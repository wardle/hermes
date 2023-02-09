(ns com.eldrix.hermes.core-bench
  (:require [clojure.test :refer :all]
            [criterium.core :as crit :refer [with-progress-reporting quick-bench]]
            [com.eldrix.hermes.core :as hermes]))


(def ^:dynamic *svc* nil)

(defn live-test-fixture [f]
  (binding [*svc* (hermes/open "snomed.db")]
    (f)
    (hermes/close *svc*)))

(use-fixtures :once live-test-fixture)

(deftest ^:benchmark bench-make-extended-descriptions
  (let [lang-refset-ids ((.-localeMatchFn *svc*) "en-GB")]
    (println "\n*** Benchmarking search/make-extended-descriptions")
    (quick-bench
      (com.eldrix.hermes.impl.search/make-extended-descriptions (.-store *svc*) lang-refset-ids (hermes/get-concept *svc* 24700007)))))

(comment
  (run-tests)
  (def ^:dynamic *svc* (hermes/open "snomed.db"))
  (def lang-refset-ids ((.-localeMatchFn *svc*) "en-GB"))
  (crit/quick-bench (com.eldrix.hermes.impl.search/make-extended-descriptions (.-store *svc*) lang-refset-ids (hermes/get-concept *svc* 24700007))))

