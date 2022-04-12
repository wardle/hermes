(ns com.eldrix.hermes.ser-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is run-tests]]
            [com.eldrix.hermes.impl.ser :as ser]
            [com.eldrix.hermes.rf2 :as rf2]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.specs :as specs])
  (:import [java.time LocalDate]
           (java.io DataInputStream ByteArrayOutputStream DataOutputStream ByteArrayInputStream)
           (java.util UUID)))

(stest/instrument)

(def n 2000)

(defn test-ser [write-fn read-fn data]
  (let [baos (ByteArrayOutputStream.)
        out (DataOutputStream. baos)
        _ (write-fn out data)
        bais (ByteArrayInputStream. (.toByteArray baos))
        in (DataInputStream. bais)
        data' (read-fn in)]
    (is (= data data'))))

(deftest ser-uuid
  (doall (map #(test-ser ser/write-uuid ser/read-uuid %) (repeatedly n #(UUID/randomUUID)))))

(deftest ser-concept
  (test-ser ser/write-concept ser/read-concept (snomed/->Concept 24700007 (LocalDate/of 2005 1 1) true 0 0))
  (doall (map #(test-ser ser/write-concept ser/read-concept %) (gen/sample (rf2/gen-concept) n))))

(deftest ser-description
  (is (every? true? (map #(test-ser ser/write-description ser/read-description %) (gen/sample (rf2/gen-description) n)))))

(deftest ser-relationship
  (doall (map #(test-ser ser/write-relationship ser/read-relationship %) (gen/sample (rf2/gen-relationship) n))))

(def refset-generators
  [(rf2/gen-simple-refset)
   (rf2/gen-association-refset)
   (rf2/gen-language-refset)
   (rf2/gen-simple-map-refset)
   (rf2/gen-complex-map-refset)
   (rf2/gen-extended-map-refset)
   (rf2/gen-attribute-value-refset)
   (rf2/gen-owl-expression-refset)
   (rf2/gen-refset-descriptor-refset)])

(deftest ser-refset-items
  (dorun (map #(test-ser ser/write-fields ser/read-fields %) (gen/sample (s/gen :info.snomed.RefsetItem/fields) n)))
  (dorun (map #(test-ser ser/write-refset-item ser/read-refset-item %) (gen/sample (gen/one-of refset-generators) (* n (count refset-generators))))))

(comment
  (run-tests)
  (ser-refset-items)
  (ser-relationship))
