# Changelog

## [0.4.0] - 2026-11-26

### Added

- Group destination quick picks and an editable, locally persisted review
  before sending. Pending messages remain available for retry or reassignment.
- Secure Group discovery, offline selection and capture-ID deduplication with
  Smriti. Accepted messages retain their delivery receipts.

- Auto-send experiment: a successfully recorded, non-empty transcript is
  submitted automatically three seconds after its review is saved and shown.
  A fill grows left to right inside the Send button during the countdown.
  Sending manually, touching the review, changing the destination, or leaving
  the app cancels the countdown; cancelled and restored recordings still
  require explicit Send or Discard.

### Changed

- Private capture commands omit `group-id`.

## [0.3.0] - 2026-11-15

### Changed

- The setup screen takes only the Smriti server host and shows the fixed
  `/api/command` path as a label; the app appends it. One build can point at a
  local or production server.
- Runtime config reads `SHRUTI_CONFIG` through `goog.object`, so advanced
  compilation no longer blanks the default host and locale.

## [0.2.0] - 2026-11-15

### Added

- Signed release builds with the version derived from `package.json`.
- GitHub Releases publishing and Obtainium-based install and update flow.

### Changed

- Entries delivered to Smriti now carry the `todo` Tag alongside `shruti`.

### Known limitations

- Retrying an ambiguously delivered capture may create a duplicate Entry.
- Updating requires the same release key; a different key needs an uninstall first.

## [0.1.0] - 2026-11-14

### Added

- Android voice capture with local queue and authenticated Smriti command delivery.
- Android app shortcut and home-screen widget to start recording.
- Secure connection setup using Android Keystore.
- Debug installation to connected authorized Android devices.

### Known limitations

- Retrying an ambiguously delivered capture may create a duplicate Entry.
- Android release APKs require signing before installation.
