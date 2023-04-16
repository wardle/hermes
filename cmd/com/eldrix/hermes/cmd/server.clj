; Copyright 2020 Mark Wardle and Eldrix Ltd
;
;   Licensed under the Apache License, Version 2.0 (the "License");
;   you may not use this file except in compliance with the License.
;   You may obtain a copy of the License at
;
;       http://www.apache.org/licenses/LICENSE-2.0
;
;   Unless required by applicable law or agreed to in writing, software
;   distributed under the License is distributed on an "AS IS" BASIS,
;   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;   See the License for the specific language governing permissions and
;   limitations under the License.
;;;;
(ns com.eldrix.hermes.cmd.server
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]
            [io.pedestal.http :as http]
            [io.pedestal.http.content-negotiation :as conneg]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.interceptor.error :as intc-err])
  (:import (java.time.format DateTimeFormatter)
           (java.time LocalDate)
           (java.util Locale)))

(set! *warn-on-reflection* true)

(def supported-types ["application/json" "application/edn"])
(def content-neg-intc (conneg/negotiate-content supported-types))

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers headers})

(def ok (partial response 200))
(def not-found (partial response 404))

(defn accepted-type
  [ctx]
  (get-in ctx [:request :accept :field] "application/json"))

(defn write-local-date [^LocalDate o ^Appendable out _options]
  (.append out \")
  (.append out (.format (DateTimeFormatter/ISO_DATE) o))
  (.append out \"))

(extend LocalDate json/JSONWriter {:-write write-local-date})

(defn transform-content
  [body content-type]
  (case content-type
    "text/html" body
    "text/plain" body
    "application/edn" (.getBytes (pr-str body) "UTF-8")
    "application/json" (.getBytes (json/write-str body) "UTF-8")))

(defn coerce-to
  [resp content-type]
  (-> resp
      (update :body transform-content content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(def coerce-body
  {:name ::coerce-body
   :leave
   (fn [ctx]
     (if (get-in ctx [:response :headers "Content-Type"])
       ctx
       (update ctx :response coerce-to (accepted-type ctx))))})

(defn inject-svc
  "A simple interceptor to inject terminology service 'svc' into the context."
  [svc]
  {:name  ::inject-svc
   :enter (fn [ctx] (assoc ctx ::svc svc))})

(def log-request
  {:name  ::log-request
   :enter (fn [{req :request :as ctx}]
            (log/trace :request (select-keys req [:request-method :uri :query-string :remote-addr]))
            ctx)})

(def entity-render
  "Interceptor to render an entity '(:result ctx)' into the response."
  {:name :entity-render
   :leave
   (fn [ctx]
     (if-let [item (:result ctx)]
       (assoc ctx :response (ok item))
       ctx))})

(def service-error-handler
  (intc-err/error-dispatch
    [ctx err]

    [{:exception-type :java.lang.NumberFormatException}]
    (assoc ctx :response {:status 400
                          :body   {:error (str "invalid number: " (ex-message (:exception (ex-data err))))}})

    [{:exception-type :clojure.lang.ExceptionInfo}]
    (let [ex (:exception (ex-data err))]                    ;; unwrap error message
      (assoc ctx :response {:status 400 :body (merge {:error (ex-message ex)} (ex-data ex))}))

    :else
    (assoc ctx :io.pedestal.interceptor.chain/error err)))


(def get-concept
  {:name  ::get-concept
   :enter (fn [{::keys [svc] :as ctx}]
            (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))]
              (assoc ctx :result (hermes/concept svc concept-id))))})

(def get-extended-concept
  {:name  ::get-extended-concept
   :enter (fn [{::keys [svc] :as ctx}]
            (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
                  concept (hermes/extended-concept svc concept-id)
                  langs (or (get-in ctx [:request :headers "accept-language"]) (.toLanguageTag (Locale/getDefault)))
                  preferred (hermes/preferred-synonym svc concept-id langs)]
              (assoc ctx :result (when concept (assoc concept :preferredDescription preferred)))))})

(def get-historical
  {:name  ::get-historical
   :enter (fn [{::keys [svc] :as ctx}]
            (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
                  result (hermes/historical-associations svc concept-id)]
              (assoc ctx :result (or (seq result) (when (hermes/concept svc concept-id) result)))))})

(def get-concept-reference-sets
  {:name  ::get-concept-reference-sets
   :enter (fn [{::keys [svc] :as ctx}]
            (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))]
              (assoc ctx :result (hermes/component-refset-items-extended svc concept-id))))})

(def get-concept-descriptions
  {:name  ::get-concept-descriptions
   :enter (fn [{::keys [svc] :as ctx}]
            (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
                  result (hermes/descriptions svc concept-id)]
              (assoc ctx :result (or (seq result) (when (hermes/concept svc concept-id) result)))))})

(def get-concept-preferred-description
  {:name  ::get-concept-preferred-description
   :enter (fn [{::keys [svc] :as ctx}]
            (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
                  langs (or (get-in ctx [:request :headers "accept-language"]) (.toLanguageTag (Locale/getDefault)))
                  ds (hermes/preferred-synonym svc concept-id langs)]
              (assoc ctx :result ds)))})

(def get-map-to
  {:name  ::get-map-to
   :enter (fn [{::keys [svc] :as ctx}]
            (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
                  refset-id (Long/parseLong (get-in ctx [:request :path-params :refset-id]))]
              (if-let [rfs (seq (hermes/component-refset-items svc concept-id refset-id))]
                (assoc ctx :result rfs)                     ;; return the results as concept found in refset
                ;; if concept not found, map into the refset and get the refset items for all mapped results
                (let [mapped-concept-ids (hermes/map-concept-into svc concept-id refset-id)]
                  (assoc ctx :result (mapcat #(hermes/component-refset-items svc % refset-id) mapped-concept-ids))))))})

(def get-map-from
  {:name  ::get-map-from
   :enter (fn [{::keys [svc] :as ctx}]
            (let [refset-id (Long/parseLong (get-in ctx [:request :path-params :refset-id]))
                  code (get-in ctx [:request :path-params :code])]
              (assoc ctx :result (seq (hermes/reverse-map svc refset-id code)))))})

(def subsumed-by?
  {:name  ::subsumed-by
   :enter (fn [{::keys [svc] :as ctx}]
            (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
                  subsumer-id (Long/parseLong (get-in ctx [:request :path-params :subsumer-id]))]
              (assoc ctx :result {:subsumedBy (hermes/subsumed-by? svc concept-id subsumer-id)})))})

(defn parse-search-params
  [{:keys [s maxHits isA refset constraint ecl fuzzy fallbackFuzzy inactiveConcepts inactiveDescriptions removeDuplicates]}]
  (cond-> {}
          s (assoc :s s)
          constraint (assoc :constraint constraint)
          ecl (assoc :constraint ecl)
          maxHits (assoc :max-hits (Long/parseLong maxHits))
          (string? isA) (assoc :properties {snomed/IsA (Long/parseLong isA)})
          (coll? isA) (assoc :properties {snomed/IsA (mapv #(Long/parseLong %) isA)})
          (string? refset) (assoc :concept-refsets [(Long/parseLong refset)])
          (coll? refset) (assoc :concept-refsets (mapv #(Long/parseLong %) refset))
          fuzzy (assoc :fuzzy (if (#{"true" "1"} fuzzy) 2 0))
          fallbackFuzzy (assoc :fallback-fuzzy (if (#{"true" "1"} fallbackFuzzy) 2 0))
          inactiveConcepts (assoc :inactive-concepts? (boolean (#{"true" "1"} inactiveConcepts)))
          inactiveDescriptions (assoc :inactive-descriptions? (boolean (#{"true" "1"} inactiveDescriptions)))
          removeDuplicates (assoc :remove-duplicates? (boolean (#{"true" "1"} removeDuplicates)))))

(def get-search
  {:name  ::get-search
   :enter (fn [{::keys [svc] :as ctx}]
            (let [params (parse-search-params (get-in ctx [:request :params]))
                  max-hits (or (:max-hits params) 500)]
              (if (< 0 max-hits 10000)
                (assoc ctx :result (or (hermes/search svc (assoc params :max-hits max-hits)) []))
                (assoc ctx :response {:status 400 :body {:error (str "invalid parameter: maxHits")}}))))})

(def get-expand
  {:name  ::get-expand
   :enter (fn [{::keys [svc] :as ctx}]
            (let [ecl (get-in ctx [:request :params :ecl])
                  include-historic? (or (#{"true" "1"} (get-in ctx [:request :params :includeHistoric]))
                                        (#{"true" "1"} (get-in ctx [:request :params :include-historic])))] ; avoid breaking change - support legacy parameter
              (if (str/blank? ecl)
                (assoc ctx :response {:status 400 :body {:error (str "missing parameter: ecl")}})
                (assoc ctx :result (if include-historic?
                                     (hermes/expand-ecl-historic svc ecl)
                                     (hermes/expand-ecl svc ecl))))))})

(def common-routes [coerce-body content-neg-intc entity-render])
(def routes
  (route/expand-routes
    #{["/v1/snomed/concepts/:concept-id" :get (conj common-routes get-concept) :constraints {:concept-id #"[0-9]+"}]
      ["/v1/snomed/concepts/:concept-id/descriptions" :get (conj common-routes get-concept-descriptions) :constraints {:concept-id #"[0-9]+"}]
      ["/v1/snomed/concepts/:concept-id/preferred" :get (conj common-routes get-concept-preferred-description) :constraints {:concept-id #"[0-9]+"}]
      ["/v1/snomed/concepts/:concept-id/extended" :get (conj common-routes get-extended-concept) :constraints {:concept-id #"[0-9]+"}]
      ["/v1/snomed/concepts/:concept-id/historical" :get (conj common-routes get-historical) :constraints {:concept-id #"[0-9]+"}]
      ["/v1/snomed/concepts/:concept-id/refsets" :get (conj common-routes get-concept-reference-sets) :constraints {:concept-id #"[0-9]+"}]
      ["/v1/snomed/concepts/:concept-id/map/:refset-id" :get (conj common-routes get-map-to) :constraints {:concept-id #"[0-9]+" :refset-id #"[0-9]+"}]
      ["/v1/snomed/concepts/:concept-id/subsumed-by/:subsumer-id" :get (conj common-routes subsumed-by?) :constraints {:concept-id #"[0-9]+" :subsumer-id #"[0-9]+"}]
      ["/v1/snomed/crossmap/:refset-id/:code" :get (conj common-routes get-map-from) :constraints {:refset-id #"[0-9]+"}]
      ["/v1/snomed/search" :get [coerce-body service-error-handler content-neg-intc entity-render get-search]]
      ["/v1/snomed/expand" :get [coerce-body service-error-handler content-neg-intc entity-render get-expand]]}))

(def service-map
  {::http/routes         routes
   ::http/type           :jetty
   ::http/port           8080
   ::http/request-logger (intc/interceptor log-request)})

(defn create-server
  "Create a HTTP SNOMED CT server.
  Parameters:
  - svc             : Hermes service
  - port            : (optional) port to use, default 8080
  - bind-address    : (optional) bind address
  - allowed-origins : (optional) a sequence of strings of hostnames or function
  - join?           : whether to join server thread or return"
  ([svc {:keys [port bind-address allowed-origins join?] :or {join? true}}]
   (let [cfg (cond-> {}
                     port (assoc ::http/port port)
                     bind-address (assoc ::http/host bind-address)
                     allowed-origins (assoc ::http/allowed-origins allowed-origins))]
     (-> (merge service-map cfg)
         (assoc ::http/join? join?)
         (http/default-interceptors)
         (update ::http/interceptors conj (intc/interceptor (inject-svc svc)))
         http/create-server))))

(defn start-server
  [svc config]
  (http/start (create-server svc config)))

(defn stop-server [server]
  (http/stop server))

;; For interactive development
(defonce server (atom nil))

(defn start-dev [svc port]
  (reset! server
          (start-server svc {:port port :join? false})))

(defn stop-dev []
  (http/stop @server))

(comment
  (def svc (hermes/open "snomed.db"))
  (start-dev svc 8080)
  (stop-dev))
