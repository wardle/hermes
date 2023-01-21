(ns com.eldrix.hermes.download
  (:require [clojure.pprint]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.trud.core :as trud]
            [com.eldrix.trud.zip :as zip])
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
            release (when release-date' (first (filter #(.isEqual ^LocalDate release-date' (:releaseDate %)) (trud/get-releases trud-key item-identifier))))]
        (if-not release
          (do (when-not (= "list" release-date) (log/info "Release not found for date:" release-date' "Available releases:"))
              (clojure.pprint/print-table [:releaseDate :archiveFileName] (trud/get-releases trud-key item-identifier)))
          (assoc release :archiveFilePath (trud/download-release cache-dir release))))
      (trud/get-latest {:api-key   trud-key
                        :cache-dir cache-dir}
                       item-identifier))))

(def registry
  "A registry of download providers. "
  {"uk.nhs/sct-clinical" {:f    (fn [params] (:archiveFilePath (download-from-trud 101 params)))
                          :desc "UK clinical edition"
                          :spec ::uk-trud}
   "uk.nhs/sct-drug-ext" {:f    (fn [params] (:archiveFilePath (download-from-trud 105 params)))
                          :spec ::uk-trud
                          :desc "UK drug extension"}})

(defn print-providers
  "Placeholder for a more sophisticated future method of printing available
  providers."
  []
  (println "Available distributions for automated 'install':\n")
  (println (->> (keys registry)
                (map #(let [dist (registry %)] (str "  " (format "%-20s" %) " - " (:desc dist))))
                (str/join \newline))))

(defn download
  "Download the named distribution.
  Parameters:
  - nm         : name of the provider   e.g. \"uk.nhs/sct-clinical\"
  - parameters : a map of parameters

  The parameters will depend on the exact nature of the provider.
  Returns the java.nio.file.Path of the directory containing unzipped files."
  ^Path [nm params]
  (let [{:keys [f spec]} (get registry nm)
        list-releases? (= "list" (:release-date params))]
    (when-not f
      (throw (IllegalArgumentException. (str "Unknown provider: " nm))))
    (if-not list-releases?
      (log/info "Installing distribution" {:distribution nm :params params})
      (println "\nAvailable releases for distribution" nm ":"))
    (if-not (and spec (s/valid? spec params))
      (throw (ex-info (str "Invalid parameters for provider '" nm "'") (s/explain-data spec params)))
      (if-let [zipfile (f params)]
        (zip/unzip zipfile)
        (when-not list-releases? (log/warn "No files returned" {:provider nm}))))))

(comment

  (download "uk.nhs/sct-clinical" ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (download "uk.nhs/sct-drug-ext" ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (download "uk.nhs/sct-clinical" [])

  (def api-key (str/trim (slurp "../trud/api-key.txt")))
  (trud/get-releases api-key 101)


  (def params ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (def params2 ["api-key" "api-key.txt" "cache-dir=../trud/cache"])
  (apply hash-map params))



