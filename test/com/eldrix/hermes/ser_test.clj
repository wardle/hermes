(ns com.eldrix.hermes.ser-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is run-tests]]
            [com.eldrix.hermes.impl.ser :as ser]
            [com.eldrix.hermes.rf2 :as rf2]
            [com.eldrix.hermes.snomed :as snomed])
  (:import [java.time LocalDate]
           (io.netty.buffer PooledByteBufAllocator)
           (java.util UUID)))

(stest/instrument)

(def n 2000)

(defn test-ser [write-fn read-fn data]
  (let [b (.directBuffer (PooledByteBufAllocator/DEFAULT))]
    (try
      (write-fn b data)
      (is (= (read-fn b) data))
      (finally
        (.release b)))))

(deftest ser-string
  (doall (map #(test-ser ser/writeUTF ser/readUTF %) (gen/sample (s/gen string?) n)))
  (test-ser ser/writeUTF ser/readUTF "\uD835\uDD20ծềſģȟᎥ\uD835\uDC8Bǩľḿꞑȯ\uD835\uDE31\uD835\uDC5E\uD835\uDDCB\uD835\uDE34ȶ\uD835\uDF84\uD835\uDF08ψ\uD835\uDC99\uD835\uDE06\uD835\uDEA31234567"))

(deftest ser-uuid
  (doall (map #(test-ser ser/write-uuid ser/read-uuid %) (repeatedly n #(UUID/randomUUID)))))

(deftest ser-concept
  (test-ser ser/write-concept ser/read-concept (snomed/->Concept 24700007 (LocalDate/of 2005 1 1) true 0 0))
  (doall (map #(test-ser ser/write-concept ser/read-concept %) (gen/sample (rf2/gen-concept) n))))

(deftest ser-description
  (is (every? true? (map #(test-ser ser/write-description ser/read-description %) (gen/sample (rf2/gen-description) n)))))

(deftest ser-relationship
  (doall (map #(test-ser ser/write-relationship ser/read-relationship %) (gen/sample (rf2/gen-relationship) n))))

(deftest ser-concrete-value
  (run! #(test-ser ser/write-concrete-value ser/read-concrete-value %) (gen/sample (rf2/gen-concrete-value) n)))

(deftest ser-field-names
  (doall (map #(test-ser ser/write-field-names ser/read-field-names %) (gen/sample (s/gen (s/coll-of string?))))))

(def refset-generators
  [(rf2/gen-simple-refset)
   (rf2/gen-association-refset)
   (rf2/gen-language-refset)
   (rf2/gen-simple-map-refset)
   (rf2/gen-complex-map-refset)
   (rf2/gen-extended-map-refset)
   (rf2/gen-attribute-value-refset)
   (rf2/gen-owl-expression-refset)
   (rf2/gen-refset-descriptor-refset)
   (rf2/gen-module-dependency-refset)
   (rf2/gen-mrcm-domain-refset)
   (rf2/gen-mrcm-attribute-domain-refset)
   (rf2/gen-mrcm-attribute-range-refset)
   (rf2/gen-mrcm-module-scope-refset)])

(deftest ser-refset-items
  (dorun (map #(test-ser ser/write-fields ser/read-fields %) (gen/sample (s/gen :info.snomed.RefsetItem/fields) n)))
  (dorun (map #(test-ser ser/write-refset-item ser/read-refset-item %) (gen/sample (gen/one-of refset-generators) (* n (count refset-generators))))))

(comment
  (run-tests)
  (ser-refset-items)
  (ser-relationship))
