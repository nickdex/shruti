# Test Shruti on an Android phone

Shruti is prepared for a physical Android-device test. The debug APK contains no Smriti API key. On launch, enter the command API URL and key on the phone; Android stores the key in Keystore and the native transport uses it without returning it to the WebView. Existing Pebble prototype connections must be configured again.

## One-time machine prerequisites

The generated Capacitor Android host currently requires:

- JDK 21 (not JDK 26)
- Android SDK Platform 36
- Android SDK Build-Tools 36.x
- Android platform-tools / `adb`
- A USB-debugging-enabled, authorized Android phone

On this Mac, after installing those prerequisites, set the build environment:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_SDK_ROOT=/Users/nik/Library/Android/sdk
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
```

Android Studio's SDK Manager is the simplest way to install the required Platform and Build-Tools. The project intentionally retains compile/target SDK 36 rather than silently downgrading to an older local SDK.

## Install and run

From the project root:

```sh
npm install
npm run android:doctor
npm run android:install
```

`android:doctor` fails early with concrete instructions if Java, SDK components, or an authorized device are missing. `android:install` runs the same check, syncs Capacitor's web assets, builds the debug APK once, and installs it on every authorized ADB device. Offline or unauthorized devices are reported, and any skipped or failed installation makes the command fail after attempting the others.

On the phone:

1. Open **Shruti**.
2. Enter the HTTPS Smriti URL ending in `/api/command`.
3. Enter the Smriti API key once and choose **Save connection**.
4. Grant microphone permission.
5. Tap the large button, speak, then tap it again to stop.
6. Confirm that the final transcript appears and that Smriti receives the entry.

The Android speech recognizer may use the device's selected speech-recognition service; make sure that service is enabled and has network access if it is cloud-backed.

## Native smoke test

With the phone connected and authorized:

```sh
npm run android:instrument
```

This runs the small host instrumentation smoke test. It does not replace the manual microphone and Smriti delivery check.

## Common failures

| Symptom | Resolution |
| --- | --- |
| `Unsupported class file major version 70` | Select JDK 21 with `JAVA_HOME`; the current JDK 26 cannot run this Gradle setup. |
| `android-36` or Build-Tools 36 missing | Install them through Android Studio's SDK Manager, then rerun `android:doctor`. |
| No device listed | Enable USB debugging, accept the computer's authorization dialog, then run `adb devices -l`. |
| Speech recognition unavailable | Enable or install a system speech-recognition service; Shruti surfaces the Android error instead of fabricating a transcript. |
| `401` or `403` from Smriti | Tap **Connection** at the bottom of Shruti, then enter the current API key. |
| Duplicate entry after a network timeout | Smriti does not deduplicate capture retries; see [the API contract](api-contract.md#current-idempotency-limitation). |

## Current scope

This validates the native speech-to-transcript and secure command delivery path. The device still uses the shared IndexedDB queue rather than the planned Android SQLite/WorkManager outbox, so background persistence and crash-recovery guarantees remain the next reliability milestone.
