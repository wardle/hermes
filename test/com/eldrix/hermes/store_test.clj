(ns com.eldrix.hermes.store-test
  (:require [clojure.set :as set]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]
            [com.eldrix.hermes.gen :as hgen]
            [com.eldrix.hermes.impl.language :as lang]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.rf2 :as rf2]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.time LocalDate)
           (java.io FileNotFoundException)))

(stest/instrument)

(deftest simple-store
  (testing "Throws exception if store doesn't exist and opened read-only"
    (is (thrown? Exception (store/open-store "store.db"))))
  (testing "Simple manual store and retrieval"
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
        (is (= description (store/get-description st 754365011)))
        (is (= description (store/get-fully-specified-name st 24700007)))))))

(deftest write-concept-test
  (with-open [st (store/open-store)]
    (is (nil? (store/get-concept st 24700007)))
    (let [concept (snomed/->Concept 24700007 (LocalDate/of 2020 11 11) true 1 0)]
      (store/write-batch {:type :info.snomed/Concept
                          :data [concept]} st)
      (is (= concept (store/get-concept st 24700007)))
      (let [older-concept (snomed/->Concept 24700007 (LocalDate/of 2020 10 01) true 0 0)]
        (store/write-batch {:type :info.snomed/Concept
                            :data [older-concept]} st)
        (is (not= older-concept (store/get-concept st 24700007)))
        (is (= concept (store/get-concept st 24700007))))
      (let [newer-concept (snomed/->Concept 24700007 (LocalDate/of 2021 01 01) true 0 0)]
        (store/write-batch {:type :info.snomed/Concept
                            :data [newer-concept]} st)
        (is (= newer-concept (store/get-concept st 24700007)))))))

(deftest write-components-test
  (with-open [st (store/open-store)]
    (let [{:keys [root-concept concepts descriptions relationships]} (hgen/make-simple-hierarchy)
          descriptions-by-concept-id (reduce (fn [acc v] (update acc (:conceptId v) conj v)) {} descriptions)]
      (store/write-batch {:type :info.snomed/Concept :data concepts} st)
      (store/write-batch {:type :info.snomed/Description :data descriptions} st)
      (store/write-batch {:type :info.snomed/Relationship :data relationships} st)
      (store/index st)
      (testing "Concept read/write"
        (is (every? true? (map #(= % (store/get-concept st (:id %))) concepts))))
      (testing "Concept descriptions"
        (is (every? true? (map #(= % (store/get-description st (:id %))) descriptions)))
        (is (every? true? (map #(= (set (get descriptions-by-concept-id (:id %))) (set (store/get-concept-descriptions st (:id %)))) concepts))))
      (testing "Concept relationships"
        (is (every? true? (map #(= % (store/get-relationship st (:id %))) relationships)))
        (is (every? true? (map #(store/is-a? st % (:id root-concept)) concepts)))
        (is (= (set (map :id concepts)) (store/get-all-children st (:id root-concept))))))))

(comment
  (def st (store/open-store))
  (def rel-1 (gen/generate (rf2/gen-relationship {:sourceId 24700007 :destinationId 6118003 :typeId 116680003 :active false :effectiveTime (java.time.LocalDate/of 2020 1 1)})))
  (def rel-2 (gen/generate (rf2/gen-relationship {:sourceId 24700007 :destinationId 6118003 :typeId 116680003 :active true :effectiveTime (java.time.LocalDate/of 2020 1 1)})))
  (store/write-batch {:type :info.snomed/Relationship :data [rel-2 rel-1]} st)
  (store/get-parent-relationships st 24700007))

(deftest write-relationships
  ;; 3229461000000123	20210512	1	999000011000000103	1089261000000101	213345000	0	116680003	900000000000011006	900000000000451002
  ;; 5687171000000128	20210512	0	999000011000000103	1089261000000101	213345000	0	116680003	900000000000011006	900000000000451002
  (let [r1 (gen/generate (rf2/gen-relationship {:sourceId 1089261000000101 :destinationId 213345000 :typeId 116680003 :active false :effectiveTime (java.time.LocalDate/of 2021 5 12)}))
        r2 (gen/generate (rf2/gen-relationship {:sourceId 1089261000000101 :destinationId 213345000 :typeId 116680003 :active true :effectiveTime (java.time.LocalDate/of 2021 5 12)}))]
    (with-open [st (store/open-store)]
      (store/write-batch {:type :info.snomed/Relationship :data [r1 r2]} st)
      (store/index st)
      (is (= {116680003 #{213345000}} (store/get-parent-relationships st 1089261000000101))))
    (with-open [st (store/open-store)]
      (store/write-batch {:type :info.snomed/Relationship :data [r2 r1]} st)
      (store/index st)
      (is (= {116680003 #{213345000}} (store/get-parent-relationships st 1089261000000101))
          "Different relationships with same source, target and type identifiers should result in indices deterministically, not on basis of import order"))))


(deftest write-simple-refsets-test
  (with-open [st (store/open-store)]
    (let [n-concepts (rand-int 10000)
          [refset & concepts] (gen/sample (rf2/gen-concept) n-concepts)
          refset-id (:id refset)
          members (set (take (/ n-concepts (inc (rand-int 10))) (shuffle concepts)))
          non-members (set/difference (set concepts) members)
          refset-items (map #(gen/generate (rf2/gen-simple-refset {:refsetId refset-id :active true :referencedComponentId (:id %)})) members)]
      (store/write-batch {:type :info.snomed/Concept :data [refset]} st)
      (store/write-batch {:type :info.snomed/Concept :data concepts} st)
      (store/write-batch {:type :info.snomed/SimpleRefset :data refset-items} st)
      (store/index st)
      (is (= #{refset-id} (store/get-installed-reference-sets st)))
      (dorun (map #(is (= % (store/get-refset-item st (:id %)))) refset-items))
      (is (every? true? (map #(= #{refset-id} (store/get-component-refset-ids st (:id %))) members)))
      (is (every? true? (map #(empty? (store/get-component-refset-ids st (:id %))) non-members)))
      (is (every? true? (map #(let [[item & more] (store/get-component-refset-items st (.-referencedComponentId %))]
                                (and (nil? more) (= item %))) refset-items)))
      (is (every? true? (map #(= % (store/get-refset-item st (.-id %))) refset-items)))
      (let [status (store/status st)]
        (is (= (:concepts status) n-concepts))
        (is (= 0 (:descriptions status)))
        (is (= (count refset-items) (:refset-items status)))
        (is (= 1 (:refsets status)))
        (is (= (count refset-items) (get-in status [:indices :component-refsets])))))))

(deftest write-refsets
  (with-open [store (store/open-store)]
    (let [simple (gen/sample (rf2/gen-simple-refset))
          refset-descriptors (gen/sample (rf2/gen-refset-descriptor-refset))]
      (store/write-batch {:type :info.snomed/SimpleRefset
                          :data simple} store)
      (store/write-batch {:type :info.snomed/RefsetDescriptorRefset
                          :data refset-descriptors} store)
      (dorun (map #(is (= % (store/get-refset-item store (:id %)))) (concat simple refset-descriptors))))))

(deftest test-refset-descriptors
  (let [refset-concept (gen/generate (rf2/gen-concept {:id 1322291000000109 :active true}))
        rd1 (gen/generate (rf2/gen-refset-descriptor-refset {:refsetId 900000000000456007 :referencedComponentId 1322291000000109 :active true :attributeOrder 0 :attributeDescriptionId 449608002}))
        rd2 (gen/generate (rf2/gen-refset-descriptor-refset {:refsetId 900000000000456007 :referencedComponentId 1322291000000109 :active true :attributeOrder 1 :attributeDescriptionId 900000000000533001}))]
    (with-open [store (store/open-store)]
      (store/write-batch {:type :info.snomed/Concept :data [refset-concept]} store)
      (store/write-batch {:type :info.snomed/RefsetDescriptorRefset :data [rd1 rd2]} store)
      (store/index store)
      (is (= rd1 (store/get-refset-item store (:id rd1))))
      (is (= rd2 (store/get-refset-item store (:id rd2))))
      (is (= (list rd1 rd2) (store/get-refset-descriptors store 1322291000000109)))
      (is (= (list 449608002 900000000000533001) (store/get-refset-descriptor-attribute-ids store 1322291000000109))))))

(deftest ^:live live-store
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
        (is (not (store/is-a? store 24700007 95320005))))))) ;; it's not a disorder of the skin

(deftest ^:live test-localisation
  (with-open [store (store/open-store "snomed.db/store.db")]
    (let [gb (store/get-preferred-synonym store 80146002 [999000691000001104 900000000000508004 999001261000000100])
          usa (store/get-preferred-synonym store 80146002 [900000000000509007])]
      (is (= "Appendicectomy" (:term gb)))
      (is (= "Appendectomy" (:term usa))))
    (let [lang-match-fn (lang/match-fn store)]
      (is (= "Appendicectomy" (:term (store/get-preferred-synonym store 80146002 (lang-match-fn "en-GB")))))
      (is (= "Appendectomy" (:term (store/get-preferred-synonym store 80146002 (lang-match-fn "en-US"))))))))

(comment
  (run-tests)
  (write-components-test)
  (live-store))
