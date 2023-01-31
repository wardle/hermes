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
   {:s    "Test install with release date"
    :args ["--db snomed.db" "install" "uk.nhs/sct-clinical" "--api-key=api-key.txt" "--release-date" "2023-01-01"]
    :test (fn [parsed]
            (is (= (get-in parsed [:options :release-date]) "2023-01-01")))}
   {:s    "Testing import"
    :args (str/split "--db snomed.db import /Downloads/snomed-2021/" #" ")
    :test (fn [{:keys [cmds options arguments]}]
            (is (= cmds ["import"]))
            (is (= (:db options) "snomed.db"))
            (is (= arguments ["/Downloads/snomed-2021/"])))}
   {:s    "Testing compact"
    :args (str/split "--db snomed.db compact" #" ")
    :test (fn [{:keys [cmds options]}]
            (is (= cmds ["compact"]))
            (is (= (:db options) "snomed.db")))}
   {:s    "Testing indexing"
    :args ["--db" "snomed.db" "index"]
    :test (fn [{:keys [cmds options]}]
            (is (= cmds ["index"]))
            (is (= (:db options) "snomed.db"))
            (is (not (:locale options))))}
   {:s    "Testing indexing with locale"
    :args ["index" "--db=snomed.db" "--locale" "en-GB,en-US"]
    :test (fn [{:keys [cmds options]}]
            (is (= cmds ["index"]))
            (is (= (:db options) "snomed.db"))
            (is (= (:locale options) "en-GB,en-US")))}
   {:s    "Test status with missing database"
    :args ["status"]
    :test (fn [{:keys [errors]}]
            (is (seq errors)))}
   {:s    "Test status "
    :args ["status" "--db" "snomed.db"]
    :test (fn [{:keys [cmds options errors]}]
            (is (nil? errors))
            (is (= cmds ["status"]))
            (is (= (:db options) "snomed.db")))}
   {:s    "Run a server"
    :args (str/split "--db snomed.db --port 8090 serve" #" ")
    :test (fn [{:keys [cmds options errors]}]
            (is (nil? errors))
            (is (= cmds ["serve"]))
            (is (= (:db options) "snomed.db"))
            (is (= (:port options) 8090)))}
   {:s    "Avoid extraneous options being included"
    :args ["serve"]
    :test (fn [{:keys [options]}]
            (is (not (contains? options :dist)) "parse-cli mistakenly included :dist options key when not provided"))}
   {:s "Test multiple commands"
    :args ["install" "--db=snomed.db" "uk.nhs/sct-clinical" "index" "compact" "serve" "--api-key=api-key.txt" "--port" "8090"]
    :test (fn [{:keys [options errors cmds]}]
            (is (= cmds ["install" "index" "compact" "serve"]))
            (is (nil? errors))
            (is (= 8090 (:port options))))}])

(deftest test-parse-cli-options
  (doseq [{:keys [s args test]} cli-tests]
    (testing s
      (test (cli/parse-cli args)))))

(deftest test-allowed-origins
  (is (= (cli/parse-cli ["serve" "--db=snomed.db" "--allowed-origins" "example.com,example.net"])
         (cli/parse-cli ["serve" "--db=snomed.db" "--allowed-origin" "example.com" "--allowed-origin" "example.net"])
         (cli/parse-cli ["serve" "--db=snomed.db" "--allowed-origins=example.com,example.net"])
         (cli/parse-cli ["serve" "--db=snomed.db" "--allowed-origin=example.com" "--allowed-origin=example.net"]))
      "--allowed-origin and --allowed-origins are not parsed to be be equivalent"))

(comment
  (cli/parse-cli ["download" "uk.nhs/sct-clinical" "api-key" "api-key.txt" "cache-dir" "/var/tmp"]))