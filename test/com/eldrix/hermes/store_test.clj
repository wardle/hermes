(ns com.eldrix.hermes.store-test
  (:require [clojure.test :refer :all]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.store :as store]))

(deftest simple-store
  (let [filename (str (gensym "test-sct-store") ".db")]
    (with-open [st (store/open-store filename {:read-only? false})]
      (let [concept (snomed/->Concept 24700007 (java.time.LocalDate/now) true 0 0)]
        (store/write-batch {:type :info.snomed/Concept
                            :data [concept]} st)
        (is (= concept (store/get-concept st 24700007))))
      (let [description (snomed/map->Description {:id                 754365011,
                                                  :effectiveTime      (java.time.LocalDate/now)
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
        (store/build-description-index st)
        (is (= description (store/get-fully-specified-name st 24700007)))))
    (.delete (java.io.File. ^String filename))))

(comment
  (run-tests)
  )