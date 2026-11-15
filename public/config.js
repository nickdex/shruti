// This file is intentionally source-controlled without secrets. The Android app saves
// its bearer key in Android Keystore through the native command adapter.
window.SHRUTI_CONFIG = Object.freeze({
  // Default Smriti server host. The setup screen lets the phone override it
  // (local or production); /api/command is fixed and appended by the app.
  serverUrl: "https://smriti.nikhilwarke.in",
  locale: navigator.language || "en-IN"
});
