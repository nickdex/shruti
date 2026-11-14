(ns shruti.infra.auth
  (:require [shruti.application.ports :as ports]))

(defrecord DevelopmentAuthProvider [token]
  ports/AuthProvider
  (request-auth! [_]
    (js/Promise.resolve
     {:headers (if (seq token)
                 {"Authorization" (str "Bearer " token)}
                 {})})))

(defn development-provider [token]
  (->DevelopmentAuthProvider token))

(defn no-auth-provider []
  (development-provider ""))
