(ns com.eldrix.hermes.graph-test
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is run-tests testing use-fixtures]]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.graph :as graph]
            [com.eldrix.hermes.snomed :as snomed]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.interface.eql :as p.eql]))

(stest/instrument)
(def ^:dynamic *registry* nil)

(defn live-test-fixture [f]
  (with-open [svc (hermes/open "snomed.db")]
    (binding [*registry* (-> {:com.wsscode.pathom3.error/lenient-mode? true}
                             (pci/register graph/all-resolvers)
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
  (testing "refset ids and items"
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
      (is (= (:info.snomed.Concept/refsetIds ms) refset-ids))))
  (testing "Refset item resolution"
    (let [ms (p.eql/process *registry* {:info.snomed.Concept/id 24700007}
                            [{:info.snomed.Concept/refsetItems [{:info.snomed.RefsetItem/referencedComponent [:info.snomed.Concept/id]}]}])]
      (is (every? #(= 24700007 %) (->> (:info.snomed.Concept/refsetItems ms)
                                       (map :info.snomed.RefsetItem/referencedComponent)
                                       (map :info.snomed.Concept/id)))))))

(deftest ^:live test-polymorphic-get
  (let [request [; we can use this same request against any component
                 :info.snomed.Concept/id
                 {:info.snomed.Concept/descriptions [:info.snomed.Description/id]}
                 :info.snomed.Description/id
                 :info.snomed.Description/conceptId
                 {:info.snomed.Description/concept [:info.snomed.Concept/id]}
                 {:info.snomed.Description/type [:info.snomed.Concept/id]}
                 :info.snomed.Relationship/id
                 :info.snomed.Relationship/sourceId
                 {:info.snomed.Relationship/source [:info.snomed.Concept/id]}
                 :info.snomed.RefsetItem/id
                 :info.snomed.RefsetItem/referencedComponentId
                 {:info.snomed.RefsetItem/referencedComponent [:info.snomed.Description/id]}]
        concept (p.eql/process *registry* {:info.snomed/id 74400008} request)
        description (p.eql/process *registry* {:info.snomed/id 123558018} request)
        relationship (p.eql/process *registry* {:info.snomed/id 859910029} request)
        refset-item (p.eql/process *registry* {:info.snomed/id "7c0d7d61-c571-5bf9-9329-fdbfee8747d0"} request)]
    (testing "Concept"
      (is (= 74400008 (:info.snomed.Concept/id concept)))
      (is (contains? (set (map :info.snomed.Description/id (:info.snomed.Concept/descriptions concept))) 123558018)))
    (testing "Description"
      (is (= 123558018 (:info.snomed.Description/id description)))
      (is (= 74400008 (:info.snomed.Description/conceptId description)))
      (is (= 74400008 (get-in description [:info.snomed.Description/concept :info.snomed.Concept/id]))))
    (testing "Relationship"
      (is (= 859910029 (:info.snomed.Relationship/id relationship)))
      (is (= 74400008 (:info.snomed.Relationship/sourceId relationship)))
      (is (= 74400008 (get-in relationship [:info.snomed.Relationship/source :info.snomed.Concept/id]))))
    (testing "Refset item"
      (is (= #uuid "7c0d7d61-c571-5bf9-9329-fdbfee8747d0" (:info.snomed.RefsetItem/id refset-item)))
      (is (= 123558018 (:info.snomed.RefsetItem/referencedComponentId refset-item))))))

(comment
  (def ^:dynamic *registry* (-> {:com.wsscode.pathom3.error/lenient-mode? true}
                                (pci/register graph/all-resolvers)
                                (assoc ::graph/svc (hermes/open "snomed.db"))))
  (run-tests))