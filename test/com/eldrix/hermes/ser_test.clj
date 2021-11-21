(ns com.eldrix.hermes.ser-test
  (:require [clojure.test :refer :all]
            [com.eldrix.hermes.impl.ser :as ser]
            [com.eldrix.hermes.snomed :as snomed])
  (:import [java.time LocalDate]
           (java.io DataInputStream ByteArrayOutputStream DataOutputStream ByteArrayInputStream)))

(defn test-ser [write-fn read-fn data]
  (let [baos (ByteArrayOutputStream.)
        out (DataOutputStream. baos)
        _ (write-fn out data)
        bais (ByteArrayInputStream. (.toByteArray baos))
        in (DataInputStream. bais)
        data' (read-fn in)]
    (is (= data data'))))

(deftest ser-uuid
  (test-ser ser/write-uuid ser/read-uuid #uuid "80000517-8513-5ca0-a44c-dc66f3c3a1c6"))

(deftest ser-concept
  (test-ser ser/write-concept ser/read-concept (snomed/->Concept 24700007 (LocalDate/of 2005 1 1) true 0 0)))

(deftest ser-extended-refset-item
  (test-ser ser/write-refset-item ser/read-refset-item (snomed/->ExtendedRefsetItem #uuid "80000517-8513-5ca0-a44c-dc66f3c3a1c6"
                                                                                    (LocalDate/now) true 0 1 24700007
                                                                                    [1 2 3 "hi there"])))

(comment
  (run-tests))