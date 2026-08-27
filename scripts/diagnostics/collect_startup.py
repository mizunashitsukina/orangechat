"""Diagnostic branches only. No log clearing; persist only allowlisted milestones.

One fresh logcat stream per force-stop launch. PID is used in memory solely to reject
buffered events from another process, and is never printed or persisted. The latest
ActivityOnCreate in that process resets the sequence. No missing/out-of-order events
are inferred. All timings come from the app's monotonic clock, not am start TotalTime.
"""
import argparse
import json
from pathlib import Path
import queue
import re
import statistics
import subprocess
import threading
import time


EVENTS = (
    "ActivityOnCreate", "ComposeRoot", "SettingsReady", "GateAccepted",
    "MainComposition", "MAIN_FIRST_FRAME_VISIBLE",
)
ALLOWED = frozenset(EVENTS + ("ApplicationOnCreate", "GateLoading", "SettingsAlreadyReady"))
LINE = re.compile(
    r"^I/OrangeStartupTiming\(\s*(\d+)\):\s*"
    r"event=([A-Za-z_]+) elapsedMs=(\d+)\s*$"
)
PHASES = (
    "ACTIVITY_TO_COMPOSE", "COMPOSE_TO_SETTINGS", "SETTINGS_TO_ACCEPTED",
    "ACCEPTED_TO_MAIN", "MAIN_TO_FIRST_FRAME", "ACTIVITY_TO_FIRST_FRAME",
)
PACKAGE = "me.rerere.orangechat.debug"
COMPONENT = PACKAGE + "/me.rerere.rikkahub.RouteActivity"


class DiagnosticFailure(Exception):
    """Only callers' fixed labels, never subprocess output or untrusted log lines."""


def parse_line(line):
    match = LINE.fullmatch(line.strip())
    if match and match[2] in ALLOWED and len(match[3]) <= 15:
        return int(match[1]), match[2], int(match[3])
    return None


class Sequence:
    def __init__(self):
        self.events = {}
        self.pending_settings_snapshot = None

    def add(self, event, milliseconds):
        if event == EVENTS[0]:
            self.events = {event: milliseconds}
            self.pending_settings_snapshot = None
            return
        if not self.events:
            return
        if event == "SettingsAlreadyReady":
            # Only accept an explicit in-process snapshot AFTER the latest Activity marker.
            # No pre-Activity log is used to fill a missing event. Its value is the actual
            # first emission time, not the snapshot time and not a dummy/default setting.
            if milliseconds > self.events["ActivityOnCreate"]:
                raise DiagnosticFailure("INVALID_SETTINGS_SNAPSHOT")
            existing = self.events.get("SettingsReady")
            if existing is not None and existing != milliseconds:
                raise DiagnosticFailure("CONFLICTING_SETTINGS_SNAPSHOT")
            if self.pending_settings_snapshot not in (None, milliseconds):
                raise DiagnosticFailure("CONFLICTING_SETTINGS_SNAPSHOT")
            self.events["SettingsReady"] = milliseconds
            return
        if event not in EVENTS:
            return
        # Concurrent mark/snapshot emission can report the same actual read twice.
        if event == "SettingsReady" and self.events.get(event) == milliseconds:
            return
        if event == "SettingsReady" and milliseconds < self.events["ActivityOnCreate"]:
            # A worker can finish its log write between the Activity marker and snapshot.
            # Do not accept it as a milestone without the explicit post-boundary snapshot.
            self.pending_settings_snapshot = milliseconds
            return
        # DataStore runs concurrently: SettingsReady can precede ComposeRoot. Preserve
        # its real timestamp instead of inventing a wait or changing app initialization.
        prerequisites = {
            "ComposeRoot": ("ActivityOnCreate",),
            "SettingsReady": ("ActivityOnCreate",),
            "GateAccepted": ("ComposeRoot", "SettingsReady"),
            "MainComposition": ("GateAccepted",),
            "MAIN_FIRST_FRAME_VISIBLE": ("MainComposition",),
        }
        missing = [name for name in prerequisites[event] if name not in self.events]
        if event in self.events or missing:
            raise DiagnosticFailure("OUT_OF_ORDER expected=" + (",".join(missing) or "UNIQUE_EVENT"))
        if milliseconds < max(self.events.values()):
            raise DiagnosticFailure("NON_MONOTONIC event=" + event)
        self.events[event] = milliseconds

    @property
    def complete(self):
        return len(self.events) == len(EVENTS)

    @property
    def missing(self):
        return ",".join(event for event in EVENTS if event not in self.events)

    def phases(self):
        if not self.complete:
            raise DiagnosticFailure("MISSING events=" + self.missing)
        values = [self.events[event] for event in EVENTS]
        return dict(zip(PHASES, [b - a for a, b in zip(values, values[1:])] +
                        [values[-1] - values[0]]))

    def critical_path(self):
        self.phases()  # Require a complete real sample.
        activity, compose, settings, accepted, main, frame = [self.events[event] for event in EVENTS]
        ready = max(compose, settings)
        return {
            "ACTIVITY_TO_COMPOSE": compose - activity,
            "SETTINGS_WAIT": ready - compose,
            "READY_TO_ACCEPTED": accepted - ready,
            "ACCEPTED_TO_MAIN": main - accepted,
            "MAIN_TO_FIRST_FRAME": frame - main,
        }


def checked_adb(label, *arguments):
    try:
        result = subprocess.run(["adb", *arguments], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True, timeout=90)
    except subprocess.TimeoutExpired:
        raise DiagnosticFailure("COMMAND_TIMEOUT step=" + label) from None
    except OSError:
        raise DiagnosticFailure("COMMAND_UNAVAILABLE step=" + label) from None
    if result.returncode:
        raise DiagnosticFailure("COMMAND_FAILED step=" + label)
    return result.stdout


def collect_round(run, directory, timeout_seconds=60):
    records = queue.Queue(maxsize=256)
    overflow = threading.Event()
    # Explicit tag allowlist and brief format. Do not save raw logcat or stderr.
    capture = subprocess.Popen(
        ["adb", "logcat", "-v", "brief", "-T", "1", "OrangeStartupTiming:I", "*:S"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
    )

    def read_records():
        for line in capture.stdout:
            record = parse_line(line)
            if record:
                try:
                    records.put_nowait(record)
                except queue.Full:
                    overflow.set()

    reader = threading.Thread(target=read_records, daemon=True)
    reader.start()
    sequence = Sequence()
    try:
        # Stream is opened before force-stop. Never clear or alter any log buffer.
        checked_adb("ForceStop", "shell", "am", "force-stop", PACKAGE)
        launch = checked_adb("ColdStart", "shell", "am", "start", "-W", "-n", COMPONENT)
        if not re.search(r"^Status: ok\s*$", launch, re.MULTILINE):
            raise DiagnosticFailure("LAUNCH_FAILED")
        process = checked_adb("ProcessCheck", "shell", "pidof", PACKAGE).strip()
        if not re.fullmatch(r"\d+", process):
            raise DiagnosticFailure("PROCESS_UNAVAILABLE")
        process = int(process)
        deadline = time.monotonic() + timeout_seconds
        # A distinct file for every round, containing only current-process fixed milestones.
        with (directory / f"run-{run}.log").open("x", encoding="utf-8") as output:
            while True:
                if overflow.is_set():
                    raise DiagnosticFailure("PROBE_OVERFLOW")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise DiagnosticFailure("TIMEOUT events=" + sequence.missing)
                try:
                    pid, event, milliseconds = records.get(timeout=remaining)
                except queue.Empty:
                    raise DiagnosticFailure("TIMEOUT events=" + sequence.missing) from None
                if pid != process:
                    continue
                output.write(f"event={event} elapsedMs={milliseconds}\n")
                output.flush()
                sequence.add(event, milliseconds)
                # am start has completed. Consume any buffered later onCreate before accepting.
                if sequence.complete and records.empty():
                    return sequence
    finally:
        capture.terminate()
        try:
            capture.wait(timeout=5)
        except subprocess.TimeoutExpired:
            capture.kill()
            capture.wait(timeout=5)
        reader.join(timeout=5)
        capture.stdout.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    results = []
    for run in range(1, 8):
        try:
            sequence = collect_round(run, args.output)
        except (DiagnosticFailure, OSError) as error:
            detail = str(error) if isinstance(error, DiagnosticFailure) else "COLLECTOR_IO_FAILED"
            print(f"STARTUP_FAILURE run={run} {detail}", flush=True)
            return 1
        phases = sequence.phases()
        results.append({"run": run, "eventsMs": sequence.events, "phasesMs": phases,
                        "criticalPathMs": sequence.critical_path()})
        print("STARTUP_RESULT " + json.dumps(results[-1], sort_keys=True), flush=True)
    summary = {"runs": results, "medianMs": {
        phase: statistics.median(row["phasesMs"][phase] for row in results) for phase in PHASES
    }}
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print("STARTUP_MEDIANS " + json.dumps(summary["medianMs"], sort_keys=True), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
