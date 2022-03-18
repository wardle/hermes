(ns com.eldrix.hermes.owl
  "Support for ontological reasoning.

  This namespace should be regarded as experimental and subject to change or
  removal.

  In the past, the stated relationship file has been used to represent
  descriptive logic (DL) definitions. However, it is unable to fully represent
  semantics of the DL expressivity due to the limitation of its relational
  structure. The OWL refsets are designed to replace the stated relationship
  file and it represents the DL definitions for SNOMED CT content by following
  the international standard of OWL 2 Web Ontology Language.

  However, during the transition, I understand that one must use the stated
  relationships file to generate axioms, unless there are axioms provided in the
  axiom reference set. Some of the code here checks that assumption, by
  reporting on the axiom frequencies in a distribution.

  It remains unclear to me whether building and running an ontology with
  reasoner is sufficiently performant to replace all other approaches to
  inference, or should only be used when and where axioms exist for a given
  query, or is dependent on the type ofd query. It may be that operationally,
  one runs two independent services, or whether one uses a formal reasoner à la
  carte when needed.

  Further reading:
  - [SNOMED CT OWL Guide](https://confluence.ihtsdotools.org/display/DOCOWL/SNOMED+CT+OWL+Guide)
  - [SNOMED OWL toolkit](https://github.com/IHTSDO/snomed-owl-toolkit)."
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.rf2spec :as rf2]
            [com.eldrix.hermes.impl.store :as store])
  (:import [com.eldrix.hermes.core Service]
           (org.semanticweb.owlapi.expression ShortFormEntityChecker)
           (org.semanticweb.owlapi.util SimpleShortFormProvider BidirectionalShortFormProvider)))

(def owl-ontology-reference-set-id
  "Reference set defining essential information about the ontology, such as the
  namespaces, ontology URI, ontology version URI, and import statement.
  The OWL ontology refset enables the use of prefixes in the OWL axiom refset."
  762103008)

(def owl-axiom-reference-set-id
  "Reference set defining axioms. Axioms are statements or propositions which
  are regarded as being established, accepted, or self-evidently true in the
  domain. The OWL axioms are in the scope for the OWL axiom refset if they are
  allowed in the SNOMED CT Logic Profile Specification. Annotation axioms and
  class declarations are generally excluded from the OWL axiom refset to avoid
  duplication to the RF2 files."
  733073007)

(s/fdef get-owl-ontology
  :args (s/cat :svc any?)
  :ret (s/coll-of :info.snomed/OWLExpressionRefsetItem))
(defn get-owl-ontology
  "Return a sequence of refset identifiers representing essential information
  about the SNOMED ontology."
  [^Service svc]
  (->> (hermes/get-refset-members svc owl-ontology-reference-set-id)
       (mapcat #(hermes/get-component-refset-items svc % owl-ontology-reference-set-id))))

(s/fdef get-concept-axioms
  :args (s/cat :svc any? :concept-id :info.snomed.Concept/id))
(defn get-concept-axioms
  "Return the axioms associated with given concept id."
  [^Service svc concept-id]
  (hermes/get-component-refset-items svc concept-id owl-axiom-reference-set-id))

(defn- count-axioms
  "Return a frequency table of the counts of axioms per concept in the service
  specified. Designed to support exploration of a SNOMED distribution.
  For example, from the UK February 2022 release:
  ```
  (count-axioms svc)
  => {0 690200, 1 353336, 2 1017, 3 85, 4 7, 6 1, 5 1}

  (count-axioms svc {:only-active? true})
  => {0 690200, 1 353336, 2 1017, 3 85, 4 7, 6 1, 5 1}
  ```"
  ([^Service svc] (count-axioms svc {}))
  ([^Service svc {:keys [only-active?] :or {only-active? false}}]
   (let [ch (a/chan)
         get-axioms (fn [concept-id]
                      (if only-active? (filter :active (get-concept-axioms svc concept-id))
                                       (get-concept-axioms svc concept-id)))]
     (store/stream-all-concepts (.-store svc) ch)
     (loop [concept (a/<!! ch)
            count-frequencies {}]
       (if-not concept
         count-frequencies
         (recur (a/<!! ch)
                (update count-frequencies (count (get-axioms (:id concept))) (fnil inc 0))))))))

(require '[clojure.spec.test.alpha :as stest])
(stest/instrument)

(comment

  (def svc (hermes/open "snomed2.db"))
  (hermes/get-refset-members svc owl-axiom-reference-set-id)
  (hermes/get-component-refset-items svc 24700007 owl-axiom-reference-set-id)
  (count-axioms svc {:only-active? true})
  (get-concept-axioms svc 24700007)
  (get-concept-axioms svc 126516008)
  (hermes/get-all-parents svc 24700007)

  (hermes/get-component-refset-items svc 734147008 owl-ontology-reference-set-id)
  (hermes/get-component-refset-items svc 734146004 owl-ontology-reference-set-id)
  (hermes/get-refset-members svc owl-axiom-reference-set-id))


(comment
  (import '[org.semanticweb.owlapi.apibinding OWLManager])
  (def input-manager (OWLManager.))
  input-manager
  (clojure.reflect/reflect input-manager)
  (def parser (OWLManager/createManchesterParser))
  (def entity-checker (ShortFormEntityChecker. (BidirectionalShortFormProvider.)))
  (.setEntityChecker parser entity-checker)
  (.setStringToParse parser (-> (get-concept-axioms svc 24700007) first :owlExpression))
  (.parseAxiom parser)
  (clojure.reflect/reflect parser))
