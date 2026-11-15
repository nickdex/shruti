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

(deftest appends-the-fixed-command-path-to-an-entered-host
  (testing "the setup screen collects a host; the path is fixed"
    (is (= "https://smriti.example.com/api/command"
           (command/endpoint-url "https://smriti.example.com")))
    (is (= "https://smriti.example.com/api/command"
           (command/endpoint-url "https://smriti.example.com/")))
    (is (= "http://10.0.2.2:8080/api/command"
           (command/endpoint-url "http://10.0.2.2:8080"))))
  (testing "an already complete endpoint is not doubled"
    (is (= "https://smriti.example.com/api/command"
           (command/endpoint-url "https://smriti.example.com/api/command")))
    (is (= "https://smriti.example.com/api/command"
           (command/endpoint-url "  https://smriti.example.com/api/command/  "))))
  (testing "a blank entry stays blank so setup can reject it"
    (is (= "" (command/endpoint-url "")))
    (is (= "" (command/endpoint-url nil)))))

(deftest shows-the-host-without-the-fixed-path
  (is (= "https://smriti.example.com"
         (command/base-url "https://smriti.example.com/api/command")))
  (is (= "http://localhost:8080"
         (command/base-url "http://localhost:8080/api/command")))
  (is (= "https://smriti.example.com"
         (command/base-url "https://smriti.example.com")))
  (is (= "" (command/base-url ""))))
