// This file is intentionally source-controlled without secrets. The Android app saves
// its bearer key in Android Keystore through the native command adapter.
window.SHRUTI_CONFIG = Object.freeze({
  webhookUrl: "https://smriti.nikhilwarke.in/api/command",
  locale: navigator.language || "en-IN"
});
