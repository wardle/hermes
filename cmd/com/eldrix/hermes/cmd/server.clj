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
  (:require [cheshire.core :as json]
            [cheshire.generate :as json-gen]
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
           (com.fasterxml.jackson.core JsonGenerator)
           (java.util Locale)
           (com.eldrix.hermes.core Service)))

(set! *warn-on-reflection* true)

(def supported-types ["text/html" "application/edn" "application/json" "text/plain"])
(def content-neg-intc (conneg/negotiate-content supported-types))

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers headers})

(def ok (partial response 200))
(def not-found (partial response 404))

(defn accepted-type
  [context]
  (get-in context [:request :accept :field] "application/json"))

(json-gen/add-encoder LocalDate
                      (fn [^LocalDate o ^JsonGenerator out]
                        (.writeString out (.format (DateTimeFormatter/ISO_DATE) o))))

(defn transform-content
  [body content-type]
  (case content-type
    "text/html" body
    "text/plain" body
    "application/edn" (.getBytes (pr-str body) "UTF-8")
    "application/json" (.getBytes (json/generate-string body) "UTF-8")))

(defn coerce-to
  [response content-type]
  (-> response
      (update :body transform-content content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(def coerce-body
  {:name ::coerce-body
   :leave
         (fn [context]
           (if (get-in context [:response :headers "Content-Type"])
             context
             (update-in context [:response] coerce-to (accepted-type context))))})

(defn inject-svc
  "A simple interceptor to inject terminology service 'svc' into the context."
  [svc]
  {:name  ::inject-svc
   :enter (fn [context] (update context :request assoc ::service svc))})

(def entity-render
  "Interceptor to render an entity '(:result context)' into the response."
  {:name :entity-render
   :leave
         (fn [context]
           (if-let [item (:result context)]
             (assoc context :response (ok item))
             context))})

(def service-error-handler
  (intc-err/error-dispatch
    [context err]
    [{:exception-type :java.lang.NumberFormatException :interceptor ::get-search}]
    (assoc context :response {:status 400
                              :body   (str "Invalid search parameters; invalid number: " (ex-message (:exception (ex-data err))))})
    [{:exception-type :java.lang.IllegalArgumentException :interceptor ::get-search}]
    (assoc context :response {:status 400 :body (str "invalid search parameters: " (ex-message (:exception (ex-data err))))})

    [{:exception-type :clojure.lang.ExceptionInfo :interceptor ::get-search}]
    (assoc context :response {:status 400 :body (str "invalid search parameters: " (ex-message (:exception (ex-data err))))})

    :else
    (assoc context :io.pedestal.interceptor.chain/error err)))

(def get-concept
  {:name  ::get-concept
   :enter (fn [context]
            (when-let [concept-id (Long/parseLong (get-in context [:request :path-params :concept-id]))]
              (when-let [concept (hermes/get-concept (get-in context [:request ::service]) concept-id)]
                (assoc context :result concept))))})

(def get-extended-concept
  {:name  ::get-extended-concept
   :enter (fn [context]
            (let [svc (get-in context [:request ::service])]
              (when-let [concept-id (Long/parseLong (get-in context [:request :path-params :concept-id]))]
                (when-let [concept (hermes/get-extended-concept svc concept-id)]
                  (let [langs (or (get-in context [:request :headers "accept-language"] (.toLanguageTag (Locale/getDefault))))
                        preferred (hermes/get-preferred-synonym svc concept-id langs)]
                    (assoc context :result (assoc concept :preferredDescription preferred)))))))})

(def get-historical
  {:name  ::get-historical
   :enter (fn [context]
            (let [svc (get-in context [:request ::service])]
              (when-let [concept-id (Long/parseLong (get-in context [:request :path-params :concept-id]))]
                (assoc context :result (hermes/historical-associations svc concept-id)))))})

(def get-concept-descriptions
  {:name  ::get-concept-descriptions
   :enter (fn [context]
            (when-let [concept-id (Long/parseLong (get-in context [:request :path-params :concept-id]))]
              (when-let [ds (hermes/get-descriptions (get-in context [:request ::service]) concept-id)]
                (assoc context :result ds))))})

(def get-concept-preferred-description
  {:name  ::get-concept-preferred-description
   :enter (fn [context]
            (when-let [concept-id (Long/parseLong (get-in context [:request :path-params :concept-id]))]
              (let [langs (or (get-in context [:request :headers "accept-language"]) (.toLanguageTag (Locale/getDefault)))]
                (when-let [ds (hermes/get-preferred-synonym (get-in context [:request ::service])
                                                            concept-id
                                                            langs)]
                  (assoc context :result ds)))))})

(def get-map-to
  {:name  ::get-map-to
   :enter (fn [context]
            (let [concept-id (Long/parseLong (get-in context [:request :path-params :concept-id]))
                  refset-id (Long/parseLong (get-in context [:request :path-params :refset-id]))]
              (when (and concept-id refset-id)
                (when-let [rfs (hermes/get-component-refset-items (get-in context [:request ::service]) concept-id refset-id)]
                  (assoc context :result rfs)))))})

(def get-map-from
  {:name  ::get-map-from
   :enter (fn [context]
            (let [refset-id (Long/parseLong (get-in context [:request :path-params :refset-id]))
                  code (get-in context [:request :path-params :code])]
              (when (and refset-id code)
                (when-let [rfs (hermes/reverse-map (get-in context [:request ::service]) refset-id (str/upper-case code))]
                  (assoc context :result rfs)))))})

(def subsumed-by?
  {:name  ::subsumed-by
   :enter (fn [context]
            (let [concept-id (Long/parseLong (get-in context [:request :path-params :concept-id]))
                  subsumer-id (Long/parseLong (get-in context [:request :path-params :subsumer-id]))
                  svc (get-in context [:request ::service])]
              (log/info "subsumed by request: is " concept-id "subsumed by" subsumer-id ", using svc:" svc "?")
              (assoc context :result {:subsumedBy (hermes/subsumed-by? svc concept-id subsumer-id)})))})

(defn parse-search-params [params]
  (let [{:keys [s maxHits isA refset constraint ecl fuzzy fallbackFuzzy]} params]
    (cond-> {}
            s (assoc :s s)
            constraint (assoc :constraint constraint)
            ecl (assoc :constraint ecl)
            maxHits (assoc :max-hits (Integer/parseInt maxHits))
            (string? isA) (assoc :properties {snomed/IsA (Long/parseLong isA)})
            (vector? isA) (assoc :properties {snomed/IsA (into [] (map #(Long/parseLong %) isA))})
            (string? refset) (assoc :concept-refsets [(Long/parseLong refset)])
            (vector? refset) (assoc :concept-refsets (into [] (map #(Long/parseLong %) refset)))
            (#{"true" "1"} fuzzy) (assoc :fuzzy 2)
            (#{"true" "1"} fallbackFuzzy) (assoc :fallback-fuzzy 2))))

(def get-search
  {:name  ::get-search
   :enter (fn [context]
            (let [params (parse-search-params (get-in context [:request :params]))
                  svc (get-in context [:request ::service])
                  max-hits (or (:max-hits params) 200)]
              (if (< 0 max-hits 10000)
                (assoc context :result (hermes/search svc (assoc params :max-hits max-hits)))
                (throw (IllegalArgumentException. "invalid parameter: maxHits")))))})

(def get-expand
  {:name  ::get-expand
   :enter (fn [context]
            (let [ecl (get-in context [:request :params :ecl])
                  include-historic? (#{"true" "1"} (get-in context [:request :params :include-historic]))
                  svc (get-in context [:request ::service])
                  max-hits (or (get-in context [:request :params :max-hits]) 500)]
              (if (< 0 max-hits 10000)
                (assoc context :result (if include-historic?
                                         (hermes/expand-ecl-historic svc ecl)
                                         (hermes/expand-ecl svc ecl)))
                (throw (IllegalArgumentException. "invalid parameter: maxHits")))))})

(def common-routes [coerce-body content-neg-intc entity-render])
(def routes
  (route/expand-routes
    #{["/v1/snomed/concepts/:concept-id" :get (conj common-routes get-concept)]
      ["/v1/snomed/concepts/:concept-id/descriptions" :get (conj common-routes get-concept-descriptions)]
      ["/v1/snomed/concepts/:concept-id/preferred" :get (conj common-routes get-concept-preferred-description)]
      ["/v1/snomed/concepts/:concept-id/extended" :get (conj common-routes get-extended-concept)]
      ["/v1/snomed/concepts/:concept-id/historical" :get (conj common-routes get-historical)]
      ["/v1/snomed/concepts/:concept-id/map/:refset-id" :get (conj common-routes get-map-to)]
      ["/v1/snomed/concepts/:concept-id/subsumed-by/:subsumer-id" :get (conj common-routes subsumed-by?)]
      ["/v1/snomed/crossmap/:refset-id/:code" :get (conj common-routes get-map-from)]
      ["/v1/snomed/search" :get [service-error-handler coerce-body content-neg-intc entity-render get-search]]
      ["/v1/snomed/expand" :get [service-error-handler coerce-body content-neg-intc entity-render get-expand]]}))

(def service-map
  {::http/routes routes
   ::http/type   :jetty
   ::http/port   8080})

(defn start-server
  ([^Service svc {:keys [port bind-address join?] :as opts :or {join? true}}]
   (let [cfg (cond-> {}
                     port (assoc ::http/port port)
                     bind-address (assoc ::http/host bind-address))]
     (-> (merge service-map cfg)
         (assoc ::http/join? join?)
         (http/default-interceptors)
         (update ::http/interceptors conj (intc/interceptor (inject-svc svc)))
         http/create-server
         http/start))))

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
  (require '[com.eldrix.hermes.core])
  (def svc (com.eldrix.hermes.core/open "snomed.db"))
  (start-dev svc 8080)
  (stop-dev)
  )