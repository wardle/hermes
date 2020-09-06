(ns com.eldrix.hermes.import
  "Provides import functionality for processing directories of files")

(def ^:private snomed-files
  [#"sct2_Concept_Snapshot_\S+_\S+.txt"
   #"sct2_Description_Snapshot-\S+_\S+.txt"])

(defn is-snomed-file? [filename]
  (not-empty (filter #(re-find % filename) snomed-files)))

(defn snomed-file-seq
  "A tree sequence for SNOMED CT data files"
  [dir]
  (->> dir
       clojure.java.io/file
       file-seq
       (filter #(.isFile %))
       (filter #(is-snomed-file? (.getName %)))))

(defn -main [x]
  (let [ff (snomed-file-seq x)]
    (println "Found" (count ff) "files in" x)))



(comment
  (require '[clojure.reflect :as reflect])
  (clojure.pprint/pprint (reflect/reflect (java.util.Date.)))
  (def f (first (file-seq (clojure.java.io/file "."))))
  (reflect/reflect f)
  (def patterns (vals snomed-files))


  (is-snomed-file? "sct2_Concept_Snapshot_INT_20190731.txt")
  (re-find (:snomed/concept snomed-files) "sct2_Concept_Snapshot_INT_20190731.txt")
  )