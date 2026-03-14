(ns ^:no-doc com.eldrix.hermes.impl.reasoner
  "Classpath-safe facade for OWL reasoning.
  Can be required unconditionally — does not import any OWL API or ELK classes.
  Checks at runtime whether OWL libraries are on the classpath and delegates
  to com.eldrix.hermes.impl.owl when they are."
  (:require [clojure.tools.logging :as log]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.snomed :as snomed]))

(defn- dynaload
  "Lazily resolve a fully-qualified var symbol from an optional namespace.
  Returns the var's value, or throws if the namespace cannot be loaded."
  [qualified-sym]
  (let [ns-sym (-> qualified-sym namespace symbol)]
    (require ns-sym)
    @(resolve qualified-sym)))

(def ^:private owl-ns 'com.eldrix.hermes.impl.owl)

(def owl-loaded?
  "Delay that resolves to true if the impl.owl namespace can be loaded
  (i.e. OWL API and ELK classes are on the classpath)."
  (delay
    (try (require owl-ns) true
         (catch Throwable _ false))))

(defn owl-data-available?
  "True if the store contains OWL axiom reference set data."
  [store]
  (contains? (store/installed-reference-sets store) snomed/OWLAxiomReferenceSet))

(defn reasoning-available?
  "True if OWL libraries are on the classpath and OWL data is in the store."
  [store]
  (and @owl-loaded? (owl-data-available? store)))

(defn start-reasoner
  "Start an OWL reasoner from the given store, or nil if unavailable.
  Returns nil if OWL libraries are not on the classpath or no OWL data exists."
  ([store] (start-reasoner store {}))
  ([store opts]
   (when (reasoning-available? store)
     (try
       ((dynaload 'com.eldrix.hermes.impl.owl/start-reasoner) store opts)
       (catch Exception e
         (log/error e "failed to start OWL reasoner")
         nil)))))

(defn classify
  "Classify a CF expression against the loaded ontology."
  [reasoner cf-expression]
  ((dynaload 'com.eldrix.hermes.impl.owl/classify) reasoner cf-expression))

(defn subsumes?
  "Test subsumption between two CF expressions."
  [reasoner cf-a cf-b]
  ((dynaload 'com.eldrix.hermes.impl.owl/subsumes?) reasoner cf-a cf-b))

(defn necessary-normal-form
  "Compute the Necessary Normal Form of a CF expression."
  [reasoner cf-expression]
  ((dynaload 'com.eldrix.hermes.impl.owl/necessary-normal-form) reasoner cf-expression))
