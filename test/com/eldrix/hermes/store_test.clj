(ns com.eldrix.hermes.store-test
  (:require [clojure.test :refer :all]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.impl.language :as lang]
            [clojure.tools.logging.readable :as log])
  (:import (java.time LocalDate)
           (java.io File)))

(deftest simple-store
  (with-open [st (store/open-store)]
    (let [concept (snomed/->Concept 24700007 (LocalDate/of 2020 11 11) true 0 0)]
      (store/write-batch {:type :info.snomed/Concept
                          :data [concept]} st)
      (is (= concept (store/get-concept st 24700007))))
    (let [description (snomed/map->Description {:id                 754365011,
                                                :effectiveTime      (LocalDate/of 2020 11 11)
                                                :active             true,
                                                :moduleId           900000000000207008,
                                                :conceptId          24700007,
                                                :languageCode       "en",
                                                :typeId             900000000000003001,
                                                :term               "Multiple sclerosis (disorder)",
                                                :caseSignificanceId 900000000000448009})]
      (store/write-batch {:type :info.snomed/Description
                          :data [description]} st)
      (store/build-description-index st)
      (is (= description (store/get-description st 754365011)))
      (is (= description (store/get-fully-specified-name st 24700007))))))

(defn has-live-database? []
  (.exists (File. "snomed.db/store.db")))

(deftest live-store
  (with-open [store (store/open-store "snomed.db/store.db")]
    (testing "Multiple sclerosis"
      (let [ms (store/get-concept store 24700007)]
        (is (= 24700007 (:id ms)))
        (let [fsn (store/get-fully-specified-name store 24700007)]
          (is (= 24700007 (:conceptId fsn)))
          (is (= "Multiple sclerosis (disorder)" (:term fsn)))
          (is (:active fsn))
          (is (snomed/is-fully-specified-name? fsn)))
        (let [all-parents (store/get-all-parents store 24700007)]
          (is (contains? all-parents 6118003))              ;; it's a demyelinating disease
          (is (contains? all-parents 138875005)))           ;; its a SNOMED CT concept
        (is (store/is-a? store 24700007 6118003))
        (is (store/is-a? store 24700007 138875005))
        (is (store/is-a? store 24700007 24700007))
        (is (not (store/is-a? store 24700007 95320005)))    ;; it's not a disorder of the skin
        ))))

(deftest test-localisation
  (with-open [store (store/open-store "snomed.db/store.db")]
    (let [gb (store/get-preferred-synonym store 80146002 [999000691000001104 900000000000508004 999001261000000100])
          usa (store/get-preferred-synonym store 80146002 [900000000000509007])]
      (is (= "Appendicectomy" (:term gb)))
      (is (= "Appendectomy" (:term usa))))
    (let [lang-match-fn (lang/match-fn store)]
    (is (= "Appendicectomy" (:term (store/get-preferred-synonym store 80146002 (lang-match-fn "en-GB")))))
    (is (= "Appendectomy" (:term (store/get-preferred-synonym store 80146002 (lang-match-fn "en-US"))))))))

(defn test-ns-hook []
  (simple-store)
  (if-not (has-live-database?)
    (log/warn "Skipping live tests as no live database 'snomed.db' found.")
    (do (live-store)
        (test-localisation))))


(comment
  (has-live-database?)
  (run-tests)
  )