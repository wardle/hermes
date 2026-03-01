(ns com.eldrix.hermes.mcp-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [com.eldrix.hermes.cmd.core]
            [com.eldrix.hermes.cmd.mcp :as mcp])
  (:import (java.time LocalDate)))

(deftest test-unknown-method
  (let [resp (mcp/dispatch nil {"jsonrpc" "2.0" "id" 4 "method" "no/such" "params" {}})]
    (is (= 4 (get resp "id")))
    (is (= -32601 (get-in resp ["error" "code"])))))

(deftest test-notification-returns-nil
  (is (nil? (mcp/dispatch nil {"jsonrpc" "2.0" "method" "notifications/initialized"}))))

(deftest test-local-date-json
  (is (= "\"2024-01-15\"" (json/write-str (LocalDate/of 2024 1 15)))))
