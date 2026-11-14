import type { PluginListenerHandle } from "@capacitor/core";

export type CaptureKind = "voice" | "text" | "audio" | "url" | "clipboard";
export type CaptureEntrypoint =
  | "app"
  | "share-sheet"
  | "widget"
  | "shortcut"
  | "quick-settings"
  | "app-intent"
  | "app-action"
  | "url-scheme"
  | "notification"
  | "external-accessory";
export type CapturePlatform = "ios" | "android";

export interface CaptureInput {
  source: {
    kind: CaptureKind;
    entrypoint: CaptureEntrypoint;
    platform: CapturePlatform;
  };
  content: {
    type: "text" | "audio" | "audio+text";
    text?: string;
    assetRef?: string;
  };
  locale?: string;
  metadata?: Record<string, string>;
}

export interface StartRecordingOptions {
  captureId: string;
  source: CaptureInput["source"];
  locale?: string;
}

export interface RecordingSession {
  captureId: string;
  sessionId: string;
}

export interface LocalAudioAsset {
  assetId: string;
  mimeType: string;
  durationMs: number;
  bytes: number;
  sha256?: string;
}

export interface CaptureSnapshot {
  id: string;
  contentState: "recording" | "finalizing" | "ready" | "discarded";
  deliveryState: "not-ready" | "queued" | "leased" | "accepted" | "blocked-auth" | "rejected" | "none";
  retryCount: number;
  lastError?: string;
}

export interface DeliveryConfiguration {
  endpoint: string;
}

export interface ShrutiCapturePlugin {
  requestMicrophonePermission(): Promise<{ granted: boolean }>;
  startRecording(options: StartRecordingOptions): Promise<RecordingSession>;
  stopRecording(): Promise<LocalAudioAsset>;
  cancelRecording(): Promise<void>;
  ingest(input: CaptureInput): Promise<CaptureSnapshot>;
  listPendingCaptures(): Promise<{ captures: CaptureSnapshot[] }>;
  retryCapture(options: { id: string }): Promise<void>;
  configureDelivery(options: DeliveryConfiguration): Promise<void>;
  addListener(
    eventName: "captureChanged" | "recordingChanged",
    listenerFunc: (event: CaptureSnapshot | RecordingSession) => void,
  ): Promise<PluginListenerHandle>;
}

