# Build and distribute an Android release

Release APKs ship from GitHub Releases and are installed and updated by
[Obtainium](https://github.com/ImranR98/Obtainium). Obtainium reads the APK's
`versionName`, so every release must carry a newer version and the same signing
key as the previous one.

## Version source

`package.json` is the single version source. `android/app/build.gradle` derives
`versionName` from it and derives `versionCode` as
`major * 10000 + minor * 100 + patch`, which keeps upgrade ordering monotonic
without a second number to maintain.

Bump the version, update `CHANGELOG.md`, and rebuild. Do not edit
`build.gradle` for a release.

## Signing key

The release key lives outside the repository:

- Keystore: `~/.keystores/shruti-release.jks`
- Credentials: `~/.keystores/shruti-release.properties`
- Gradle reads: `android/keystore.properties` (git-ignored, copied from the
  credentials file above)

`android/app/build.gradle` signs the release build only when
`android/keystore.properties` exists, and leaves the build unsigned otherwise.
The keystore and passwords never enter the repository or the APK.

Losing the keystore means installed copies can no longer be updated in place:
Obtainium refuses an APK signed by a different key, and the phone must uninstall
and reinstall. Back up `~/.keystores/shruti-release.jks` and its properties
file somewhere durable.

To create the key on a new machine, restore the backup instead of generating a
new one. Generating is only correct for a project that has never shipped.

## Build

The Android host requires JDK 21 and Android SDK Platform/Build-Tools 36:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
npm run android:release
```

`android:release` syncs Capacitor's web assets, builds the release APK, checks
that its `versionName` matches `package.json`, verifies the signature with
`apksigner`, and copies the artifact to `dist/shruti-<version>.apk`. It prints
the APK's SHA-256 for the release notes.

## Publish

```sh
gh release create v<version> dist/shruti-<version>.apk \
  --title "v<version>" \
  --notes-file <notes>
```

The APK asset name must keep the `shruti-<version>.apk` shape. Obtainium
matches `.apk` assets in a release; a stable name keeps each release's asset
list unambiguous.

## Install and update with Obtainium

1. Install Obtainium.
2. **Add App**, then paste `https://github.com/nickdex/shruti`.
3. Obtainium lists the latest GitHub release, downloads
   `shruti-<version>.apk`, and installs it. It re-checks the repository for new
   releases on its own schedule and offers the update when `versionName`
   increases.

Because the API key lives in Android Keystore under the app's own signing
identity, updating through Obtainium keeps the existing configuration. An
uninstall-and-reinstall does not.

The release is only installable from Obtainium if Android allows it: grant
Obtainium "Install unknown apps" once.
