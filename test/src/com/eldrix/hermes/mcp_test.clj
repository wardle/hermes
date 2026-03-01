(ns com.eldrix.hermes.mcp-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.eldrix.hermes.cmd.core]
            [com.eldrix.hermes.cmd.mcp :as mcp]
            [com.eldrix.hermes.core :as hermes])
  (:import (java.time LocalDate)))

(deftest test-unknown-method
  (let [resp (mcp/dispatch nil {"jsonrpc" "2.0" "id" 4 "method" "no/such" "params" {}})]
    (is (= 4 (get resp "id")))
    (is (= -32601 (get-in resp ["error" "code"])))))

(deftest test-notification-returns-nil
  (is (nil? (mcp/dispatch nil {"jsonrpc" "2.0" "method" "notifications/initialized"}))))

(deftest test-local-date-json
  (is (= "\"2024-01-15\"" (json/write-str (LocalDate/of 2024 1 15)))))

(def ^:dynamic *svc* nil)

(defn live-test-fixture [f]
  (binding [*svc* (hermes/open "snomed.db")]
    (try (f) (finally (hermes/close *svc*)))))

(use-fixtures :once live-test-fixture)

(deftest ^:live test-tool-search
  (let [resp (mcp/dispatch *svc* {"jsonrpc" "2.0" "id" 10
                                  "method"  "tools/call"
                                  "params"  {"name" "search" "arguments" {"query" "multiple sclerosis"}}})
        content (get-in resp ["result" "content"])]
    (is (seq content))
    (is (= "text" (get (first content) "type")))))

(deftest ^:live test-tool-concept
  (let [resp (mcp/dispatch *svc* {"jsonrpc" "2.0" "id" 11
                                  "method"  "tools/call"
                                  "params"  {"name" "concept" "arguments" {"concept_id" 24700007}}})
        content (get-in resp ["result" "content"])
        text    (json/read-str (get (first content) "text"))]
    (is (= 24700007 (get text "id")))
    (is (contains? text "active"))
    (is (not (contains? text "descriptions")))))

(deftest ^:live test-tool-extended-concept
  (let [resp (mcp/dispatch *svc* {"jsonrpc" "2.0" "id" 11
                                  "method"  "tools/call"
                                  "params"  {"name" "extended_concept" "arguments" {"concept_id" 24700007}}})
        content (get-in resp ["result" "content"])
        text    (json/read-str (get (first content) "text"))]
    (is (= 24700007 (get-in text ["concept" "id"])))
    (is (contains? text "descriptions"))))

(deftest ^:live test-tool-extended-concept-batch
  (let [resp (mcp/dispatch *svc* {"jsonrpc" "2.0" "id" 11
                                  "method"  "tools/call"
                                  "params"  {"name" "extended_concept" "arguments" {"concept_id" [24700007 73211009]}}})
        content (get-in resp ["result" "content"])
        result  (json/read-str (get (first content) "text"))]
    (is (= 2 (count result)))))

(deftest ^:live test-tool-fully-specified-name
  (let [resp (mcp/dispatch *svc* {"jsonrpc" "2.0" "id" 13
                                  "method"  "tools/call"
                                  "params"  {"name" "fully_specified_name" "arguments" {"concept_id" 24700007}}})
        content (get-in resp ["result" "content"])
        text    (json/read-str (get (first content) "text"))]
    (is (str/includes? (get text "term") "(disorder)"))))

(deftest ^:live test-tool-fully-specified-name-batch
  (let [resp (mcp/dispatch *svc* {"jsonrpc" "2.0" "id" 14
                                  "method"  "tools/call"
                                  "params"  {"name" "fully_specified_name" "arguments" {"concept_id" [24700007 73211009]}}})
        content (get-in resp ["result" "content"])
        result  (json/read-str (get (first content) "text"))]
    (is (= 2 (count result)))))

(deftest ^:live test-tool-unknown
  (let [resp (mcp/dispatch *svc* {"jsonrpc" "2.0" "id" 12
                                  "method"  "tools/call"
                                  "params"  {"name" "no_such_tool" "arguments" {}}})]
    (is (true? (get-in resp ["result" "isError"])))))
