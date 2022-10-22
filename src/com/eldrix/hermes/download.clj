(ns com.eldrix.hermes.download
  (:require [clojure.pprint]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]

            [clojure.walk :as walk]
            [com.eldrix.trud.core :as trud]
            [com.eldrix.trud.zip :as zip]
            [expound.alpha :as expound])
  (:import (java.nio.file Path)
           (java.time LocalDate)
           (java.time.format DateTimeParseException)))

(s/def ::api-key string?)
(s/def ::cache-dir string?)
(s/def ::release-date string?)
(s/def ::uk-trud (s/keys :req-un [::api-key ::cache-dir]
                         :opt-un [::release-date]))

(defn download-from-trud
  "Download an item from TRUD
  Parameters:
  - item-identifier : item wanted, (e.g. 101)
  - config: a map containing:
    :api-key : path to file containing TRUD api-key (e.g.\"/var/hermes/api-key.txt\")
    :cache-dir : TRUD download cache directory (e.g. \"/tmp/trud\")
    :release-date : (optional) an ISO 8601 date e.g. \"2022-02-03\"
  Returns TRUD metadata about the item specified.
  See com.eldrix.trud.core/get-latest for information about return value."
  [item-identifier {:keys [api-key cache-dir release-date]}]
  (let [trud-key (str/trim-newline (slurp api-key))]
    (if release-date
      (let [release-date' (try (LocalDate/parse release-date) (catch DateTimeParseException _))
            release (when release-date' (first (filter #(.isEqual release-date' (:releaseDate %)) (trud/get-releases trud-key item-identifier))))]
        (if-not release
          (do (when-not (= "list" release-date) (log/info "Release not found for date:" release-date' "Available releases:"))
              (dorun (map #(log/info "Release: " (:releaseDate %)) (trud/get-releases trud-key item-identifier))))
          (assoc release :archiveFilePath (trud/download-release cache-dir release))))
      (trud/get-latest {:api-key   trud-key
                        :cache-dir cache-dir}
                       item-identifier))))

(def registry
  "A registry of download providers. "
  {"uk.nhs/sct-clinical" {:f    (fn [params] (:archiveFilePath (download-from-trud 101 params)))
                          :spec ::uk-trud}
   "uk.nhs/sct-drug-ext" {:f    (fn [params] (:archiveFilePath (download-from-trud 105 params)))
                          :spec ::uk-trud}})

(s/def ::provider-parameters (s/or :items (s/coll-of #(re-matches #".*\=.*" %))
                                   :pairs (s/* (s/cat ::key string? ::value string?))))

(defn parse-provider-parameters
  "Parse a sequence of string arguments returning a map of keys to values.
  Arguments can be provided as alternating key value pairs, or as strings of
  the format key = value.

  The following are equivalent:
  ```
  (parse-provider-parameters [\"api-key\" \"../trud/api-key.txt\"])
  (parse-provider-parameters [\"api-key=../trud/api-key.txt\"])
   => {:api-key \"../trud/api-key.txt\"}
  ```"
  [args]
  (let [[mode params] (s/conform ::provider-parameters args)]
    (->> (case mode :pairs (reduce (fn [acc {::keys [key value]}] (assoc acc key value)) {} params)
                    :items (apply hash-map (mapcat #(str/split % #"=") params)))
         (walk/keywordize-keys))))

(defn print-providers
  "Placeholder for a more sophisticated future method of printing available
  providers."
  []
  (clojure.pprint/print-table (map #(hash-map :identifier %) (keys registry))))

(defn download
  "Download the named distribution.
  Parameters:
  - nm         : name of the provider   e.g. \"uk.nhs/sct-clinical\"
  - parameters : a sequence of strings representing alternating key value pairs,
                 or strings of the format key=value

  The parameters will depend on the exact nature of the provider.
  Returns the java.nio.file.Path of the directory containing unzipped files."
  ^Path [nm parameters]
  (if-not (s/valid? ::provider-parameters parameters)
    (println "Parameters must be given as key value pairs. e.g. \"api-key key.txt\" or \"api-key=key.txt\"" (expound/expound-str ::provider-parameters parameters))
    (let [{:keys [f spec]} (get registry nm)]
      (when-not f
        (throw (IllegalArgumentException. (str "Unknown provider: " nm))))
      (let [params (parse-provider-parameters parameters)]
        (println "Installing using provider" nm "with params" params)
        (if-not (and spec (s/valid? spec params))
          (println "Invalid parameters for provider '" nm "':\n" (expound/expound-str spec params {:print-specs? false :theme :figwheel-theme}))
          (if-let [zipfile (f params)]
            (zip/unzip zipfile)
            (log/warn "No files returned" {:provider nm})))))))

(comment

  (download "uk.nhs/sct-clinical" ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (download "uk.nhs/sct-drug-ext" ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (download "uk.nhs/sct-clinical" [])

  (def api-key (str/trim (slurp "../trud/api-key.txt")))
  (trud/get-releases api-key 101)


  (def params ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (def params2 ["api-key" "api-key.txt" "cache-dir=../trud/cache"])
  (apply hash-map params))



