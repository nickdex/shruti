# Changelog

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
