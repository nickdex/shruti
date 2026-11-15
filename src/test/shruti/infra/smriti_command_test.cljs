(ns shruti.infra.smriti-command-test
  (:require [cljs.test :refer [deftest is testing]]
            [shruti.infra.smriti-command :as command]))

(def request
  {:id "f38b1d22-c4cc-4c0a-8120-8ef4e3f70b51"
   :occurred-at "2026-07-27T12:30:00.000Z"
   :content {:type :text
             :text "Buy 20kg potatoes tomorrow."
             :transcript "Buy 20kg potatoes tomorrow."
             :audio nil}})

(deftest maps-a-capture-to-a-journal-create-entry-command
  (is (= {"command/name" "journal/create-entry"
          "text" "Buy 20kg potatoes tomorrow."
          "tags" ["shruti" "todo"]
          "created-at" "2026-07-27T12:30:00.000Z"}
         (command/capture->command request))))

(deftest keeps-the-original-occurred-at-instead-of-the-delivery-time
  (testing "Smriti accepts the ISO instant as an offset date-time"
    (is (= (:occurred-at request)
           (get (command/capture->command request) "created-at"))))
  (testing "a different capture time is carried through unchanged"
    (let [backdated (assoc request :occurred-at "2026-01-02T03:04:05.000Z")]
      (is (= "2026-01-02T03:04:05.000Z"
             (get (command/capture->command backdated) "created-at"))))))

(deftest trims-the-final-text
  (let [padded (assoc-in request [:content :transcript] "  Buy potatoes  ")]
    (is (= "Buy potatoes" (get (command/capture->command padded) "text")))))

(deftest a-capture-without-a-final-transcript-has-no-command
  (is (nil? (command/capture->command
             (assoc request :content {:type :audio
                                      :audio {:asset-id "local"}})))))
