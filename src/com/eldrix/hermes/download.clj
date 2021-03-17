(ns com.eldrix.hermes.download
  (:require [clojure.string :as str]
            [clojure.pprint :as pprint]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
            [com.eldrix.trud.core :as trud]
            [com.eldrix.trud.zip :as zip]
            [expound.alpha :as expound]))

(s/def ::api-key string?)
(s/def ::cache-dir string?)
(s/def ::uk-trud (s/keys :req-un [::api-key ::cache-dir]))

(defn download-from-trud
  "Download an item from TRUD
  Parameters:
  - item-identifier : item wanted, (e.g. 221)
  - config: a map containing:
    :api-key : path to file containing TRUD api-key (e.g.\"/var/hermes/api-key.txt\")
    :cache-dir : TRUD download cache directory (e.g. \"/tmp/trud\".
  Returns TRUD metadata about the item specified.
  See com.eldrix.trud.core/get-latest for information about return value."
  [item-identifier {:keys [api-key cache-dir]}]
  (let [trud-key (str/trim-newline (slurp api-key))]
    (trud/get-latest {:api-key   trud-key
                      :cache-dir cache-dir}
                     item-identifier)))

(def registry
  "A registry of download providers. "
  {"uk.nhs/sct-clinical" {:f    (fn [params] (:archiveFilePath (download-from-trud 101 params)))
                          :spec ::uk-trud}
   "uk.nhs/sct-drug-ext" {:f    (fn [params] (:archiveFilePath (download-from-trud 105 params)))
                          :spec ::uk-trud}})

(s/def ::provider-parameters (s/* (s/cat ::key string? ::value string?)))

(defn print-providers
  "Placeholder for a more sophisticated future method of printing available
  providers."
  []
  (clojure.pprint/print-table (map #(hash-map :identifier %) (keys registry))))

(defn ^java.nio.file.Path download
  "Download the named distribution.
  Parameters:
  - nm         : name of the provider   e.g. \"uk.nhs/sct-clinical\"
  - parameters : a sequence of key value pairs with optional parameters.

  The parameters will depend on the exact nature of the provider.
  Returns the java.nio.file.Path of the directory containing unzipped files."
  [nm parameters]
  (if-not (s/valid? ::provider-parameters parameters)
    (println "Parameters must be given as key value pairs. e.g. \"api-key key.txt" \" (expound/expound-str ::provider-parameters parameters))
    (let [{:keys [f spec]} (get registry nm)]
      (when-not f
        (throw (IllegalArgumentException. (str "Unknown provider: " nm))))
      (let [params (walk/keywordize-keys (apply hash-map parameters))]
        (println "Downloading using provider " nm " with params" params)
        (if-not (and spec (s/valid? spec params))
          (println "Invalid parameters for provider '" nm "':" (expound/expound-str spec params {:print-specs? false :theme :figwheel-theme}))
          (when-let [zipfile (f params)]
            (zip/unzip zipfile)))))))

(comment

  (download "uk.nhs/sct-clinical" ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (download "uk.nhs/sct-drug-ext" ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (download "uk.nhs/sct-clinical" [])

  )
