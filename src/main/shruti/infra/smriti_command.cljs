(ns shruti.infra.smriti-command
  "Smriti's POST /api/command wire mapping.

   A capture becomes a scripted journal command. The mapping lives apart
   from the transport that sends it, so the transport stays usable for
   any JSON command endpoint."
  (:require [clojure.string :as str]
            [shruti.infra.command-transport :as transport]))

(defn- final-text [request]
  (some-> (or (get-in request [:content :transcript])
              (get-in request [:content :text]))
          str
          str/trim
          not-empty))

(defn capture->command
  "The JSON body Smriti's /api/command accepts for a text-bearing capture.

   `created-at` is the original occurred-at instant: Smriti parses an
   offset date-time as-is, so a note spoken near midnight keeps the day it
   happened rather than the day it was delivered. Returns nil when there is
   no final text, because the command creates an entry from text only."
  [request]
  (when-let [text (final-text request)]
    (cond-> {"command/name" "journal/create-entry"
             "text" text
             "tags" ["shruti" "todo"]}
      (not (str/blank? (str (:occurred-at request))))
      (assoc "created-at" (:occurred-at request)))))

(defn create [webhook-url]
  (transport/create webhook-url capture->command))
