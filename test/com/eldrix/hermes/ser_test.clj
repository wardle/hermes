(ns com.eldrix.hermes.ser-test
  (:require [clojure.test :refer :all]
            [com.eldrix.hermes.impl.ser :as ser]
            [com.eldrix.hermes.gen :as hgen]
            [com.eldrix.hermes.snomed :as snomed])
  (:import [java.time LocalDate]
           (java.io DataInputStream ByteArrayOutputStream DataOutputStream ByteArrayInputStream)
           (java.util UUID)))

(defn test-ser [write-fn read-fn data]
  (let [baos (ByteArrayOutputStream.)
        out (DataOutputStream. baos)
        _ (write-fn out data)
        bais (ByteArrayInputStream. (.toByteArray baos))
        in (DataInputStream. bais)
        data' (read-fn in)]
    (= data data')))

(deftest ser-uuid
  (is (every? true? (map #(test-ser ser/write-uuid ser/read-uuid %) (repeatedly 500 #(UUID/randomUUID))))))

(deftest ser-concept
  (is (test-ser ser/write-concept ser/read-concept (snomed/->Concept 24700007 (LocalDate/of 2005 1 1) true 0 0)))
  (is (every? true? (map #(test-ser ser/write-concept ser/read-concept %) (repeatedly 500 hgen/make-concept)))))

(deftest ser-description
  (is (every? true? (map #(test-ser ser/write-description ser/read-description %) (repeatedly 500 #(hgen/make-description 24700007))))))

(deftest ser-relationship
  (is (every? true? (map #(test-ser ser/write-relationship ser/read-relationship %) (repeatedly 500 hgen/make-relationship)))))

(deftest ser-extended-refset-item
  (is (test-ser ser/write-refset-item ser/read-refset-item (snomed/->ExtendedRefsetItem #uuid "80000517-8513-5ca0-a44c-dc66f3c3a1c6"
                                                                                        (LocalDate/now) true 0 1 24700007
                                                                                        [1 2 3 "hi there"]))))

(comment
  (run-tests)
  (ser-relationship))
