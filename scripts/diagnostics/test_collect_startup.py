import unittest

from collect_startup import DiagnosticFailure, EVENTS, PHASES, Sequence, parse_line


class StartupCollectorTest(unittest.TestCase):
    def test_only_fixed_tag_events_and_milliseconds_are_accepted(self):
        self.assertEqual(parse_line("I/OrangeStartupTiming( 42): event=MAIN_FIRST_FRAME_VISIBLE elapsedMs=123"),
                         (42, "MAIN_FIRST_FRAME_VISIBLE", 123))
        for line in (
            "I/OtherTag( 42): event=GateAccepted elapsedMs=1",
            "I/OrangeStartupTiming( 42): event=PRIVATE_MARKER elapsedMs=1",
            "I/OrangeStartupTiming( 42): event=GateAccepted elapsedMs=1 PRIVATE_MARKER",
            "I/OrangeStartupTiming( 42): event=GateAccepted elapsedMs=-1",
        ):
            self.assertIsNone(parse_line(line))

    def test_complete_sequence_computes_each_real_stage(self):
        sequence = Sequence()
        for event, ms in zip(EVENTS, (10, 30, 80, 85, 87, 120)):
            sequence.add(event, ms)
        self.assertEqual(list(sequence.phases().values()), [20, 50, 5, 2, 33, 110])
        self.assertEqual(list(sequence.phases()), list(PHASES))

    def test_old_tail_is_ignored_before_new_activity(self):
        sequence = Sequence()
        for event in EVENTS[1:]:
            sequence.add(event, 999)
        self.assertEqual(sequence.events, {})
        for event, ms in zip(EVENTS, range(6)):
            sequence.add(event, ms)
        self.assertTrue(sequence.complete)

    def test_latest_activity_resets_even_a_previous_complete_sample(self):
        sequence = Sequence()
        for event, ms in zip(EVENTS, range(6)):
            sequence.add(event, ms)
        sequence.add("ActivityOnCreate", 10)
        sequence.add("ComposeRoot", 20)
        self.assertFalse(sequence.complete)
        with self.assertRaisesRegex(DiagnosticFailure, "MISSING events=SettingsReady"):
            sequence.phases()

    def test_missing_frame_is_not_replaced_by_main_composition(self):
        sequence = Sequence()
        for event, ms in zip(EVENTS[:-1], range(5)):
            sequence.add(event, ms)
        self.assertEqual(sequence.missing, "MAIN_FIRST_FRAME_VISIBLE")
        self.assertFalse(sequence.complete)

    def test_out_of_order_and_duplicate_events_fail(self):
        for event in ("GateAccepted", "MAIN_FIRST_FRAME_VISIBLE"):
            sequence = Sequence()
            sequence.add("ActivityOnCreate", 10)
            with self.assertRaisesRegex(DiagnosticFailure, "OUT_OF_ORDER expected="):
                sequence.add(event, 20)
        sequence = Sequence()
        sequence.add("ActivityOnCreate", 10)
        sequence.add("ComposeRoot", 20)
        with self.assertRaises(DiagnosticFailure):
            sequence.add("ComposeRoot", 21)

    def test_settings_before_compose_is_preserved_not_fabricated_as_a_wait(self):
        sequence = Sequence()
        for event, ms in (("ActivityOnCreate", 735), ("SettingsReady", 853),
                          ("ComposeRoot", 900), ("GateAccepted", 930),
                          ("MainComposition", 940), ("MAIN_FIRST_FRAME_VISIBLE", 990)):
            sequence.add(event, ms)
        self.assertEqual(sequence.events["SettingsReady"], 853)
        self.assertEqual(sequence.phases()["COMPOSE_TO_SETTINGS"], -47)
        self.assertEqual(sequence.critical_path()["SETTINGS_WAIT"], 0)
        self.assertEqual(sequence.critical_path()["READY_TO_ACCEPTED"], 30)
        self.assertEqual(sum(sequence.critical_path().values()), 255)

    def test_current_activity_snapshot_preserves_settings_completed_before_activity(self):
        sequence = Sequence()
        sequence.add("SettingsReady", 414)  # Buffered pre-boundary events cannot fill the sample.
        sequence.add("ActivityOnCreate", 422)
        self.assertNotIn("SettingsReady", sequence.events)
        sequence.add("SettingsAlreadyReady", 414)  # Fresh snapshot after this onCreate.
        sequence.add("SettingsReady", 414)  # Identical concurrent log is harmless.
        for event, ms in (("ComposeRoot", 491), ("GateAccepted", 772),
                          ("MainComposition", 772), ("MAIN_FIRST_FRAME_VISIBLE", 1400)):
            sequence.add(event, ms)
        self.assertEqual(sequence.events["SettingsReady"], 414)
        self.assertEqual(sequence.phases()["COMPOSE_TO_SETTINGS"], -77)
        self.assertEqual(sequence.critical_path()["SETTINGS_WAIT"], 0)
        self.assertEqual(sum(sequence.critical_path().values()), 978)

    def test_old_or_conflicting_snapshots_cannot_fill_new_activity(self):
        sequence = Sequence()
        sequence.add("SettingsAlreadyReady", 1)
        sequence.add("ActivityOnCreate", 20)
        sequence.add("ComposeRoot", 30)
        with self.assertRaisesRegex(DiagnosticFailure, "expected=SettingsReady"):
            sequence.add("GateAccepted", 40)
        with self.assertRaisesRegex(DiagnosticFailure, "INVALID_SETTINGS_SNAPSHOT"):
            sequence.add("SettingsAlreadyReady", 21)
        sequence.add("SettingsAlreadyReady", 10)
        with self.assertRaisesRegex(DiagnosticFailure, "CONFLICTING_SETTINGS_SNAPSHOT"):
            sequence.add("SettingsAlreadyReady", 11)

    def test_concurrent_early_settings_log_still_requires_activity_snapshot(self):
        sequence = Sequence()
        sequence.add("ActivityOnCreate", 422)
        sequence.add("SettingsReady", 414)
        self.assertNotIn("SettingsReady", sequence.events)
        sequence.add("SettingsAlreadyReady", 414)
        self.assertEqual(sequence.events["SettingsReady"], 414)

    def test_monotonic_clock_required(self):
        sequence = Sequence()
        sequence.add("ActivityOnCreate", 10)
        with self.assertRaisesRegex(DiagnosticFailure, "NON_MONOTONIC event=ComposeRoot"):
            sequence.add("ComposeRoot", 9)

    def test_loading_is_auxiliary_not_a_main_frame(self):
        sequence = Sequence()
        sequence.add("ActivityOnCreate", 10)
        sequence.add("ComposeRoot", 20)
        sequence.add("GateLoading", 21)
        self.assertEqual(sequence.missing, ",".join(EVENTS[2:]))


if __name__ == "__main__":
    unittest.main()
