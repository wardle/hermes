(ns com.eldrix.hermes.expression.ecl-test
  (:require [clojure.test :refer :all]
            [com.eldrix.hermes.expression.ecl :as ecl]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.impl.search :as search]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.io File)))

(defonce svc (atom {}))

(defn live-test-fixture [f]
  (if-not (and (.exists (File. "snomed.db/store.db"))
               (.exists (File. "snomed.db/search.db")))
    (log/warn "skipping live tests... no live store/search services found")
    (let [store (store/open-store "snomed.db/store.db")
          index-reader (search/open-index-reader "snomed.db/search.db")
          searcher (org.apache.lucene.search.IndexSearcher. index-reader)]
      (reset! svc {:store store :searcher searcher})
      (f))))

(use-fixtures :once live-test-fixture)

(def simple-tests
  [{:ecl "404684003 |Clinical finding|"
    :f   (fn [concept-ids]
           (is (= concept-ids #{404684003})))}
   {:ecl "<  24700007"
    :f   (fn [concept-ids]
           (is (not (contains? concept-ids 24700007)) "descendant of should not include concept itself")
           (is (contains? concept-ids 426373005) "relapsing remitting MS (426373005) should be a type of MS (24700007)"))}
   {:ecl "<<  73211009 |Diabetes mellitus|"
    :f   (fn [concept-ids]
           (is (contains? concept-ids 73211009) "descendent-or-self-of should include concept itself")
           (is (contains? concept-ids 46635009) "type 1 diabetes mellitus (46635009) should be a type of diabetes mellitus (73211009)"))}
   {:ecl "<!  404684003 |Clinical finding|"
    :f   (fn [concept-ids]
           (is (not (contains? concept-ids 404684003)) "'child of' should not include concept itself")
           (is (contains? concept-ids 64572001) "'child of' clinical finding should include 'disease (64572001)")
           (is (not (contains? concept-ids 24700007)) "'child' of' should include only proximal relationships"))}
   {:ecl "    >  40541001 |Acute pulmonary edema|"
    :f   (fn [concept-ids]
           (is (not (contains? concept-ids 40541001)) "'ancestor of' should not include concept itself")
           (is (contains? concept-ids 19829001) "ancestors of acute pulmonary oedema should include 'disorder of lung' (19829001)"))}
   {:ecl "    >!  40541001 |Acute pulmonary edema|"
    :f   (fn [concept-ids]
           (is (not (contains? concept-ids 40541001)) "'parent of' should not include concept itself")
           (is (contains? concept-ids 19242006) "pulmonary oedema should be a proximal parent of acute pulmonary oedema")
           (is (not (contains? concept-ids 19829001)) "(proximal) parent of acute pulmonary oedema should not include 'disorder of lung' (19829001)"))}
   {:ecl "24700007 |Multiple sclerosis| AND ^  723264001 |Lateralizable body structure reference set|"
    :f   (fn [concept-ids]
           (is (empty? concept-ids) "multiple sclerosis should not be in lateralizable body structure refset"))}
   {:ecl "(24136001 |Hip joint| OR 53120007|Arm|) AND ^  723264001 |Lateralizable body structure reference set|"
    :f   (fn [concept-ids]
           (is (= concept-ids #{24136001 53120007}) "both 'hip joint' and 'arm' should be in lateralizable body structure refset"))}
   {:ecl "<  19829001 |Disorder of lung| :\n         116676008 |Associated morphology|  =  79654002 |Edema|"
    :f   (fn [concept-ids]
           (let [morphologies (->> concept-ids
                                   (map #(store/get-parent-relationships-of-type (:store @svc) % snomed/AssociatedMorphology))
                                   (map set))]
             (is (every? true? (map #(contains? % 79654002) morphologies))) ;; all must have EXACTLY oedema as morphology
             (is (every? false? (map #(contains? % 85628007) morphologies)))))} ;; and *not* a subtype of oedema such as chronic oedema
   {:ecl "<  19829001 |Disorder of lung| :\n         116676008 |Associated morphology|  = <<  79654002 |Edema|"
    :f   (fn [concept-ids]
           (is (contains? concept-ids 40541001)))}          ;; acute pulmonary oedema has morphology 'acute oedema' and should be included via this expression
   {:ecl "<  404684003 |Clinical finding| :\n         363698007 |Finding site|  = <<  39057004 |Pulmonary valve structure| , \n         116676008 |Associated morphology|  = <<  415582006 |Stenosis|"
    :f (fn [concept-ids]
         (doseq [concept-id concept-ids]
           (let [ec (store/make-extended-concept (:store @svc) (store/get-concept (:store @svc) concept-id))]
             (is (store/is-a? nil ec 404684003))                  ;; are all a clinical finding?
             (is (store/has-property? nil ec 363698007 39057004))   ;; are all affecting the pulmonary value?
             (is (store/has-property? nil ec 116676008 415582006)))))}    ;; are all a stenosis?
   ])

;; 85628007

(deftest do-simple-tests
  (doseq [t simple-tests]
    (let [p (ecl/parse (:store @svc) (:searcher @svc) (:ecl t))
          results (ecl/realise-concept-ids @svc p)
          f (:f t)]
      (when f (f results)))))

(comment
  (run-tests))