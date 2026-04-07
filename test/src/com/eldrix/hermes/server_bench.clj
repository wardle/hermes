(ns com.eldrix.hermes.server-bench
  "HTTP server benchmarks using wrk.
  Starts an in-process Pedestal/Jetty server, shells out to wrk for each
  endpoint, and prints results.

  Run:  clj -X:dev:bench :patterns '[\".*server-bench$\"]'"
  (:require [clojure.java.process :as proc]
            [clojure.test :refer [deftest use-fixtures]]
            [com.eldrix.hermes.core :as hermes]))

(set! *warn-on-reflection* true)

(def ^:private port 8190)
(def ^:dynamic *svc* nil)
(def ^:dynamic *conn* nil)

(def ^:private server-available?
  (try
    (requiring-resolve 'com.eldrix.hermes.cmd.core/-main) ;; registers LocalDate JSON writer
    (requiring-resolve 'com.eldrix.hermes.cmd.server/start!)
    true
    (catch Exception _
      (println "Skipping HTTP server benchmarking; use 'clj -X:dev:bench' if required.")
      false)))

(defn live-server-fixture [f]
  (if-not server-available?
    (f)
    (let [start! (requiring-resolve 'com.eldrix.hermes.cmd.server/start!)
          stop!  (requiring-resolve 'com.eldrix.hermes.cmd.server/stop!)
          svc    (hermes/open "snomed.db" {:quiet true})]
      (try
        (let [conn (start! svc {:port port :join? false})]
          (try
            (binding [*svc* svc *conn* conn]
              (f))
            (finally
              (stop! conn))))
        (finally
          (hermes/close svc))))))

(use-fixtures :once live-server-fixture)

(defn- run-wrk
  "Run wrk and print its output."
  [url & {:keys [threads connections duration]
          :or   {threads 2 connections 10 duration "10s"}}]
  (println (proc/exec "wrk" (str "-t" threads) (str "-c" connections) (str "-d" duration) "--latency" url))
  (flush))

(def benchmarks
  [{:label "Concept lookup"        :path "/v1/snomed/concepts/22298006"}
   {:label "Extended concept"      :path "/v1/snomed/concepts/22298006/extended"}
   {:label "Concept descriptions"  :path "/v1/snomed/concepts/22298006/descriptions"}
   {:label "Preferred synonym"     :path "/v1/snomed/concepts/22298006/preferred"}
   {:label "Concept properties"    :path "/v1/snomed/concepts/22298006/properties"}
   {:label "Subsumption (T2DM<DM)" :path "/v1/snomed/concepts/44054006/subsumed-by/73211009"}
   {:label "Search: heart attack"  :path "/v1/snomed/search?s=heart+attack&maxHits=10"}
   {:label "Search: diabetes"      :path "/v1/snomed/search?s=diabetes+type+2&maxHits=10"}
   {:label "Search: asthma"        :path "/v1/snomed/search?s=asthma&maxHits=10"}
   {:label "ECL: <73211009"        :path "/v1/snomed/expand?ecl=%3C73211009"}
   {:label "Map to ICD-10"         :path "/v1/snomed/concepts/22298006/map/447562003"}
   {:label "Concept (50 conn)"     :path "/v1/snomed/concepts/22298006"       :threads 4 :connections 50}
   {:label "Search (50 conn)"      :path "/v1/snomed/search?s=heart+attack&maxHits=10" :threads 4 :connections 50}])

(deftest ^:benchmark bench-http
  (if-not server-available?
    (println "Skipping HTTP benchmarks (server deps not available)")
    (doseq [{:keys [label path] :as bench} benchmarks]
      (println (str "\n*** " label))
      (run-wrk (str "http://127.0.0.1:" port path) (dissoc bench :label :path)))))
