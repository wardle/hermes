; Copyright (c) 2020-2026 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns ^:no-doc com.eldrix.hermes.impl.owl
  "OWL reasoning support for SNOMED CT.
  Converts classifiable form (CF) expressions to OWL API objects and provides
  ELK reasoner integration for classification and subsumption testing."
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.eldrix.hermes.impl.scg :as scg]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.io Closeable)
           (java.util Set UUID)
           (java.util.concurrent.locks ReentrantLock)
           (org.semanticweb.owlapi.apibinding OWLManager)
           (org.semanticweb.owlapi.functional.parser OWLFunctionalSyntaxOWLParser)
           (org.semanticweb.owlapi.io StringDocumentSource)
           (org.semanticweb.owlapi.model AxiomType IRI OWLAxiom OWLClass OWLClassExpression
                                         OWLDataFactory OWLOntology
                                         OWLOntologyLoaderConfiguration
                                         OWLOntologyManager
                                         OWLSubPropertyChainOfAxiom
                                         OWLTransitiveObjectPropertyAxiom)
           (org.semanticweb.owlapi.reasoner OWLReasoner InferenceType)
           (org.semanticweb.owlapi.util DefaultPrefixManager)
           (org.semanticweb.owlapi.vocab OWL2Datatype)
           (org.semanticweb.elk.owlapi ElkReasonerFactory)))

(set! *warn-on-reflection* true)

(def ^:private snomed-iri "http://snomed.info/id/")

;;;; ── Specs ──

(s/def ::equivalent-concepts (s/coll-of :info.snomed.Concept/id :kind set?))
(s/def ::direct-super-concepts (s/coll-of :info.snomed.Concept/id :kind set?))
(s/def ::proximal-primitive-supertypes (s/coll-of :info.snomed.Concept/id :kind set?))
(s/def ::classification-result
  (s/keys :req-un [::equivalent-concepts ::direct-super-concepts ::proximal-primitive-supertypes]))

;;;; ── CF → OWL API ──

(declare cf-value->owl-class-expression)

(defn- cf-attribute->owl-expression
  "Convert a CF attribute [type-id [tag value]] to an OWL expression.
  Concept values become ObjectSomeValuesFrom, concrete values become DataHasValue."
  [^OWLDataFactory factory ^DefaultPrefixManager pm [type-id [tag value]]]
  (let [obj-prop #(.getOWLObjectProperty factory (str ":" type-id) pm)
        data-prop #(.getOWLDataProperty factory (str ":" type-id) pm)]
    (case tag
      :concept
      (.getOWLObjectSomeValuesFrom factory (obj-prop)
        (.getOWLClass factory (str ":" value) pm))

      :expression
      (.getOWLObjectSomeValuesFrom factory (obj-prop)
        (cf-value->owl-class-expression factory pm value))

      :numeric
      (.getOWLDataHasValue factory (data-prop)
        (if (integer? value)
          (.getOWLLiteral factory (str value) OWL2Datatype/XSD_INTEGER)
          (.getOWLLiteral factory (str value) OWL2Datatype/XSD_DECIMAL)))

      :string
      (.getOWLDataHasValue factory (data-prop)
        (.getOWLLiteral factory ^String value))

      :boolean
      (.getOWLDataHasValue factory (data-prop)
        (.getOWLLiteral factory (boolean value))))))

(defn- make-intersection-or-single
  "If the collection has one element, return it. Otherwise wrap in ObjectIntersectionOf."
  [^OWLDataFactory factory exprs]
  (if (= 1 (count exprs))
    (first exprs)
    (.getOWLObjectIntersectionOf factory ^Set (set exprs))))

(defn- cf-group->role-group
  "Convert a CF group (set of attributes) to a role-group-wrapped expression.
  ObjectSomeValuesFrom(:609096000 ObjectIntersectionOf(...))"
  [^OWLDataFactory factory ^DefaultPrefixManager pm group]
  (let [group-exprs (mapv #(cf-attribute->owl-expression factory pm %) group)]
    (.getOWLObjectSomeValuesFrom factory
      (.getOWLObjectProperty factory (str ":" snomed/RoleGroup) pm)
      ^OWLClassExpression (make-intersection-or-single factory group-exprs))))

(defn- cf-value->owl-class-expression
  "Build the OWL class expression for the right-hand side of a CF expression."
  ^OWLClassExpression [^OWLDataFactory factory ^DefaultPrefixManager pm cf-expression]
  (let [{:cf/keys [focus-concepts ungrouped groups]} cf-expression
        top-level (concat
                    (map #(.getOWLClass factory (str ":" %) pm) focus-concepts)
                    (when ungrouped (map #(cf-attribute->owl-expression factory pm %) ungrouped))
                    (when groups (map #(cf-group->role-group factory pm %) groups)))]
    (make-intersection-or-single factory top-level)))

(defn cf->axiom
  "Convert a classifiable form expression to an OWLAxiom.

  The concept-id becomes the left-hand side. The CF expression provides the
  right-hand side: focus concepts become OWLClass references, ungrouped
  attributes become ObjectSomeValuesFrom, and groups are wrapped in
  ObjectSomeValuesFrom with the role group concept (609096000).

  Returns an OWLSubClassOfAxiom or OWLEquivalentClassesAxiom."
  [^OWLDataFactory factory ^DefaultPrefixManager pm concept-id cf-expression]
  (let [lhs (.getOWLClass factory (str ":" concept-id) pm)
        rhs (cf-value->owl-class-expression factory pm cf-expression)]
    (if (= :subtype-of (:cf/definition-status cf-expression))
      (.getOWLSubClassOfAxiom factory lhs rhs)
      (.getOWLEquivalentClassesAxiom factory ^OWLClassExpression lhs ^OWLClassExpression rhs))))

;;;; ── OWL String Parsing / Rendering ──

(def ^:private ontology-doc-start "Prefix(:=<http://snomed.info/id/>) Ontology(")
(def ^:private ontology-doc-end ")")

(defn create-axiom-deserializer
  "Create a single-threaded OWL axiom deserializer. Returns a function that
  takes an OWL functional syntax string and returns the parsed OWLAxiom."
  []
  (let [manager  (OWLManager/createOWLOntologyManager)
        ontology (.loadOntologyFromOntologyDocument
                   manager (StringDocumentSource. (str ontology-doc-start ontology-doc-end)))
        config   (OWLOntologyLoaderConfiguration.)
        parser   (OWLFunctionalSyntaxOWLParser.)]
    (fn deserialize [^String owl-expression]
      (when (str/blank? owl-expression)
        (throw (ex-info "OWL expression is blank" {:owl-expression owl-expression})))
      (let [expr (.replaceAll owl-expression "\\broleGroup\\b" "609096000")
            doc  (str ontology-doc-start expr ontology-doc-end)]
        (.parse parser (StringDocumentSource. doc) ontology config)
        (let [axioms (.getAxioms ontology)]
          (when (empty? axioms)
            (throw (ex-info "OWL expression produced no axiom"
                            {:owl-expression owl-expression})))
          (let [axiom (first axioms)]
            (.removeAxioms ontology axioms)
            axiom))))))

(defn axiom->owl-string
  "Render an OWLAxiom to SNOMED OWL functional syntax with short-form IRIs.
  Matches the approach used by the SNOMED OWL Toolkit's `axiomToString`:
  https://github.com/IHTSDO/snomed-owl-toolkit/blob/master/src/main/java/org/snomed/otf/owltoolkit/conversion/AxiomRelationshipConversionService.java#L297"
  [^OWLAxiom axiom]
  (-> (.toString axiom)
      (.replaceAll "<http://snomed.info/id/([0-9]+)>" ":$1")
      (.replaceAll "\\)\\s+\\)" "))")))

;;;; ── Property Chains ──

(defn- parse-property-id
  "Extract a SNOMED concept ID from an OWL object property IRI, or nil."
  [^org.semanticweb.owlapi.model.OWLObjectPropertyExpression prop]
  (let [iri (str (.getIRI (.asOWLObjectProperty (.getNamedProperty prop))))
        id-str (.replace ^String iri ^String snomed-iri "")]
    (when (re-matches #"\d+" id-str)
      (Long/parseLong id-str))))

(defn extract-property-chains
  "Extract property chains and transitive properties from an OWL ontology.
  Returns a map of {inferred-type-id #{[source-type-id dest-type-id] ...}}.

  Sources:
  - SubPropertyChainOf(ObjectPropertyChain(A B) C) => {C #{[A B]}}
  - TransitiveObjectProperty(P) => {P #{[P P]}}"
  [^OWLOntology ontology]
  (let [chains (atom {})]
    (doseq [^OWLSubPropertyChainOfAxiom ax (.getAxioms ontology AxiomType/SUB_PROPERTY_CHAIN_OF)]
      (let [chain (.getPropertyChain ax)
            super-id (parse-property-id (.getSuperProperty ax))
            chain-ids (mapv parse-property-id chain)]
        (when (and super-id (every? some? chain-ids) (= 2 (count chain-ids)))
          (swap! chains update super-id (fnil conj #{}) (vec chain-ids)))))
    (doseq [^OWLTransitiveObjectPropertyAxiom ax (.getAxioms ontology AxiomType/TRANSITIVE_OBJECT_PROPERTY)]
      (when-let [prop-id (parse-property-id (.getProperty ax))]
        (swap! chains update prop-id (fnil conj #{}) [prop-id prop-id])))
    @chains))

;;;; ── NNF (pure functions) ──

(defn- chain-redundant?
  "True if attribute `attr` in `group` is redundant due to a property chain.
  `property-chains` is {inferred-type #{[source dest] ...}}.
  `rel-targets-fn` is (fn [concept-id type-id] => #{target-ids}) — returns
  the target concepts for a given relationship type on a concept.
  `is-a-fn` is (fn [child parent] => boolean)."
  [property-chains rel-targets-fn is-a-fn group [type-id [tag value-id] :as attr]]
  (when (and (= :concept tag) (contains? property-chains type-id))
    (some (fn [[source-type dest-type]]
            (some (fn [[other-type [other-tag other-value]]]
                    (when (and (= :concept other-tag)
                               (= other-type source-type))
                      (let [targets (rel-targets-fn other-value dest-type)]
                        (or (contains? targets value-id)
                            (some #(is-a-fn value-id %) targets)))))
                  (disj group attr)))
          (get property-chains type-id))))

(defn compute-nnf
  "Compute the Necessary Normal Form from pre-collected classification data.

  Parameters:
  - pps-ids         : set of proximal primitive supertype concept IDs
  - cf-expression   : input CF expression (carries user-supplied refinements)
  - inherited-attrs : {:ungrouped #{...} :groups #{...}} collected from PPSs
  - opts map:
    :reduce-attrs   — (fn [attrs] => attrs') — Rule 1 within-group redundancy
    :reduce-groups  — (fn [groups] => groups') — Rule 1 cross-group redundancy
    :chains         — {type-id #{[source dest] ...}} property chain rules
    :rel-targets    — (fn [concept-id type-id] => #{target-ids})
    :is-a?          — (fn [child-id parent-id] => boolean)

  Returns a CF expression with :cf/definition-status :subtype-of."
  [pps-ids cf-expression inherited-attrs
   {:keys [reduce-attrs reduce-groups chains rel-targets is-a?]
    :or   {reduce-attrs identity reduce-groups identity
           chains {} rel-targets (constantly #{}) is-a? (constantly false)}}]
  (let [;; 1. Merge inherited + user-supplied
        all-ungrouped (into (:ungrouped inherited-attrs) (:cf/ungrouped cf-expression))
        all-groups    (into (:groups inherited-attrs) (:cf/groups cf-expression))
        ;; 2. Rule 1: type/value subsumption redundancy
        all-ungrouped (reduce-attrs all-ungrouped)
        all-groups    (->> all-groups
                          (into #{} (map #(set (reduce-attrs %))))
                          reduce-groups)
        ;; 3. Rule 2: property chain redundancy within groups
        property-chains chains
        all-groups    (if (seq property-chains)
                        (->> all-groups
                             (into #{} (comp
                                         (map (fn [g]
                                                (into #{} (remove #(chain-redundant? property-chains
                                                                                     rel-targets is-a? g %))
                                                      g)))
                                         (filter seq))))
                        all-groups)]
    (cond-> {:cf/definition-status :subtype-of
             :cf/focus-concepts    pps-ids}
      (seq all-ungrouped) (assoc :cf/ungrouped all-ungrouped)
      (seq all-groups)    (assoc :cf/groups all-groups))))

(defn collect-inherited-attributes
  "Collect inferred non-IS-A relationships from the store for a set of concept IDs.
  The store contains inferred relationships from the RF2 distribution — these
  are the complete NNF for each pre-coordinated concept, already computed by the
  OWL Toolkit. No ancestor walking is needed.
  Returns {:ungrouped #{...} :groups #{...}}."
  [store concept-ids]
  (reduce
    (fn [acc concept-id]
      (let [{:keys [ungrouped groups]} (scg/properties->attributes
                                         (store/properties-by-group store concept-id))]
        (-> acc
            (update :ungrouped into ungrouped)
            (update :groups into groups))))
    {:ungrouped #{} :groups #{}}
    concept-ids))

;;;; ── Service Layer ──

(defn build-ontology-from-store
  "Build an OWLOntology by streaming all refset items from the store, filtering
  for active OWL expression items, and parsing them in parallel.

  Spawns n-parsers worker threads, each with its own deserializer, all draining
  a shared expression channel. Parsed axioms are sent to an output channel and
  added to the ontology on the calling thread.

  Parameters:
  - store : an open hermes store
  - opts  : optional map
    :n-parsers   — parallel parser threads (default 8)
    :item-filter — predicate on OWLExpressionRefsetItem (default: identity)"
  (^OWLOntology [store] (build-ontology-from-store store {}))
  (^OWLOntology [store {:keys [n-parsers item-filter]
                        :or   {n-parsers 8 item-filter identity}}]
   (let [expr-ch  (a/chan 8192 (comp (filter #(instance? com.eldrix.hermes.snomed.OWLExpressionRefsetItem %))
                                     (filter :active)
                                     (remove #(= snomed/OWLOntologyReferenceSet (:refsetId %)))
                                     (filter item-filter)
                                     (map :owlExpression)))
         axiom-ch (a/chan 64 (partition-all 512))
         errors   (atom [])
         manager  (OWLManager/createOWLOntologyManager)
         ontology (.createOntology manager)
         ;; spawn n workers, each with own deserializer, draining the shared expr-ch
         done-chs (into [] (repeatedly n-parsers
                            #(a/thread
                               (let [parse (create-axiom-deserializer)]
                                 (loop []
                                   (when-let [owl-expr (a/<!! expr-ch)]
                                     (try
                                       (a/>!! axiom-ch (parse owl-expr))
                                       (catch Exception ex
                                         (log/error ex "failed to parse OWL expression")
                                         (swap! errors conj ex)))
                                     (recur)))))))]
     (log/info "loading OWL axioms from store...")
     (a/thread
       (try
         (store/stream-all-refset-items store expr-ch)
         (catch Exception e
           (log/error e "failed to stream refset items")
           (a/close! expr-ch))))
     ;; close axiom-ch once all workers complete
     (a/go
       (doseq [ch done-chs] (a/<! ch))
       (a/close! axiom-ch))
     (loop [n 0]
       (if-let [batch (a/<!! axiom-ch)]
         (do (.addAxioms ontology ^java.util.Collection batch)
             (recur (+ n (count batch))))
         (let [errs @errors]
           (when (seq errs)
             (log/warn (count errs) "OWL axiom(s) failed to parse"))
           (log/info "loaded" n "OWL axioms into ontology")
           (when (and (zero? n) (seq errs))
             (throw (ex-info "All OWL axioms failed to parse"
                             {:error-count (count errs)
                              :first-error (first errs)})))
           ontology))))))

(defn create-reasoner
  "Create an ELK reasoner and run initial classification.
  This is the expensive step (~30s-5min depending on SNOMED edition size).
  Returns the OWLReasoner instance."
  ^OWLReasoner [^OWLOntology ontology]
  (log/info "classifying ontology...")
  (let [reasoner-factory (ElkReasonerFactory.)
        reasoner         (.createReasoner reasoner-factory ontology)]
    (.precomputeInferences reasoner
      (into-array InferenceType [InferenceType/CLASS_HIERARCHY]))
    reasoner))

(def ^:private temp-iri-prefix "http://snomed.info/temp/classify/")

(defprotocol Reasoner
  "Protocol for OWL reasoning over SNOMED CT expressions."
  (-classify [this cf-expression]
    "Classify a CF expression. Returns a classification result map with
    :equivalent-concepts, :direct-super-concepts, and :proximal-primitive-supertypes.")
  (-subsumes? [this cf-a cf-b]
    "Test subsumption between two CF expressions. Returns one of:
    :equivalent, :subsumes, :subsumed-by, :not-subsumed.
    Aligns with the FHIR CodeSystem $subsumes operation.")
  (-necessary-normal-form [this cf-expression]
    "Return the Necessary Normal Form of a CF expression after classification.
    The NNF expresses the concept in terms of its proximal primitive supertypes
    plus all necessary role relationships. Returns a CF expression with
    :cf/definition-status :subtype-of and :cf/focus-concepts set to the
    proximal primitive supertypes.")
  (-close [this]
    "Release resources held by the reasoner."))

(s/def ::reasoner #(satisfies? Reasoner %))

(defn- parse-iri->concept-id
  "Extract a SNOMED concept ID from an OWL class IRI, or nil."
  [^OWLClass cls]
  (let [iri (str (.getIRI cls))
        id-str (.replace ^String iri ^String snomed-iri "")]
    (when (re-matches #"\d+" id-str)
      (Long/parseLong id-str))))

(defn- make-temp-axiom
  "Build a temporary OWL axiom for a CF expression.
  Returns {:temp-cls OWLClass, :axiom OWLAxiom}."
  [^OWLDataFactory factory ^DefaultPrefixManager pm cf-expression]
  (let [temp-iri (IRI/create (str temp-iri-prefix (UUID/randomUUID)))
        temp-cls (.getOWLClass factory temp-iri)
        rhs      (cf-value->owl-class-expression factory pm cf-expression)
        axiom    (if (= :subtype-of (:cf/definition-status cf-expression))
                   (.getOWLSubClassOfAxiom factory temp-cls rhs)
                   (.getOWLEquivalentClassesAxiom factory
                     ^OWLClassExpression temp-cls ^OWLClassExpression rhs))]
    {:temp-cls temp-cls :axiom axiom}))

(defn- primitive-class?
  "A concept is primitive if it has no EquivalentClasses axiom in the ontology."
  [^OWLOntology ontology ^OWLClass cls]
  (empty? (.getEquivalentClassesAxioms ontology cls)))

(defn- proximal-primitive-supertypes
  "Walk the inferred hierarchy upward from `cls`, skipping defined (fully-defined)
  concepts and collecting only the most specific (proximal) primitive ancestors.
  A concept is primitive when it has no EquivalentClasses axiom in the ontology.
  After collecting all reachable primitive ancestors, non-proximal ones (those
  subsumed by another in the set) are removed."
  [^OWLOntology ontology ^OWLReasoner reasoner ^OWLClass cls]
  (let [all-primitives
        (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                           (.getFlattened (.getSuperClasses reasoner cls true)))
               result #{}]
          (if-let [^OWLClass super (peek queue)]
            (cond
              (.isOWLThing super)
              (recur (pop queue) result)

              (primitive-class? ontology super)
              (recur (pop queue) (conj result super))

              :else ;; defined — skip through to its supers
              (recur (into (pop queue) (.getFlattened (.getSuperClasses reasoner super true)))
                     result))
            result))]
    ;; Remove non-proximal: if A is a superclass of B, keep B (more specific)
    (into #{} (remove (fn [^OWLClass candidate]
                        (some (fn [^OWLClass other]
                                (and (not= candidate other)
                                     (.containsEntity
                                       (.getSuperClasses reasoner other false)
                                       candidate)))
                              all-primitives))
                      all-primitives))))

(defn- query-classification
  "Query the reasoner for equivalent classes, direct super classes, and
  proximal primitive supertypes of a temp class."
  [^OWLOntology ontology ^OWLReasoner reasoner ^OWLClass temp-cls]
  (let [equivalents (->> (.getEquivalentClasses reasoner temp-cls)
                         (.getEntities)
                         (remove (fn [^OWLClass c] (.isOWLNothing c)))
                         (keep parse-iri->concept-id)
                         set)
        supers     (->> (.getSuperClasses reasoner temp-cls true)
                        (.getFlattened)
                        (remove (fn [^OWLClass c] (.isOWLThing c)))
                        (keep parse-iri->concept-id)
                        set)
        primitives (->> (proximal-primitive-supertypes ontology reasoner temp-cls)
                        (keep parse-iri->concept-id)
                        set)]
    {:equivalent-concepts equivalents
     :direct-super-concepts supers
     :proximal-primitive-supertypes primitives}))

(defn- with-temp-axioms
  "Create temporary axioms from CF expressions, add them to the ontology, flush
  the reasoner, call f with a vector of the temporary OWLClass instances, then
  remove the axioms and flush again. Acquires the lock for thread safety."
  [^OWLOntology ontology ^OWLDataFactory factory ^DefaultPrefixManager pm
   ^OWLReasoner reasoner ^ReentrantLock lock cf-expressions f]
  (let [temps  (mapv #(make-temp-axiom factory pm %) cf-expressions)
        axioms (mapv :axiom temps)]
    (.lock lock)
    (try
      (.addAxioms ontology ^java.util.Collection axioms)
      (try
        (.flush reasoner)
        (f (mapv :temp-cls temps))
        (finally
          (try
            (.removeAxioms ontology ^java.util.Collection axioms)
            (catch Exception e
              (log/error e "failed to remove temporary axioms")))
          (try
            (.flush reasoner)
            (catch Exception e
              (log/error e "failed to flush reasoner after cleanup")))))
      (finally
        (.unlock lock)))))

(defrecord SimpleReasoner
  [^OWLOntology ontology
   ^OWLDataFactory factory
   ^DefaultPrefixManager prefix-manager
   ^OWLReasoner reasoner
   ^ReentrantLock lock
   store
   property-chains]
  Reasoner
  (-classify [_ cf-expression]
    (with-temp-axioms ontology factory prefix-manager reasoner lock [cf-expression]
      (fn [[^OWLClass temp-cls]]
        (query-classification ontology reasoner temp-cls))))
  (-subsumes? [_ cf-a cf-b]
    (with-temp-axioms ontology factory prefix-manager reasoner lock [cf-a cf-b]
      (fn [[^OWLClass a-cls ^OWLClass b-cls]]
        (let [a-subs-b (.containsEntity (.getSuperClasses reasoner b-cls false) a-cls)
              b-subs-a (.containsEntity (.getSuperClasses reasoner a-cls false) b-cls)
              equiv    (.contains (.getEquivalentClasses reasoner a-cls) b-cls)]
          (cond
            equiv    :equivalent
            a-subs-b :subsumes
            b-subs-a :subsumed-by
            :else    :not-subsumed)))))
  (-necessary-normal-form [_ cf-expression]
    (with-temp-axioms ontology factory prefix-manager reasoner lock [cf-expression]
      (fn [[^OWLClass temp-cls]]
        (let [pps-ids   (->> (proximal-primitive-supertypes ontology reasoner temp-cls)
                             (keep parse-iri->concept-id) set)
              inherited (collect-inherited-attributes store (:cf/focus-concepts cf-expression))]
          (compute-nnf pps-ids cf-expression inherited
                       {:reduce-attrs  #(scg/remove-subsumed-attributes store %)
                        :reduce-groups #(scg/remove-subsumed-groups store %)
                        :chains        property-chains
                        :rel-targets   #(store/parent-relationships-of-type store %1 %2)
                        :is-a?         #(store/is-a? store %1 %2)})))))
  (-close [_] (.dispose reasoner))
  Closeable
  (close [this] (-close this)))

;;;; ── Public API ──

(s/fdef classify
  :args (s/cat :reasoner ::reasoner :cf-expression :cf/expression)
  :ret ::classification-result)

(defn classify
  "Classify a CF expression against the loaded ontology.
  Returns a classification result map with :equivalent-concepts,
  :direct-super-concepts, and :proximal-primitive-supertypes."
  [reasoner cf-expression]
  (-classify reasoner cf-expression))

(s/fdef subsumes?
  :args (s/cat :reasoner ::reasoner :cf-a :cf/expression :cf-b :cf/expression)
  :ret #{:equivalent :subsumes :subsumed-by :not-subsumed})

(defn subsumes?
  "Test subsumption between two CF expressions. Returns one of:
  :equivalent, :subsumes, :subsumed-by, :not-subsumed.
  Aligns with the FHIR CodeSystem $subsumes operation."
  [reasoner cf-a cf-b]
  (-subsumes? reasoner cf-a cf-b))

(s/fdef necessary-normal-form
  :args (s/cat :reasoner ::reasoner :cf-expression :cf/expression)
  :ret :cf/expression)

(defn necessary-normal-form
  "Return the Necessary Normal Form (NNF) of a CF expression after classification.

  The NNF expresses a concept/expression in terms of:
  1. Proximal primitive supertypes — the most specific primitive ancestors in
     the inferred hierarchy (from the OWL reasoner)
  2. All necessary role relationships — inherited from the proximal primitive
     supertypes plus user-supplied refinements, with redundancies removed

  Redundancy removal applies two rules:
  - Rule 1: attributes subsumed by a more specific attribute with the same type
  - Rule 2: attributes implied by property chains (e.g., direct-substance ∘
    is-modification-of → direct-substance)

  Returns a CF expression with :cf/definition-status :subtype-of.

  See: SNOMED OWL Toolkit RelationshipNormalFormGenerator"
  [reasoner cf-expression]
  (-necessary-normal-form reasoner cf-expression))

(defn owl-available?
  "True if the store contains OWL axiom data."
  [store]
  (contains? (store/installed-reference-sets store) snomed/OWLAxiomReferenceSet))

(s/fdef start-reasoner
  :args (s/cat :store any? :opts (s/? map?))
  :ret (s/nilable ::reasoner))

(defn start-reasoner
  "Load OWL axioms from a hermes store and start the ELK reasoner.
  Returns a Reasoner (satisfying Closeable), or nil if no OWL axioms found.

  Parameters:
  - store       : an open hermes store
  - opts map (optional):
    :n-parsers   — parallel parser threads (default 8)
    :item-filter — predicate on OWLExpressionRefsetItem (default: identity)"
  ([store] (start-reasoner store {}))
  ([store opts]
   (when (owl-available? store)
     (let [ontology (build-ontology-from-store store opts)]
       (when (pos? (.getAxiomCount ontology))
         (let [reasoner (create-reasoner ontology)
               factory  (.getOWLDataFactory (.getOWLOntologyManager ontology))
               pm       (doto (DefaultPrefixManager.) (.setDefaultPrefix snomed-iri))
               chains   (extract-property-chains ontology)]
           (log/info "extracted" (count chains) "property chain rule(s) from ontology")
           (->SimpleReasoner ontology factory pm reasoner (ReentrantLock.) store chains)))))))

(defn stop-reasoner
  "Dispose of the reasoner and release resources."
  [reasoner]
  (when reasoner
    (-close reasoner)))

;;;; ── ECL generation ──

(declare cf->ecl*)

(defn- cf-value->ecl
  "Render a CF tagged attribute value as an ECL expression constraint.
  Concept values use descendant-or-self (<<) for subsumption-aware matching."
  [[tag value]]
  (case tag
    :concept    (str "<< " value)
    :expression (str "(" (cf->ecl* value) ")")
    :numeric    (str "#" value)
    :string     (str "\"" (-> value
                               (str/replace "\\" "\\\\")
                               (str/replace "\"" "\\\"")) "\"")
    :boolean    (if value "TRUE" "FALSE")))

(defn- cf-attr->ecl
  "Render a CF attribute [type-id [tag value]] as an ECL attribute constraint."
  [[type-id value]]
  (str type-id " = " (cf-value->ecl value)))

(defn cf->ecl*
  "Render a classifiable form expression as an ECL expression constraint string.
  Focus concepts use descendant-or-self (<<), concept attribute values use
  descendant-or-self (<<). Concrete values use exact match.

  This is a pure rendering function — it does not invoke the reasoner. Use
  [[cf->ecl]] for reasoner-informed ECL generation."
  [{:cf/keys [focus-concepts ungrouped groups]}]
  (let [foci       (sort focus-concepts)
        focus-ecl  (if (= 1 (count foci))
                     (str "<< " (first foci))
                     (str "(" (str/join " AND " (map #(str "<< " %) foci)) ")"))
        ungrouped-strs (when (seq ungrouped)
                         (->> (sort-by first ungrouped)
                              (map cf-attr->ecl)))
        group-strs     (when (seq groups)
                         (->> (sort-by #(apply min (map first %)) groups)
                              (map (fn [g]
                                     (str "{ "
                                          (->> (sort-by first g)
                                               (map cf-attr->ecl)
                                               (str/join ", "))
                                          " }")))))
        all-refinements (concat ungrouped-strs group-strs)]
    (if (seq all-refinements)
      (str focus-ecl " : " (str/join ", " all-refinements))
      focus-ecl)))

(defn cf->ecl
  "Generate an ECL expression constraint from a classifiable form expression
  using OWL reasoning.

  If the reasoner finds a pre-coordinated equivalent, returns `<< concept-id`.
  Otherwise computes the Necessary Normal Form — proximal primitive supertypes
  plus all necessary relationships — and renders it as an ECL refinement
  constraint with descendant-or-self (<<) operators on concept values.

  The resulting ECL, when evaluated against a SNOMED index, approximates
  'all pre-coordinated concepts subsumed by this expression', informed by
  the reasoner's classification rather than naive structural matching."
  [reasoner cf-expression]
  (let [result      (classify reasoner cf-expression)
        equivalents (:equivalent-concepts result)]
    (if (seq equivalents)
      (str "<< " (first (sort equivalents)))
      (cf->ecl* (necessary-normal-form reasoner cf-expression)))))


(comment
  (require '[com.eldrix.hermes.core :as hermes])
  (require '[com.eldrix.hermes.impl.scg :as scg])
  (def svc (hermes/open "snomed-owl.db"))
  (def st (.-store svc))
  (def reasoner (start-reasoner st))

  ;; Equivalence discovery: express the definition of a fully-defined concept
  ;; as a post-coordinated expression. The reasoner should find it equivalent
  ;; to the pre-coordinated concept — something structural subsumption cannot do.
  ;; Here we express appendectomy (80146002) via its defining relationships:
  (classify reasoner (scg/ctu->cf (scg/str->ctu "80146002")))
  ;; => :equivalent-concepts should contain 80146002

  ;; GCI-derived classification: clinical finding GCI axioms can infer parents
  ;; beyond the focus concept. A disorder defined by finding site + morphology
  ;; may be classified under a parent that isn't structurally obvious.
  ;; e.g. "disorder of lung" with a specific morphology may be recognised as
  ;; a specific named lung disease via GCI axioms:
  (classify reasoner (scg/ctu->cf (scg/str->ctu
    "64572001 |disease| : { 363698007 |finding site| = 39607008 |lung structure|, 116676008 |associated morphology| = 79654002 |edema| }")))

  ;; "disease with finding site=femur and morphology=fracture" — the reasoner
  ;; should infer this is a subtype of fracture of femur (71620000) via GCI
  ;; axioms, not just a subtype of disease:
  (classify reasoner (scg/ctu->cf (scg/str->ctu
    "64572001 |disease| : { 363698007 |finding site| = 71341001 |bone structure of femur|, 116676008 |associated morphology| = 72704001 |fracture| }")))

  ;; Necessary Normal Form: express an expression in terms of its proximal
  ;; primitive supertypes + all inherited relationships. The NNF is always primitive.
  (necessary-normal-form reasoner (scg/ctu->cf (scg/str->ctu "80146002")))
  ;; => {:cf/definition-status :subtype-of
  ;;     :cf/focus-concepts #{<primitive supers>}
  ;;     :cf/groups #{#{[260686004 [:concept 129304002]] [405813007 [:concept 66754008]]}}}
  ;; The groups now include inherited relationships from the concept's definition.

  ;; NNF of a post-coordinated expression merges user refinements with inherited:
  (necessary-normal-form reasoner (scg/ctu->cf (scg/str->ctu
    "80146002 : 272741003 = 7771000")))
  ;; The NNF includes laterality=left (user) + method/site groups (inherited)

  ;; Subsumption testing (FHIR CodeSystem $subsumes):
  (subsumes? reasoner
    (scg/ctu->cf (scg/str->ctu "80146002"))
    (scg/ctu->cf (scg/str->ctu "80146002 : 272741003 = 7771000")))
  ;; => :subsumes

  ;; ECL generation from SCG expressions:
  ;; A fully-defined concept has a pre-coordinated equivalent:
  (cf->ecl reasoner (scg/ctu->cf (scg/str->ctu "80146002")))
  ;; => "<< 80146002"

  ;; A post-coordinated expression has no equivalent — uses NNF:
  (cf->ecl reasoner (scg/ctu->cf (scg/str->ctu "80146002 : 272741003 = 7771000")))
  ;; => "<< prim1 : 272741003 = << 7771000, { ... }"

  ;; Pure rendering without reasoner:
  (cf->ecl* (scg/ctu->cf (scg/str->ctu "80146002 : 272741003 = 7771000")))
  ;; => "<< 80146002 : 272741003 = << 7771000"

  (stop-reasoner reasoner)
  (hermes/close svc))
