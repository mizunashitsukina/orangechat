# Encrypted Backup Container

OrangeChat uses `.ocbackup` for versioned, authenticated local backup files. New manual local exports are always
encrypted. Manual import also accepts older ZIP backups after an explicit unencrypted-file warning. S3 and WebDAV keep
their existing ZIP format in this change. Encryption protects the complete selected backup; it does not exclude stored
credentials from the inner archive, and a forgotten password cannot be recovered by the app or its developers.

All integer fields in version 1 use unsigned bytes or signed big-endian integers with non-negative values. The fixed
52-byte header is:

| Offset | Length | Field |
| ---: | ---: | --- |
| 0 | 4 | ASCII magic `OCBK` |
| 4 | 1 | version (`1`) |
| 5 | 1 | algorithm (`1` = AES-256-GCM) |
| 6 | 1 | KDF (`1` = PBKDF2-HMAC-SHA-256) |
| 7 | 1 | flags (`0`) |
| 8 | 4 | header length (`52`) |
| 12 | 4 | PBKDF2 iteration count |
| 16 | 4 | plaintext chunk size |
| 20 | 2 | salt length (`16`) |
| 22 | 2 | nonce-prefix length (`8`) |
| 24 | 4 | reserved (`0`) |
| 28 | 16 | random salt |
| 44 | 8 | random nonce prefix |

Production creates the salt and nonce prefix with `SecureRandom`. PBKDF2 derives one 256-bit AES key per container,
not one per chunk. The default is 600,000 iterations; readers accept only 100,000 through 2,000,000. These bounds
follow the current OWASP PBKDF2-HMAC-SHA-256 baseline while leaving room for a later version to raise the work factor
after representative low-end Android benchmarks.

The header is followed by records. Each record begins with this 13-byte big-endian header:

| Offset | Length | Field |
| ---: | ---: | --- |
| 0 | 1 | record type (`1` data, `2` terminal) |
| 1 | 4 | strictly increasing record index |
| 5 | 4 | plaintext length |
| 9 | 4 | ciphertext length, including the 16-byte GCM tag |

For each record the 96-bit nonce is `8-byte nonce prefix || 4-byte big-endian record index`. Data records start at
index zero. The terminal record consumes the next index, so no data and terminal record can reuse a nonce. Production
limits the number of data records to 131,072, far below signed 32-bit overflow, and rejects the operation before the
terminal nonce could collide.

AES-GCM authenticates the complete 52-byte header followed by the current 13-byte record header as AAD. A data record
contains between 1 byte and the declared chunk size of plaintext, and its ciphertext length must be exactly plaintext
length plus the 16-byte tag.

The terminal record encrypts a fixed 12-byte plaintext containing the final total plaintext length (8-byte signed
big-endian integer) followed by the data-record count (4-byte signed big-endian integer). Its record index equals that
same count. Readers compare all three authenticated values with the observed stream, require a valid terminal tag, and
then require immediate physical EOF. This detects deletion of the final data record, missing records, duplication,
reordering, truncation, and appended bytes without relying on `InputStream.available()`.

Default limits are 512 MiB plaintext, 576 MiB container, 4 KiB through 4 MiB declared chunk size, a 1 MiB production
write chunk, and 131,072 data records. The parser validates header values, record lengths, count bounds and remaining
plaintext budget before allocation. It uses bounded streaming I/O rather than loading a complete archive into memory.

Decryption writes only to an application-private temporary file in the local integration. `BackupContainer` publishes
its destination only after every record, the terminal state and EOF have been authenticated. The local restore service
therefore invokes the existing ZIP preflight only after full container authentication. Failure or cancellation removes
partial plaintext and encrypted temporary files. Authentication failures use one user-safe category for an incorrect
password or damaged backup; cancellation remains cancellation and ordinary I/O failures remain separate.

Version 1 requires Android API 26 or later, matching the app minimum. Android exposes `AES/GCM/NoPadding`,
`PBKDF2WithHmacSHA256`, `GCMParameterSpec` and `SecureRandom` on that baseline. JVM tests pin the byte format and exercise
independent mutation, ordering, truncation, resource and cancellation assertions. Actual Android-provider execution on
API 26 hardware or an emulator remains a device-level compatibility check; no weaker cipher fallback is permitted.
