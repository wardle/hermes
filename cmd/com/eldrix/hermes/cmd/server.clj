; Copyright (c) 2020-2024 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
(ns com.eldrix.hermes.cmd.server
  (:require [clojure.core.match :as match]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.content-negotiation :as conneg]
            [io.pedestal.http.cors :as cors]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.secure-headers :as sec-headers]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.service.interceptors :as interceptors])
  (:import (java.time.format DateTimeFormatter)
           (java.time LocalDate)))

(set! *warn-on-reflection* true)

(def supported-types ["application/json" "application/edn"])
(def content-neg-intc (conneg/negotiate-content supported-types))

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers headers})

(def ok (partial response 200))

(defn accepted-type
  [ctx]
  (get-in ctx [:request :accept :field] "application/json"))

(defn write-local-date [^LocalDate o ^Appendable out _options]
  (.append out \")
  (.append out (.format DateTimeFormatter/ISO_DATE o))
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
  (intc/interceptor
    {:name ::coerce-body
     :leave
     (fn [ctx]
       (if (get-in ctx [:response :headers "Content-Type"])
         ctx
         (update ctx :response coerce-to (accepted-type ctx))))}))

(defn inject-svc
  "A simple interceptor to inject terminology service 'svc' into the context."
  [svc]
  (intc/interceptor
    {:name ::inject-svc
     :enter
     (fn [ctx] (assoc ctx ::svc svc))}))

(def log-request                                            ;; TODO: also update metrics similar to default log interception; see https://github.com/pedestal/pedestal/blob/0.7.0-beta-2/service/src/io/pedestal/http.clj#L87
  (intc/interceptor
    {:name ::log-request
     :enter
     (fn [{req :request :as ctx}]
       (log/trace :request (select-keys req [:request-method :uri :query-string :remote-addr]))
       ctx)}))

(def entity-render
  "Interceptor to render an entity '(:result ctx)' into the response."
  (intc/interceptor
    {:name ::entity-render
     :leave
     (fn [ctx]
       (if-let [item (:result ctx)]
         (assoc ctx :response (ok item))
         ctx))}))

(def service-error-handler
  "Pedestal wraps all exceptions in ex-info. The following keys are defined in its exception data map:
  - :execution-id   - unique value
  - :stage          - :enter or :leave
  - :interceptor    - the :name of the interceptor
  - :exception-type - keywordized form of the exception's Java class name.
  See https://pedestal.io/pedestal/0.8/reference/error-handling.html for more information."
  (intc/interceptor
    {:name ::error
     :error
     (fn [ctx err]
       (match/match [(ex-data err)]
         [{:exception-type :java.lang.NumberFormatException}]
         (assoc ctx :response {:status 400
                               :body   {:error   "invalid number"
                                        :message (ex-message err)}})

         [{:exception-type :clojure.lang.ExceptionInfo}]
         (let [ex (ex-cause err)]                           ;; unwrap error message as all Pedestal exceptions are wrapped in 'ex-info'
           (assoc ctx :response {:status 400 :body (merge {:error (ex-message ex)} (ex-data ex))}))

         :else
         (assoc ctx :io.pedestal.interceptor.chain/error err)))}))

(defn parse-flag [s] (case s ("1" "true") true false))

(def get-concept
  (intc/interceptor
    {:name ::get-concept
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))]
         (assoc ctx :result (hermes/concept svc concept-id))))}))

(def get-extended-concept
  (intc/interceptor
    {:name ::get-extended-concept
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
             concept (hermes/extended-concept svc concept-id)
             langs (get-in ctx [:request :headers "accept-language"])
             preferred (hermes/preferred-synonym svc concept-id langs true)]
         (assoc ctx :result (when concept (assoc concept :preferredDescription preferred)))))}))

(def get-historical
  (intc/interceptor
    {:name ::get-historical
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
             result (hermes/historical-associations svc concept-id)]
         (assoc ctx :result (or (seq result) (when (hermes/concept svc concept-id) result)))))}))

(def get-concept-reference-sets
  (intc/interceptor
    {:name ::get-concept-reference-sets
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))]
         (assoc ctx :result (hermes/component-refset-items-extended svc concept-id))))}))

(def get-concept-descriptions
  (intc/interceptor
    {:name ::get-concept-descriptions
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
             result (hermes/descriptions svc concept-id)]
         (assoc ctx :result (or (seq result) (when (hermes/concept svc concept-id) result)))))}))

(def ^:private property-formats
  {"id"       :id
   "syn"      :syn
   "[id:syn]" :vec-id-syn
   "{id:syn}" :map-id-syn
   "id:syn"   :str-id-syn})

(def get-concept-properties
  (intc/interceptor
    {:name ::get-concept-properties
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
             expand? (parse-flag (get-in ctx [:request :params :expand]))
             fmt (property-formats (get-in ctx [:request :params :format]))
             key-fmt (or (property-formats (get-in ctx [:request :params :key-format])) fmt)
             value-fmt (or (property-formats (get-in ctx [:request :params :value-format])) fmt)
             pretty (or key-fmt value-fmt)
             language-range (get-in ctx [:request :headers "accept-language"])
             result (hermes/properties svc concept-id {:expand expand?})]
         (assoc ctx :result                                 ;; take care that if no result, is it because no props, or concept doesn't exist?
                    (if (seq result)                        ;; so if there's an empty result
                      (if pretty (hermes/pprint-properties svc result {:key-fmt key-fmt :value-fmt value-fmt :lang language-range}) result)
                      (when (hermes/concept svc concept-id) result)))))})) ;; return 404 if concept doesn't exist

(def get-concept-preferred-description
  (intc/interceptor
    {:name ::get-concept-preferred-description
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
             langs (get-in ctx [:request :headers "accept-language"])
             ds (hermes/preferred-synonym svc concept-id langs true)]
         (assoc ctx :result ds)))}))

(def get-map-to
  (intc/interceptor
    {:name ::get-map-to
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
             refset-id (Long/parseLong (get-in ctx [:request :path-params :refset-id]))]
         (if-let [rfs (seq (hermes/component-refset-items svc concept-id refset-id))]
           (assoc ctx :result rfs)                          ;; return the results as concept found in refset
           ;; if concept not found, map into the refset and get the refset items for all mapped results
           (let [mapped-concept-ids (hermes/map-concept-into svc concept-id refset-id)]
             (assoc ctx :result (mapcat #(hermes/component-refset-items svc % refset-id) mapped-concept-ids))))))}))

(def get-map-from
  (intc/interceptor
    {:name ::get-map-from
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [refset-id (Long/parseLong (get-in ctx [:request :path-params :refset-id]))
             code (get-in ctx [:request :path-params :code])]
         (assoc ctx :result (seq (hermes/reverse-map svc refset-id code)))))}))

(def subsumed-by?
  (intc/interceptor
    {:name ::subsumed-by
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [concept-id (Long/parseLong (get-in ctx [:request :path-params :concept-id]))
             subsumer-id (Long/parseLong (get-in ctx [:request :path-params :subsumer-id]))]
         (assoc ctx :result {:subsumedBy (hermes/subsumed-by? svc concept-id subsumer-id)})))}))

(defn parse-search-params
  [{:keys [s maxHits isA refset constraint ecl fuzzy fallbackFuzzy inactiveConcepts inactiveDescriptions removeDuplicates fsn]}]
  (cond-> {}
    s (assoc :s s)
    constraint (assoc :constraint constraint)
    ecl (assoc :constraint ecl)
    maxHits (assoc :max-hits (Long/parseLong maxHits))
    (string? isA) (assoc :properties {snomed/IsA (Long/parseLong isA)})
    (coll? isA) (assoc :properties {snomed/IsA (mapv #(Long/parseLong %) isA)})
    (string? refset) (assoc :concept-refsets [(Long/parseLong refset)])
    (coll? refset) (assoc :concept-refsets (mapv #(Long/parseLong %) refset))
    fuzzy (assoc :fuzzy (if (parse-flag fuzzy) 2 0))
    fallbackFuzzy (assoc :fallback-fuzzy (if (parse-flag fallbackFuzzy) 2 0))
    inactiveConcepts (assoc :inactive-concepts? (parse-flag inactiveConcepts))
    inactiveDescriptions (assoc :inactive-descriptions? (parse-flag inactiveDescriptions))
    removeDuplicates (assoc :remove-duplicates? (parse-flag removeDuplicates))
    fsn (assoc :show-fsn? (parse-flag fsn))))

(def get-search
  (intc/interceptor
    {:name ::get-search
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [params (parse-search-params (get-in ctx [:request :params]))
             max-hits (or (:max-hits params) 500)
             langs (get-in ctx [:request :headers "accept-language"])
             params' (if langs (assoc params :accept-language langs) params)]
         (if (< 0 max-hits 10000)
           (assoc ctx :result (or (hermes/search svc (assoc params' :max-hits max-hits)) []))
           (assoc ctx :response {:status 400 :body {:error "invalid parameter: maxHits"}}))))}))

(def get-expand
  (intc/interceptor
    {:name ::get-expand
     :enter
     (fn [{::keys [svc] :as ctx}]
       (let [ecl (get-in ctx [:request :params :ecl])
             preferred? (parse-flag (get-in ctx [:request :params :preferred]))
             dialect-id (some-> (get-in ctx [:request :params :dialectId]) parse-long vector)
             include-historic? (or (parse-flag (get-in ctx [:request :params :includeHistoric]))
                                   (parse-flag (get-in ctx [:request :params :include-historic])))] ; avoid breaking change - support legacy parameter
         (cond
           (str/blank? ecl)
           (assoc ctx :response {:status 400 :body {:error "missing parameter: ecl"}})
           (and include-historic? preferred?)               ;; while possible to implement, this combination would not make sense
           (assoc ctx :response {:status 400 :body {:error "invalid parameters: nonsensical use of both 'includeHistoric' and 'preferred'"}})
           include-historic?
           (assoc ctx :result (hermes/expand-ecl-historic svc ecl))
           preferred?
           (let [refset-ids (or dialect-id (take 1 (hermes/match-locale svc (get-in ctx [:request :headers "accept-language"]) true)))]
             (assoc ctx :result (hermes/expand-ecl* svc ecl refset-ids)))
           :else
           (assoc ctx :result (hermes/expand-ecl svc ecl)))))}))

(def get-mrcm-domains
  (intc/interceptor
    {:name ::get-mrcm-domains
     :enter
     (fn [{svc ::svc :as ctx}]
       (assoc ctx :result (hermes/mrcm-domains svc)))}))

(def routes
  #{["/v1/snomed/concepts/:concept-id" :get get-concept :constraints {:concept-id #"[0-9]+"}]
    ["/v1/snomed/concepts/:concept-id/descriptions" :get get-concept-descriptions :constraints {:concept-id #"[0-9]+"}]
    ["/v1/snomed/concepts/:concept-id/properties" :get get-concept-properties :constraints {:concept-id #"[0-9]+"}]
    ["/v1/snomed/concepts/:concept-id/preferred" :get get-concept-preferred-description :constraints {:concept-id #"[0-9]+"}]
    ["/v1/snomed/concepts/:concept-id/extended" :get get-extended-concept :constraints {:concept-id #"[0-9]+"}]
    ["/v1/snomed/concepts/:concept-id/historical" :get get-historical :constraints {:concept-id #"[0-9]+"}]
    ["/v1/snomed/concepts/:concept-id/refsets" :get get-concept-reference-sets :constraints {:concept-id #"[0-9]+"}]
    ["/v1/snomed/concepts/:concept-id/map/:refset-id" :get get-map-to :constraints {:concept-id #"[0-9]+" :refset-id #"[0-9]+"}]
    ["/v1/snomed/concepts/:concept-id/subsumed-by/:subsumer-id" :get subsumed-by? :constraints {:concept-id #"[0-9]+" :subsumer-id #"[0-9]+"}]
    ["/v1/snomed/crossmap/:refset-id/:code" :get get-map-from :constraints {:refset-id #"[0-9]+"}]
    ["/v1/snomed/search" :get get-search]
    ["/v1/snomed/expand" :get get-expand]
    ["/v1/snomed/mrcm-domains" :get get-mrcm-domains]})


(defn create-connector
  "Create a HTTP SNOMED server.
  Parameters:
  - svc             : Hermes service
  - port            : (optional) port to use, default 8080
  - bind-address    : (optional) bind address
  - allowed-origins : (optional) a sequence of strings of hostnames or function
  - join?           : whether to join server thread or return"
  [svc {:keys [port bind-address allowed-origins join?]
        :or   {port 8080, join? true, bind-address "localhost"}}]
  (-> (conn/default-connector-map bind-address port)
      (conn/optionally-with-dev-mode-interceptors)
      (conn/with-interceptors
        [log-request
         (cors/allow-origin allowed-origins)
         interceptors/not-found
         (inject-svc svc)
         route/query-params
         route/path-params-decoder
         (sec-headers/secure-headers {})
         coerce-body
         service-error-handler
         content-neg-intc
         entity-render])
      (conn/with-routes routes)
      (jetty/create-connector {:join? join?})))

(defn start! [svc config]
  (conn/start! (create-connector svc config)))

(defn stop! [conn]
  (conn/stop! conn))

;; For interactive development
(defonce server (atom nil))

(defn start-dev [svc port]
  (reset! server (start! svc {:port port :join? false})))

(defn stop-dev []
  (stop! @server))

(comment
  (def svc (hermes/open "snomed.db"))
  (start-dev svc 8080)
  (stop-dev))
