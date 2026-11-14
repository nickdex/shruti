# Native integration strategy

Shruti is a capture pipe, not an assistant replacement. The operating system owns global voice invocation and background recording permissions. Every integration below feeds the same `CaptureInput` and native durable inbox.

## Android

| Source | Implementation | Recommendation |
| --- | --- | --- |
| Main screen | Capacitor capture screen plus Android `SpeechRecognizer` test adapter | Current phone-test path; replace with native file capture for production audio |
| Share text | `ACTION_SEND` with `text/plain` | Persist immediately and finish |
| Share audio | `ACTION_SEND` and `ACTION_SEND_MULTIPLE` with `audio/*` | Copy incoming URIs to app-private storage before returning |
| Long-press icon | Static `Record` app shortcut starts voice capture | Available |
| Home widget | One-button `RemoteViews` widget opens Shruti and starts recording | Available |
| Quick Settings | `TileService` that opens safe recording activity | Ship after widget |
| Notifications | Stop and retry actions on recording/upload notification | Required feedback and re-entry path |
| Gemini and Assistant | `OPEN_APP_FEATURE` plus text-only `CREATE_THING` experiment | Optional, never primary |

### Gemini and App Actions trade-off

App Actions invoke an Android Activity or deep link with extracted intent parameters. They do not hand raw microphone audio from Gemini to Shruti. A true custom hotword service would require becoming the selected `VoiceInteractionService`, which replaces the user’s global assistant. Shruti should not do that.

The realistic experience is: “Hey Gemini, open voice capture in Shruti,” then Shruti’s visible native recorder begins. Text-only App Actions can provide supplementary capture where locale, matching, and Play review allow it. Do not promise a direct audio handoff for arbitrary “Hey Gemini, remember ...” speech.

### Android recording and delivery rules

- Use `MediaRecorder` to create a file-backed AAC/M4A recording for the first production engine.
- Use a microphone foreground service only from a permitted, visible or explicitly exempt user action. Widgets have a relevant exemption; Quick Settings should safely launch an activity first.
- Put each request and file reference into Room/SQLite before networking.
- Use unique `WorkManager` work per capture ID, network constraints, and persisted backoff.
- Use a user-initiated data-transfer job only for long user-visible uploads.

### Implemented Android test slice

The Android app uses the system speech recognizer to return a final transcript. A Smriti adapter maps it to a `journal/create-entry` command, which the generic native JSON transport sends to Smriti's command API. The bearer key remains in Android Keystore and is never passed back to the WebView. This path sends text, not audio.

References: [App Actions](https://developer.android.com/develop/devices/assistant/overview), [Create Thing BII](https://developer.android.com/reference/app-actions/built-in-intents/common/create-thing), [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService), [audio input sharing](https://developer.android.com/media/platform/sharing-audio-input), [share receiving](https://developer.android.com/develop/ui/compose/sharing/receive), [widgets](https://developer.android.com/develop/ui/views/appwidgets/overview), [Quick Settings](https://developer.android.com/develop/ui/views/quicksettings-tiles), [foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), and [WorkManager data transfer](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options).

## iOS

| Source | Implementation | Recommendation |
| --- | --- | --- |
| Main screen | Native capture scene with Capacitor UI | Primary visible capture path |
| Siri and Shortcuts text | `CaptureTextIntent` | Ship early |
| Siri and Action Button voice | `StartCaptureIntent` and `AudioRecordingIntent` exploration | Foreground first, prototype on hardware |
| Share sheet | Share Extension with App Group inbox | Ship early |
| Widget | `Link` or `widgetURL` to capture scene | Ship early |
| Control Center and Lock Screen | Controls extension with `OpenIntent` | Ship after basic capture |
| Spotlight | App Shortcuts only | Do not index private transcripts or audio |
| URL scheme | Narrow `shruti://capture` navigation route | Convenience only, validate every field |

### Siri and Shortcut trade-off

iOS does not provide a custom wake phrase. App Shortcut phrases include the app name and do not provide reliable arbitrary voice dictation. Offer two App Shortcuts only: `Start Capture` and `Capture Text`. For free-form text dictation, users can compose `Dictate Text` then `Capture Text in Shruti` in Shortcuts.

For voice, an App Intent must foreground the app before a general app activates a microphone. `AudioRecordingIntent` is the deepest current option, but requires a Live Activity that remains active for the whole recording. It is a deliberate later experiment, not an assumption for the first release.

### iOS recording and delivery rules

- Use `AVAudioRecorder` to write AAC/M4A in an App Group staging directory initially.
- App, Share Extension, Widgets/Controls, and App Intents use the same App Group SQLite database and short transactional writes.
- A Share Extension copies media from `NSItemProvider` to App Group storage before completing.
- Upload through a file-backed background `URLSession`. Background upload cannot rely on an in-memory stream after termination.
- Add the audio background mode only because a visible, already-started recording genuinely continues after lock or background.
- Treat locked-widget authentication and foregrounding requirements as hard product constraints.

References: [App Shortcuts](https://developer.apple.com/documentation/appintents/app-shortcuts), [supported modes](https://developer.apple.com/documentation/appintents/appintent/supportedmodes), [AudioRecordingIntent](https://developer.apple.com/documentation/appintents/audiorecordingintent), [App Group sharing](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/ExtensionScenarios.html), [Widget interactivity](https://developer.apple.com/documentation/widgetkit/adding-interactivity-to-widgets-and-live-activities), [Controls](https://developer.apple.com/documentation/widgetkit/creating-controls-to-perform-actions-across-the-system), [AVAudioRecorder](https://developer.apple.com/documentation/avfaudio/avaudiorecorder), [recording permission](https://developer.apple.com/documentation/avfaudio/avaudioapplication/requestrecordpermission%28completionhandler%3A%29), and [background URLSession](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background).

## Cross-platform source normalization

```clojure
{:source {:kind :voice|:text|:audio|:url
          :entrypoint :app|:share-sheet|:widget|:shortcut|:app-intent|:app-action
          :platform :ios|:android}
 :content {:type :text|:audio|:audio+text
           :text nil
           :asset-ref nil}
 :locale "en-IN"
 :metadata {:source-event-id "optional-id"}}
```

The platform adapter is responsible for making the payload durable and valid. The shared pipeline is responsible for normalizing it, applying generic state rules, and arranging delivery. Neither one learns what the content means.
