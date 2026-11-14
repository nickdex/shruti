# Shruti

Shruti is a deliberately tiny native-first voice-capture client for a personal assistant. Its Android test path sends final spoken transcripts to Smriti's command API:

```text
tap → speak → stop → transcript → Smriti → done
```

It does not manage notes, organize content, search, or run AI.

## Test on Android

See the complete [Android phone guide](docs/android-device-testing.md). In short:

```sh
npm install
npm run android:doctor
npm run android:install
```

The first phone launch asks for the HTTPS Smriti command URL and API key. Existing Pebble prototype connections must be configured again. The Android native plugin stores the key encrypted under Android Keystore; it is not bundled into the APK or written to WebView storage.

On Android, long-press the Shruti app icon and tap **Record** to open the app and start recording. The shortcut uses the same microphone permission and capture flow as the main screen.

Add the **Record with Shruti** widget from the Android home screen's widget picker for one-tap recording. The widget opens Shruti before starting the microphone.

## Development commands

```sh
npm run test
npm run build
npm run verify
npm run cap:sync
npm run android:doctor
npm run android:install
npm run android:instrument
```

Browser mode is a local development fallback using IndexedDB and `MediaRecorder`. It does not configure or deliver to Smriti's authenticated cross-origin API. Use the Android build for the real speech-to-transcript and secure delivery path.

## Design and architecture

- [Architecture, state machine, extensibility, roadmap](docs/architecture.md)
- [Smriti command API contract](docs/api-contract.md)
- [Android device testing](docs/android-device-testing.md)
- [Platform integration strategy](docs/platform-integrations.md)
- [Future native bridge contract](packages/capacitor-shruti-bridge/README.md)

## Current limitation worth knowing

Smriti's command API does not deduplicate captures. A retry after an ambiguous network failure can create a duplicate Entry. Details are in the [API contract](docs/api-contract.md#current-idempotency-limitation).
