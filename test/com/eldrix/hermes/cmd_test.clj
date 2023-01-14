(ns com.eldrix.hermes.cmd-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [com.eldrix.hermes.cmd.cli :as cli]))



(def cli-tests
  [{:s    "Testing legacy download command line arguments"
    :args ["--db" "snomed.db" "download" "uk.nhs/sct-clinical" "api-key" "api-key.txt" "cache-dir" "/var/tmp"]
    :test (fn [parsed]
            (is (= (get-in parsed [:options :db]) "snomed.db"))
            (is (= (get-in parsed [:options :dist]) ["uk.nhs/sct-clinical"]))
            (is (= (get-in parsed [:options :api-key]) "api-key.txt"))
            (is (= (get-in parsed [:options :cache-dir]) "/var/tmp"))
            (is (nil? (:errors parsed)))
            (is (seq (:warnings parsed))))}
   {:s    "Testing legacy download with multiple distributions"
    :args ["--db" "snomed.db" "download" "uk.nhs/sct-clinical" "uk.nhs/sct-drug-ext" "api-key" "api-key.txt" "cache-dir" "/var/tmp"]
    :test (fn [parsed]
            (is (= (get-in parsed [:options :db]) "snomed.db"))
            (is (= (get-in parsed [:options :dist]) ["uk.nhs/sct-clinical" "uk.nhs/sct-drug-ext"]))
            (is (= (get-in parsed [:options :api-key]) "api-key.txt"))
            (is (= (get-in parsed [:options :cache-dir]) "/var/tmp"))
            (is (nil? (:errors parsed)))
            (is (seq (:warnings parsed))))}
   {:s    "Testing install with missing database"
    :args ["install" "--dist" "uk.nhs/sct-clinical" "--dist" "uk.nhs/sct-drug-ext"]
    :test (fn [parsed]
            (is (:errors parsed)))}
   {:s    "Testing install with database"
    :args ["--db" "snomed.db" "install" "--dist" "uk.nhs/sct-clinical" "--api-key=api-key.txt"]
    :test (fn [parsed]
            (is (= (get-in parsed [:options :db]) "snomed.db"))
            (is (= (get-in parsed [:options :api-key])) "api-key.txt")
            (is (nil? (:errors parsed))))}
   {:s    "Testing import"
    :args (str/split "--db snomed.db import /Downloads/snomed-2021/" #" ")
    :test (fn [{:keys [cmd options arguments]}]
            (is (= cmd "import"))
            (is (= (:db options) "snomed.db"))
            (is (= arguments ["/Downloads/snomed-2021/"])))}
   {:s    "Testing compact"
    :args (str/split "--db snomed.db compact" #" ")
    :test (fn [{:keys [cmd options]}]
            (is (= cmd "compact"))
            (is (= (:db options) "snomed.db")))}
   {:s    "Testing indexing"
    :args ["--db" "snomed.db" "index"]
    :test (fn [{:keys [cmd options]}]
            (is (= cmd "index"))
            (is (= (:db options) "snomed.db"))
            (is (not (:locale options))))}
   {:s    "Testing indexing with locale"
    :args ["index" "--db=snomed.db" "--locale" "en-GB,en-US"]
    :test (fn [{:keys [cmd options]}]
            (is (= cmd "index"))
            (is (= (:db options) "snomed.db"))
            (is (= (:locale options) "en-GB,en-US")))}
   {:s "Test status with missing database"
    :args ["status"]
    :test (fn [{:keys [errors]}]
            (is (seq errors)))}
   {:s "Test status "
    :args ["status" "--db" "snomed.db"]
    :test (fn [{:keys [cmd options errors]}]
            (is (nil? errors))
            (is (= cmd "status"))
            (is (= (:db options) "snomed.db")))}
   {:s "Run a server"
    :args (str/split "--db snomed.db --port 8090 serve" #" ")
    :test (fn [{:keys [cmd options errors]}]
            (is (nil? errors))
            (is (= "serve" cmd))
            (is (= (:db options) "snomed.db"))
            (is (= (:port options) 8090)))}])

(deftest test-parse-cli-options
  (doseq [{:keys [s args test]} cli-tests]
    (testing s
      (test (cli/parse-cli args)))))



(comment
  (cli/parse-cli ["download" "uk.nhs/sct-clinical" "api-key" "api-key.txt" "cache-dir" "/var/tmp"]))