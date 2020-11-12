(ns com.eldrix.hermes.store-test
  (:require [clojure.test :refer :all]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.store :as store]))

(compile 'com.eldrix.hermes.snomed)

(deftest simple-store
  (with-open [st (store/open-store)]
    (let [concept (snomed/->Concept 24700007 (java.time.LocalDate/of 2020 11 11) true 0 0)]
      (store/write-batch {:type :info.snomed/Concept
                          :data [concept]} st)
      (is (= concept (store/get-concept st 24700007))))
    (let [description (snomed/map->Description {:id                 754365011,
                                                :effectiveTime      (java.time.LocalDate/of 2020 11 11)
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
      (is (= description (store/get-fully-specified-name st 24700007))))))

(comment
  (run-tests)
  )