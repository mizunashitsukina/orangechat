# Android System Backup Policy

OrangeChat disables Android system cloud backup and device-transfer backup for application data. The application can
store credentials, conversations, attachments, and other private content, so these data must not be copied implicitly
by Android, Google, or device-vendor migration services.

The manifest disables system backup, and both Android backup-rule formats explicitly exclude every supported
credential-protected and device-protected app-data domain. Keep both layers: some Android device implementations do not
apply the manifest switch consistently to device-to-device transfers.

This policy does not change OrangeChat's in-app export and restore features. A protected in-app encrypted backup format
will be implemented and reviewed separately. Until then, system backup and device migration must not be treated as a
supported OrangeChat data-transfer mechanism.
