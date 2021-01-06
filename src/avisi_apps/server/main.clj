(ns avisi-apps.server.main
  (:require [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            reitit.coercion.malli
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [buddy.sign.jwt :as jwt]
            [aero.core :refer [read-config]]
            [reitit.ring.coercion :as rrc]
            [clojure.tools.logging :as log]
            [muuntaja.core :as m]
            [malli.util :as mu]
            [clojure.spec.alpha :as s]
            [mount.core :refer [defstate]]
            [jsonista.core :as json]
            [com.wsscode.pathom.graphql :refer [query->graphql]]
            [clj-http.client :as http]
            [clojure.string :as string])
  (:import (org.eclipse.jetty.server Server)))

(defn monday-query [query]
  (let [{:keys [body status]}
        (http/post
          "https://api.monday.com/v2"
          {:oauth-token (:monday/api-token (read-config "config.edn"))
           :content-type :json
           :form-params {:query (query->graphql query)}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status}))

(defn handle-integration [payload]
  ;(log/info "Got payload" payload)
  {:status 200
   :body {:result "done"}})

(defn handle-text-transformer [payload]
  (let [{:keys [inputFields]} payload
        {:keys [sourceColumnId boardId targetColumnId itemId]} inputFields]
    (def _payload payload)
    (let [source-column (-> (monday-query [(list {:items [:id :name
                                                          (list {:column_values [:text]}
                                                            {:ids sourceColumnId})]}
                                             {:ids [itemId]})])
                          :body
                          :data
                          :items
                          first
                          :column_values
                          first
                          :text)
          {:keys [body status]}
          (monday-query [{(list 'change_column_value
                              {:item_id itemId
                               :column_id targetColumnId
                               :board_id boardId
                               :value (json/write-value-as-string (string/upper-case source-column))})
                            [:id]}])]
      {:status status
       :body {:result "done"}})))

(def monday-authentication-middleware
  {:name ::monday-authentication
   :spec (s/keys :req [:monday/client-secret])
   :compile (fn [route-data _]
              (fn [handler]
                (fn [request]
                  (handler
                    (assoc request :jwt
                           (jwt/unsign
                             (get (:headers request) "authorization")
                             (:monday/signing-secret route-data)))))))})

(comment
  (re-find)
  )

(def app
  (ring/ring-handler
    ;; Routes
    (ring/router
      [["/integrations"
        ["/action"
         {:middleware [[monday-authentication-middleware]]}
         ["/text-transformer"
          {:post {:parameters {:body [:map]}
                  :responses {200 {:body [:map]}}
                  :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                             (def _request request)
                             (handle-text-transformer payload))}}]
         ["/create-page" {:post {:parameters {:body [:map]}
                                 :responses {200 {:body [:map [:result string?]]}}
                                 :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                                            (def _request request)
                                            (handle-integration payload))}}]]]
       ["/api"
        ["/math" {:get {:parameters {:query [:map [:x int?] [:y int?]]}
                        :responses {200 {:body [:map [:total int?]]}}
                        :handler (fn [{{{:keys [x y]} :query} :parameters}]
                                   {:status 200
                                    :body {:total (+ x y)}})}}]]]
      ;; router data effecting all routes
      {:data (merge {:muuntaja m/instance
                     :coercion (reitit.coercion.malli/create
                                 {;; set of keys to include in error messages
                                  :error-keys #{#_:type :coercion :in :schema :value :errors :humanized #_:transformed}
                                  ;; schema identity function (default: close all map schemas)
                                  :compile mu/open-schema
                                  ;; add/set default values
                                  :default-values true
                                  ;; malli options
                                  :options nil})
                     :middleware [parameters/parameters-middleware
                                  muuntaja/format-middleware
                                  rrc/coerce-exceptions-middleware
                                  rrc/coerce-request-middleware
                                  rrc/coerce-response-middleware]}
                    (read-config "config.edn"))})
    ;; Default handler
    (ring/routes
      (ring/create-resource-handler {:path "/"})
      (ring/create-default-handler))))

(defstate server
  :start (do
           (println "Server starting on port 3000")
           (jetty/run-jetty #'app {:port 3000, :join? false}))
  :stop (.stop ^Server server))

(comment
  (start)

  (jwt/unsign
    (get (:headers _request) "authorization")
    (:monday/signing-secret (read-config "config.edn")))

  ;api.monday.com/v2

  (monday-query [(list {:items [:id :name
                                (list {:column_values [:id]}
                                  {:ids "text"})]}
                   {:ids [749796988]})])

  (monday-query [(list {:items [:id :name]}
                   {:ids [749796988]})])

  (monday-query [{(list 'change_column_value
                    {:item_id 749796988
                     :column_id "text2"
                     :board_id 749796977
                     :value (json/write-value-as-string "This update will be added to the item")})
                  [:id]}])

  )


