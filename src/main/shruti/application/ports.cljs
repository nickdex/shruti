(ns shruti.application.ports)

(defprotocol CaptureStore
  (put-capture! [store capture])
  (get-capture! [store capture-id])
  (list-captures! [store])
  (put-media! [store asset-id blob])
  (get-media! [store asset-id])
  (delete-media! [store asset-id]))

(defprotocol VoiceCapture
  (start! [recorder options])
  (stop! [recorder])
  (cancel! [recorder]))

(defprotocol CaptureTransport
  (deliver! [transport capture media access-token]))

(defprotocol DeliveryConfiguration
  "The small boundary for one-time transport setup. Implementations must never
   expose persisted credentials to shared application state."
  (delivery-status! [configuration])
  (configure-delivery! [configuration options])
  (clear-delivery! [configuration]))

(defprotocol AuthProvider
  "Returns opaque request authentication material owned by the auth adapter."
  (request-auth! [provider]))

(defprotocol NativeInbox
  (drain! [inbox]))

(defprotocol WorkScheduler
  (schedule! [scheduler next-at-ms]))
