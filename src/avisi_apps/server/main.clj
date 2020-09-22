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
            [clojure.spec.alpha :as s]))

(defn handle-integration [payload]
  (log/info "Got payload" payload)
  {:status 200
   :body {:result "done"}})

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
                             (:monday/client-secret route-data)))))))})

(def app
  (ring/ring-handler
    ;; Routes
    (ring/router
      [["/integrations"
        ["/action"
         {:middleware [[monday-authentication-middleware]]}
         ["/create-page" {:post {:parameters {:body [:map #_[:payload
                                                           [:map [:inboundFieldValues
                                                                  [:map [:itemId string?]]]]]]}
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

(defn start []
  (jetty/run-jetty #'app {:port 3000, :join? false})
  (println "server running in port 3000"))

(comment
  (start)

  (jwt/unsign
    (get (:headers _request) "authorization")
    (:monday/client-secret (read-config "config.edn")))

  _request


  )


