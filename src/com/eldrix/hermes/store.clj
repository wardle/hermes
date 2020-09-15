(ns com.eldrix.hermes.store
  "Store provides access to a key value store."
  (:require [clojure.java.io :as io]
            [com.eldrix.hermes.snomed :as snomed])
  (:import [java.io FileNotFoundException]
           [com.eldrix.hermes.snomed Concept]
           [java.nio.channels OverlappingFileLockException]))

(defn ^org.mapdb.DB open-database
  "Open a file-based key-value database from the file specified, optionally read only. Use in a with-open
  block or manually .close when done"
  [filename & {:keys [read-only?]}]
  (when (and read-only? (not (.exists (io/as-file filename))))
    (throw (FileNotFoundException. (str "file `" filename "` opened read-only but not found"))))
  (.make (cond-> (org.mapdb.DBMaker/fileDB ^String filename)
                 true (.fileMmapEnable)
                 read-only? (.readOnly))))

(defn ^org.mapdb.HTreeMap open-hashmap
  "Create or open the named hashmap with the specified key and value serializers."
  [^org.mapdb.DB db ^String nm ^org.mapdb.Serializer key-serializer ^org.mapdb.Serializer value-serializer]
  (.createOrOpen (.hashMap db nm key-serializer value-serializer)))

(defn ^org.mapdb.BTreeMap open-btreemap
  "Create or open the named b-tree map with the specified key and value serializers."
  [^org.mapdb.DB db ^String nm ^org.mapdb.Serializer key-serializer ^org.mapdb.Serializer value-serializer]
  (.createOrOpen (.treeMap db nm key-serializer value-serializer)))


(comment
  (set! *warn-on-reflection* true)
  (use 'clojure.repl)
  (require '[clojure.java.javadoc :as javadoc])
  (def db (open-database "snomed.db"))
  (def ^org.mapdb.BTreeMap bt (open-btreemap db "ent" org.mapdb.Serializer/LONG org.mapdb.Serializer/JAVA))
  (def hm (open-hashmap db "concept" org.mapdb.Serializer/LONG org.mapdb.Serializer/JAVA))
  (def ms (snomed/->Concept 24700002 (snomed/parse-date "20020101") true 0 0))
  (snomed/validate ms)

  (.put concepts 24700007 ms)
  (.get concepts 24700007)
  (.put hm 24700007 ms)
  (.get hm 24700007)
  (.get hm 41398015)
  (.get hm 1223979019)

  ;; turn a sequence of maps into a map keyed by :id of each map entry
  (def m (let [batch (clojure.core.async/<!! results-c) ids (map :id batch)] (zipmap ids batch)))
  (.putAll ^org.mapdb.BTreeMap bt m)

  (.get bt 107012)

  (doseq [o batch] (.put hm (:id o) o))
  (.get hm 126815003)
  ()
  (.close db)

  )




