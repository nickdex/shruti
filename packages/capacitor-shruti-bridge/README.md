# Capacitor Shruti bridge

This is the narrow native boundary for Shruti. Its TypeScript definitions are the contract shared by the ClojureScript shell, Android host, and iOS host.

## What belongs here

- microphone permission, recording start, stop, and cancellation;
- durable ingress for shared text or audio;
- native capture-state events;
- listing and retrying the native outbox;
- native background-delivery configuration.

## What does not belong here

- notes, lists, search, tags with business meaning, AI prompts, reminders, contact lookup, or backend business logic.

## Native implementation shape

```text
Android component or iOS process
  -> CaptureIngress.ingest(CaptureInput)
  -> private media file and SQLite transaction
  -> native scheduler
  -> configured native transport adapter

Capacitor bridge
  <-> status and explicit commands only
```

### Android

- `CaptureIngress` is called from share activities, the widget, shortcut activity, Quick Settings tile, App Actions, and notifications.
- Use Room or SQLite for the outbox and app-private media files.
- Use `MediaRecorder` initially, then replace it with `AudioRecord` only when streaming or signal processing needs it.
- Use unique `WorkManager` work per capture ID for retry.

### iOS

- `CaptureIngress` is shared by the app, App Intents, widget/control targets, and Share Extension through an App Group.
- Use SQLite in WAL mode and App Group media paths.
- Use `AVAudioRecorder` initially, then `AVAudioEngine` for advanced signal processing.
- Use a file-backed background `URLSession` for upload.

## Important implementation rule

The native queue is authoritative in production. A native source must persist its request before it opens or waits for the Capacitor WebView. The browser `IndexedDB` code in `src/main/shruti/infra` exists only for local development.

The current Android test adapter is intentionally narrower: Android speech recognition produces a transcript and the native Smriti webhook adapter owns a Keystore-backed bearer key. The future durable bridge must keep that secret boundary: configuration/status APIs may expose a non-secret endpoint, but never return persisted credentials to the WebView.

The host projects are generated in `android/` and `ios/`. The next implementation milestone is to create the platform-specific bridge classes from these definitions and register them with Capacitor v8.
