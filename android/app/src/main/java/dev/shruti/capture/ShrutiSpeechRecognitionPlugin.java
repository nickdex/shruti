package dev.shruti.capture;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.Locale;

/**
 * A deliberately small, in-memory adapter around Android's system speech recognizer.
 *
 * startListening resolves once the recognizer has started. stopListening stays pending until
 * Android supplies a nonblank final result, then resolves its highest-ranked transcript.
 */
@CapacitorPlugin(
    name = "ShrutiSpeechRecognition",
    permissions = {
        @Permission(alias = ShrutiSpeechRecognitionPlugin.MICROPHONE_PERMISSION, strings = { Manifest.permission.RECORD_AUDIO })
    }
)
public class ShrutiSpeechRecognitionPlugin extends Plugin {

    static final String MICROPHONE_PERMISSION = "microphone";

    // Capacitor invokes plugin methods on its own HandlerThread. Android's
    // SpeechRecognizer, however, requires every interaction on the app main
    // thread. All session state is therefore main-thread confined as well.

    private enum SessionState {
        IDLE,
        WAITING_FOR_PERMISSION,
        LISTENING,
        STOPPING,
        RESULT_READY,
        FAILED,
    }

    private static final class RecognitionFailure {

        final String code;
        final String message;

        RecognitionFailure(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    private SpeechRecognizer speechRecognizer;
    private SessionState sessionState = SessionState.IDLE;
    private PluginCall pendingStopCall;
    private String finalTranscript;
    private RecognitionFailure terminalFailure;
    private boolean permissionRequestInFlight;
    private boolean permissionRequestCancelled;
    private String pendingPermissionCallbackId;
    private long recognitionGeneration;

    @PluginMethod
    public void startListening(PluginCall call) {
        getBridge().executeOnMainThread(() -> startListeningOnMainThread(call));
    }

    private void startListeningOnMainThread(PluginCall call) {
        if (permissionRequestInFlight) {
            call.reject(
                "A microphone permission request is already in progress.",
                "MICROPHONE_PERMISSION_REQUEST_IN_PROGRESS"
            );
            return;
        }

        if (sessionState == SessionState.LISTENING || sessionState == SessionState.STOPPING) {
            call.reject("Speech recognition is already active.", "LISTENING_IN_PROGRESS");
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            call.reject(
                "Speech recognition is unavailable on this device. Install or enable a system speech recognition service and try again.",
                "SPEECH_RECOGNITION_UNAVAILABLE"
            );
            return;
        }

        discardTerminalSession();

        if (getPermissionState(MICROPHONE_PERMISSION) != PermissionState.GRANTED) {
            sessionState = SessionState.WAITING_FOR_PERMISSION;
            permissionRequestInFlight = true;
            permissionRequestCancelled = false;
            pendingPermissionCallbackId = call.getCallbackId();

            try {
                requestPermissionForAlias(MICROPHONE_PERMISSION, call, "microphonePermissionResult");
            } catch (Exception error) {
                resetPermissionRequest();
                sessionState = SessionState.IDLE;
                call.reject(
                    "Unable to request microphone permission for speech recognition.",
                    "MICROPHONE_PERMISSION_REQUEST_FAILED",
                    error
                );
            }
            return;
        }

        beginListening(call);
    }

    @PermissionCallback
    private void microphonePermissionResult(PluginCall call) {
        getBridge().executeOnMainThread(() -> microphonePermissionResultOnMainThread(call));
    }

    private void microphonePermissionResultOnMainThread(PluginCall call) {
        boolean requestWasCancelled =
            permissionRequestCancelled ||
            pendingPermissionCallbackId == null ||
            !pendingPermissionCallbackId.equals(call.getCallbackId()) ||
            sessionState != SessionState.WAITING_FOR_PERMISSION;

        resetPermissionRequest();

        if (requestWasCancelled) {
            sessionState = SessionState.IDLE;
            call.reject("Speech recognition was cancelled before it could start.", "LISTENING_CANCELLED");
            return;
        }

        if (getPermissionState(MICROPHONE_PERMISSION) != PermissionState.GRANTED) {
            sessionState = SessionState.IDLE;
            call.reject(
                "Microphone permission is required to transcribe speech.",
                "MICROPHONE_PERMISSION_DENIED"
            );
            return;
        }

        beginListening(call);
    }

    @PluginMethod
    public void stopListening(PluginCall call) {
        // The result arrives later through RecognitionListener. Set this before
        // returning to Capacitor's plugin thread so the Bridge retains the call.
        call.setKeepAlive(true);
        getBridge().executeOnMainThread(() -> stopListeningOnMainThread(call));
    }

    private void stopListeningOnMainThread(PluginCall call) {
        if (sessionState == SessionState.RESULT_READY) {
            String transcript = finalTranscript;
            sessionState = SessionState.IDLE;
            clearTerminalState();
            resolveTranscript(call, transcript);
            return;
        }

        if (sessionState == SessionState.FAILED) {
            RecognitionFailure failure = terminalFailure;
            sessionState = SessionState.IDLE;
            clearTerminalState();
            reject(call, failure);
            return;
        }

        if (sessionState == SessionState.STOPPING) {
            reject(call, new RecognitionFailure("STOP_ALREADY_REQUESTED", "Speech recognition is already stopping."));
            return;
        }

        if (sessionState == SessionState.WAITING_FOR_PERMISSION) {
            reject(
                call,
                new RecognitionFailure(
                    "LISTENING_NOT_READY",
                    "Speech recognition cannot stop until microphone permission has been resolved."
                )
            );
            return;
        }

        if (sessionState != SessionState.LISTENING || speechRecognizer == null) {
            reject(
                call,
                new RecognitionFailure("NO_ACTIVE_LISTENING_SESSION", "There is no active speech recognition session to stop.")
            );
            return;
        }

        sessionState = SessionState.STOPPING;
        pendingStopCall = call;

        try {
            speechRecognizer.stopListening();
        } catch (Exception error) {
            handleRecognitionFailure(
                recognitionGeneration,
                new RecognitionFailure("SPEECH_RECOGNITION_STOP_FAILED", "Unable to stop speech recognition.")
            );
        }
    }

    @PluginMethod
    public void cancelListening(PluginCall call) {
        getBridge().executeOnMainThread(() -> cancelListeningOnMainThread(call));
    }

    private void cancelListeningOnMainThread(PluginCall call) {
        if (sessionState == SessionState.WAITING_FOR_PERMISSION) {
            permissionRequestCancelled = true;
        }

        rejectPendingStop(new RecognitionFailure("LISTENING_CANCELLED", "Speech recognition was cancelled."));
        clearActiveRecognizer();
        sessionState = SessionState.IDLE;
        clearTerminalState();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        permissionRequestCancelled = permissionRequestInFlight;
        rejectPendingStop(
            new RecognitionFailure("LISTENING_CANCELLED", "Speech recognition stopped because the activity was destroyed.")
        );
        clearActiveRecognizer();
        sessionState = SessionState.IDLE;
        clearTerminalState();
        super.handleOnDestroy();
    }

    private void beginListening(PluginCall call) {
        if (getPermissionState(MICROPHONE_PERMISSION) != PermissionState.GRANTED) {
            sessionState = SessionState.IDLE;
            call.reject(
                "Microphone permission is required to transcribe speech.",
                "MICROPHONE_PERMISSION_DENIED"
            );
            return;
        }

        final long generation = ++recognitionGeneration;
        final SpeechRecognizer recognizer;

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {}

                @Override
                public void onBeginningOfSpeech() {}

                @Override
                public void onRmsChanged(float rmsdB) {}

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {}

                @Override
                public void onError(int error) {
                    handleRecognitionFailure(generation, recognitionFailureFor(error));
                }

                @Override
                public void onResults(Bundle results) {
                    handleFinalResults(generation, results);
                }

                @Override
                public void onPartialResults(Bundle partialResults) {}

                @Override
                public void onEvent(int eventType, Bundle params) {}
            });

            speechRecognizer = recognizer;
            sessionState = SessionState.LISTENING;
            recognizer.startListening(recognizerIntent(call));
        } catch (Exception error) {
            clearActiveRecognizer();
            sessionState = SessionState.IDLE;
            call.reject(
                "Unable to start speech recognition.",
                "SPEECH_RECOGNITION_START_FAILED",
                error
            );
            return;
        }

        call.resolve(new JSObject().put("started", true));
    }

    private Intent recognizerIntent(PluginCall call) {
        String requestedLocale = call.getString("locale");
        String locale = requestedLocale == null || requestedLocale.trim().isEmpty()
            ? Locale.getDefault().toLanguageTag()
            : requestedLocale.trim();

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        return intent;
    }

    private void handleFinalResults(long generation, Bundle results) {
        if (generation != recognitionGeneration) {
            return;
        }

        String transcript = bestFinalTranscript(results);
        if (transcript == null) {
            handleRecognitionFailure(
                generation,
                new RecognitionFailure(
                    "NO_FINAL_TRANSCRIPT",
                    "Speech recognition completed without a nonblank final transcript."
                )
            );
            return;
        }

        if (pendingStopCall != null) {
            PluginCall stopCall = pendingStopCall;
            pendingStopCall = null;
            clearActiveRecognizer();
            sessionState = SessionState.IDLE;
            clearTerminalState();
            resolveTranscript(stopCall, transcript);
            return;
        }

        finalTranscript = transcript;
        terminalFailure = null;
        clearActiveRecognizer();
        sessionState = SessionState.RESULT_READY;
    }

    private void handleRecognitionFailure(long generation, RecognitionFailure failure) {
        if (generation != recognitionGeneration) {
            return;
        }

        PluginCall stopCall = pendingStopCall;
        pendingStopCall = null;
        clearActiveRecognizer();

        if (stopCall != null) {
            sessionState = SessionState.IDLE;
            clearTerminalState();
            reject(stopCall, failure);
            return;
        }

        finalTranscript = null;
        terminalFailure = failure;
        sessionState = SessionState.FAILED;
    }

    private String bestFinalTranscript(Bundle results) {
        if (results == null) {
            return null;
        }

        ArrayList<String> candidates = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (candidates == null) {
            return null;
        }

        // Use the platform's first nonblank final candidate as its best transcript.
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate.trim();
            }
        }

        return null;
    }

    private RecognitionFailure recognitionFailureFor(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return new RecognitionFailure("RECOGNITION_AUDIO_ERROR", "Speech recognition encountered an audio error.");
            case SpeechRecognizer.ERROR_CLIENT:
                return new RecognitionFailure("RECOGNITION_CLIENT_ERROR", "Speech recognition was interrupted by the device.");
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return new RecognitionFailure(
                    "MICROPHONE_PERMISSION_DENIED",
                    "The speech recognition service does not have microphone permission."
                );
            case SpeechRecognizer.ERROR_NETWORK:
                return new RecognitionFailure("RECOGNITION_NETWORK_ERROR", "Speech recognition could not reach its network service.");
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return new RecognitionFailure("RECOGNITION_NETWORK_TIMEOUT", "Speech recognition timed out while contacting its service.");
            case SpeechRecognizer.ERROR_NO_MATCH:
                return new RecognitionFailure("NO_FINAL_TRANSCRIPT", "Speech recognition could not find a final transcript.");
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return new RecognitionFailure("RECOGNIZER_BUSY", "The system speech recognizer is busy. Try again shortly.");
            case SpeechRecognizer.ERROR_SERVER:
                return new RecognitionFailure("RECOGNITION_SERVER_ERROR", "The system speech recognition service returned an error.");
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return new RecognitionFailure("SPEECH_TIMEOUT", "Speech recognition did not detect speech before timing out.");
            default:
                return new RecognitionFailure("RECOGNITION_ERROR", "Speech recognition failed with error code " + error + ".");
        }
    }

    private void discardTerminalSession() {
        if (sessionState == SessionState.RESULT_READY || sessionState == SessionState.FAILED) {
            sessionState = SessionState.IDLE;
            clearTerminalState();
        }
    }

    private void clearActiveRecognizer() {
        recognitionGeneration++;
        SpeechRecognizer recognizer = speechRecognizer;
        speechRecognizer = null;

        if (recognizer == null) {
            return;
        }

        try {
            recognizer.cancel();
        } catch (RuntimeException ignored) {
            // The recognizer can already be stopped by the time a final callback arrives.
        }

        try {
            recognizer.destroy();
        } catch (RuntimeException ignored) {
            // Destruction is best-effort during Android activity teardown.
        }
    }

    private void clearTerminalState() {
        finalTranscript = null;
        terminalFailure = null;
    }

    private void resetPermissionRequest() {
        permissionRequestInFlight = false;
        permissionRequestCancelled = false;
        pendingPermissionCallbackId = null;
    }

    private void resolveTranscript(PluginCall call, String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) {
            reject(call, new RecognitionFailure("NO_FINAL_TRANSCRIPT", "Speech recognition returned no final transcript."));
            return;
        }

        call.setKeepAlive(false);
        call.resolve(new JSObject().put("transcript", transcript));
    }

    private void rejectPendingStop(RecognitionFailure failure) {
        PluginCall stopCall = pendingStopCall;
        pendingStopCall = null;

        if (stopCall != null) {
            reject(stopCall, failure);
        }
    }

    private void reject(PluginCall call, RecognitionFailure failure) {
        RecognitionFailure safeFailure = failure == null
            ? new RecognitionFailure("RECOGNITION_ERROR", "Speech recognition failed.")
            : failure;
        call.setKeepAlive(false);
        call.reject(safeFailure.message, safeFailure.code);
    }
}
