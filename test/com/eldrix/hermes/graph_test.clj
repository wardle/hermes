(ns com.eldrix.hermes.graph-test
  (:require [clojure.test :refer :all]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.graph :as graph]
            [com.eldrix.hermes.snomed :as snomed]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [clojure.spec.test.alpha :as stest]))

(stest/instrument)
(def ^:dynamic *registry* nil)

(defn live-test-fixture [f]
  (with-open [svc (hermes/open "snomed.db")]
    (binding [*registry* (-> (pci/register graph/all-resolvers)
                             (assoc ::graph/svc svc))]
      (f))))

(use-fixtures :once live-test-fixture)

(deftest ^:live test-get-concept
  (let [result (p.eql/process *registry*
                              {:info.snomed.Concept/id 80146002}
                              [:info.snomed.Concept/id
                               :info.snomed.Concept/active
                               {:>/en-GB ['(:info.snomed.Concept/preferredDescription {:accept-language "en-GB"})]}
                               {:>/en-US ['(:info.snomed.Concept/preferredDescription {:accept-language "en-US"})]}
                               {:info.snomed.Concept/descriptions
                                [:info.snomed.Description/active :info.snomed.Description/lowercaseTerm]}])]
    (is (= 80146002 (:info.snomed.Concept/id result)))
    (is (:info.snomed.Concept/active result))
    (is (= "Appendectomy" (get-in result [:>/en-US :info.snomed.Concept/preferredDescription :info.snomed.Description/term])))
    (is (= "Appendicectomy" (get-in result [:>/en-GB :info.snomed.Concept/preferredDescription :info.snomed.Description/term])))))

(deftest ^:live test-concept-relationships
  (let [result (p.eql/process *registry*
                              {:info.snomed.Concept/id 24700007}
                              [:info.snomed.Concept/id
                               {:>/all [:info.snomed.Concept/parentRelationshipIds
                                        :info.snomed.Concept/directParentRelationshipIds]}
                               {:>/is-a [(list :info.snomed.Concept/parentRelationshipIds {:type snomed/IsA})
                                         (list :info.snomed.Concept/directParentRelationshipIds {:type snomed/IsA})]}])]
    (is (= (get-in result [:>is-a :info.snomed.Concept/parentRelationshipIds snomed/IsA])
           (get-in result [:>all :info.snomed.Concept/parentRelationshipIds snomed/IsA])))))

(deftest ^:live test-search
  (let [result (p.eql/process *registry*
                              [{'(info.snomed.Search/search
                                   {:s          "mnd"
                                    :constraint "<404684003"
                                    :max-hits   1})
                                [:info.snomed.Concept/id
                                 :info.snomed.Description/id
                                 :info.snomed.Description/term
                                 {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
                                 :info.snomed.Concept/active]}])]
    (is (= 37340000 (get-in result ['info.snomed.Search/search 0 :info.snomed.Concept/id])))
    (is (= "Motor neuron disease" (get-in result ['info.snomed.Search/search 0 :info.snomed.Concept/preferredDescription :info.snomed.Description/term])))))

(deftest ^:live test-search-resolver
  (let [result (p.eql/process *registry*
                              ['({:info.snomed.Search/search [:info.snomed.Concept/id
                                                              :info.snomed.Description/id
                                                              :info.snomed.Description/term
                                                              {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
                                                              :info.snomed.Concept/active]}
                                 {:s          "mnd"
                                  :constraint "<404684003"
                                  :max-hits   1})])]

    (is (= 37340000 (get-in result [:info.snomed.Search/search 0 :info.snomed.Concept/id])))
    (is (= "Motor neuron disease" (get-in result [:info.snomed.Search/search 0 :info.snomed.Concept/preferredDescription :info.snomed.Description/term])))))

(deftest ^:live test-replaced-by
  (let [result (p.eql/process *registry*
                              {:info.snomed.Concept/id 203004}
                              [:info.snomed.Concept/id
                               {:info.snomed.Concept/module [{:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                               :info.snomed.Concept/active
                               {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term
                                                                           :info.snomed.Description/active]}
                               {:info.snomed.Concept/possiblyEquivalentTo [:info.snomed.Concept/id
                                                                           :info.snomed.Concept/active
                                                                           {:info.snomed.Concept/preferredDescription
                                                                            [:info.snomed.Description/term]}]}
                               {:info.snomed.Concept/sameAs [:info.snomed.Concept/id
                                                             :info.snomed.Concept/active
                                                             {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]}
                               {:info.snomed.Concept/replacedBy [:info.snomed.Concept/id
                                                                 :info.snomed.Concept/active
                                                                 {:info.snomed.Concept/preferredDescription
                                                                  [:info.snomed.Description/term]}]}])]
    (is result)))

(deftest ^:live test-ctv3-crossmap
  (when (contains? (hermes/get-installed-reference-sets (::graph/svc *registry*)) 900000000000497000)
    (let [ms (p.eql/process *registry*
                            {:info.read/ctv3 "F20.."}
                            [:info.snomed.Concept/id
                             {:info.snomed.Concept/preferredDescription [:info.snomed.Description/lowercaseTerm]}])]
      (is (= "multiple sclerosis" (get-in ms [:info.snomed.Concept/preferredDescription :info.snomed.Description/lowercaseTerm])))
      (is (= 24700007 (get-in ms [:info.snomed.Concept/id]))))))

(deftest ^:live test-refsets
  (let [svc (::graph/svc *registry*)
        refset-items (hermes/get-component-refset-items svc 24700007)
        refset-ids (hermes/get-component-refset-ids svc 24700007)
        ms (p.eql/process *registry*
                          {:info.snomed.Concept/id 24700007}
                          [:info.snomed.Concept/id
                           :info.snomed.Concept/refsetItems
                           :info.snomed.Concept/refsetIds])]
    (is (= (map #(update-keys % (comp keyword name)) (:info.snomed.Concept/refsetItems ms))
           (map #(update-keys % (comp keyword name)) refset-items)))
    (is (= (:info.snomed.Concept/refsetIds refset-ids)))))

(comment
  (def ^:dynamic *registry* (-> (pci/register graph/all-resolvers)
                                (assoc ::graph/svc (hermes/open "snomed.db"))))
  (run-tests))