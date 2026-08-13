# Encrypted Backup Container

OrangeChat reserves `.ocbackup` for a versioned, authenticated outer backup container. The container core is currently
internal and is not connected to local export, restore, S3, WebDAV, or UI flows. Existing ZIP behavior is unchanged.

Version 1 uses PBKDF2-HMAC-SHA-256 to derive a 256-bit AES key from a caller-supplied password and a fresh 128-bit salt.
The production default follows the current OWASP baseline of 600,000 iterations, with readers accepting only 100,000
through 2,000,000 iterations. The value is stored in the versioned header so future revisions can increase it after
representative low-end Android device benchmarks; production does not silently reduce it on slower devices.

Payload data is encrypted in bounded chunks with AES-256-GCM. Every container receives a fresh random salt and 64-bit
nonce prefix from `SecureRandom`; the prefix is combined with a strictly increasing 32-bit chunk counter to form each
96-bit nonce. The fixed header and each record's type, sequence number, plaintext length, and ciphertext length are
authenticated as additional data.

An authenticated empty terminal record consumes the nonce after the final data chunk. Readers require that terminal
record and immediate end-of-file, so missing, reordered, truncated, or appended records are rejected before decrypted
output is made available. Decryption writes only to a temporary file and publishes it after complete authentication.

Default limits are a 512 MiB plaintext, a 576 MiB container, 4 KiB through 4 MiB chunk sizes, a 1 MiB write chunk, and
at most 131,072 data chunks. These limits are validated before attacker-controlled lengths are used for allocation.
