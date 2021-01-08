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
           ;:oauth-token (get-in _request [:jwt :shortLivedToken])
           :content-type :json
           :form-params {:query (query->graphql query)}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status}))

(defn gitlab-query [query oauth-token]
  (let [{:keys [body status]}
        (http/post
          "https://gitlab.com/api/graphql"
          {:oauth-token oauth-token
           :content-type :json
           :form-params {:query (query->graphql query)}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status}))

(defn handle-integration [payload]
  ;(log/info "Got payload" payload)
  {:status 200
   :body {:result "done"}})

(defn handle-gitlab-projects [payload]
  (let [{:keys [inputFields]} payload
        {:keys [sourceColumnId boardId targetColumnId itemId]} inputFields
        _ (def _gl-projects-payload payload)
        projects (->> (gitlab-query [(list {:projects
                                            [{:nodes [:fullPath]}]}
                                       {:membership true})]
                        (:access_token _gitlab-token))
                   :body
                   :data
                   :projects
                   :nodes
                   (mapv :fullPath))
        _ (def _gl-projects projects)
        body (mapv
               (fn [project]
                 {:id project
                  :title project
                  :outboundType "text"
                  :inboundTypes ["text"]})
               projects)
        _ (def _gl-body body)]
    {:status 200
     :headers {"content-type" "application/json"}
     :body
     (json/write-value-as-bytes (mapv (fn [project]
                                        {:id project
                                         :value project})
                                  projects))
     }))

(defn handle-text-transformer [payload]
  (let [{:keys [inputFields]} payload
        {:keys [sourceColumnId boardId targetColumnId itemId]} inputFields]
    (def _tt-payload payload)
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

(defn handle-gitlab-create-issue [payload]
  (let [{:keys [inputFields]} payload
        {:keys [itemId gitlabProjects]} inputFields]
    (def _gl-payload payload)
    (def _gl-item-id itemId)
    (let [item-name (-> (monday-query [(list {:items [:name]}
                                         {:ids [itemId]})])
                      :body
                      :data
                      :items
                      first
                      :name)
          {:keys [body status]} (-> (gitlab-query [{(list 'createIssue
                                                {:input {:projectPath (:value gitlabProjects)
                                                         :title item-name}})
                                              [{:issue [:id]}]}]
                                (:access_token _gitlab-token))
                            :body)]
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

(defn monday-authorize-url [token]
  (str "https://auth.monday.com/oauth2/authorize?"
    (http/generate-query-string
      {:state token
       :client_id (:monday/client-id (read-config "config.edn"))})))

(defn fetch-monday-oauth-token [code]
  (let [{:keys [body status]}
        (http/post
          "https://auth.monday.com/oauth2/token"
          {:content-type :json
           :query-params {:client_id (:monday/client-id (read-config "config.edn"))
                          :client_secret (:monday/client-secret (read-config "config.edn"))
                          :code code
                          :redirect_uri "https://fatih.eu.ngrok.io/oauth/callback"}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status}))

(defn fetch-gitlab-oauth-token [code]
  (let [{:keys [body status]}
        (http/post
          "https://gitlab.com/oauth/token"
          {:content-type :json
           :query-params {:client_id (:gitlab/application-id (read-config "config.edn"))
                          :client_secret (:gitlab/secret (read-config "config.edn"))
                          :code code
                          :redirect_uri "https://fatih.eu.ngrok.io/gitlab/oauth/callback"
                          :grant_type "authorization_code"}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status}))

(defn gitlab-authorize-url [state]
  (str "https://gitlab.com/oauth/authorize?"
    (http/generate-query-string
      {:client_id (:gitlab/application-id (read-config "config.edn"))
       :redirect_uri "https://fatih.eu.ngrok.io/gitlab/oauth/callback"
       ;:scopes "read_user+profile"
       :scopes "api" ; grants complete read/write access
       :response_type "code"
       :state state})))

(def app
  (ring/ring-handler
    ;; Routes
    (ring/router
      [["/integrations"
        {:middleware [[monday-authentication-middleware]]}
        ["/field-types"
         ["/gitlab-projects"
          {:post {:parameters {:body [:map]}
                  :responses {200 {:body [any?]}}
                  :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                             (log/info "gitlab-projects")
                             (def _gl-projects-request request)
                             (let [resp (handle-gitlab-projects payload)]
                               (def _gl-projects-respo resp)
                               resp)
                             )}}]]
        ["/action"
         ["/text-transformer"
          {:post {:parameters {:body [:map]}
                  :responses {200 {:body [:map]}}
                  :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                             (log/info "text transformer")
                             (def _request request)
                             (handle-text-transformer payload))}}]
         ["/create-gitlab-issue"
          {:post {:parameters {:body [:map]}
                  :responses {200 {:body [:map]}}
                  :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                             (def _create-issue-request request)
                             (def _create-issue-params (get-in _create-issue-request [:parameters :body :payload]))
                             (log/info "create gitlab issue")
                             (handle-gitlab-create-issue payload))}}]
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
                                    :body {:total (+ x y)}})}}]]
       ["/authorization"
        {:get {:handler (fn [request]
                          (let [{:keys [params]} request
                                {:strs [token]} params
                                _ (def _token token)
                                redirect-url (monday-authorize-url token)
                                _ (log/info "Redirecting to monday oauth url " redirect-url)]
                            (def _monday-request request)
                            {:status 302
                             :headers {"location" redirect-url}})
                          )}}]
       ["/oauth/callback"
        {:get {:handler (fn [request]
                          (def _req request)
                          (let [{:keys [params]} request
                                {:strs [code state]} params
                                _ (def _monday-oauth-callback request)
                                ;_ (def _code (get-in _monday-oauth-callback [:params "code"]))
                                ;; Fetch monday oauth token
                                monday-oauth-token (:monday (fetch-monday-oauth-token code))
                                _ (def _monday-oauth-token monday-oauth-token)
                                gitlab-auth-url (gitlab-authorize-url state)
                                _ (log/info "Redirecting to gitlab auth url " gitlab-auth-url)]
                            {:status 302
                             :headers {"location" gitlab-auth-url}}))}}]
       ["/gitlab"
        ["/oauth/callback"
         {:get {:handler (fn [request]
                           (def _req request)
                           (let [{:keys [params]} request
                                 {:strs [code state]} params
                                 _ (def _gitlab-oauth-callback request)
                                 _ (def _state (get-in _gitlab-oauth-callback [:params "state"]))
                                 {:keys [backToUrl]} (jwt/unsign state (:monday/signing-secret (read-config "config.edn")))
                                 gitlab-token (:body (fetch-gitlab-oauth-token code))
                                 _ (log/info "Fetched gitlab oauth token")
                                 _ (def _gitlab-token gitlab-token)]
                             ;; redirect to backtourl monday
                             {:status 302
                              :headers {"location" backToUrl}}))}}]]]
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

  (str "https://gitlab.com/oauth/authorize?"
    (http/generate-query-string
      {:client_id (:gitlab/application-id (read-config "config.edn"))
       :redirect_uri "https://fatih.eu.ngrok.io/gitlab/oauth/callback"
       :scopes "read_user+profile"
       :response_type "code"}))

  (jwt/unsign
    ;(get (:headers _request) "authorization")
    _token
    (:monday/signing-secret (read-config "config.edn")))

  (let [{:keys [body status]}
        (http/post
          "https://gitlab.com/oauth/token"
          {:content-type :json
           :query-params {:client_id (:gitlab/application-id (read-config "config.edn"))
                          :client_secret (:gitlab/secret (read-config "config.edn"))
                          :code (get _params "code")
                          :redirect_uri "https://fatih.eu.ngrok.io/gitlab/oauth/callback"
                          :grant_type "authorization_code"}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status})

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

  _gitlab-token

  (gitlab-query [{:currentUser
                  [:id :name]}]
    (:access_token _gitlab-token))

  (->> (gitlab-query [(list {:projects
                            [{:nodes [:fullPath]}]}
                       {:membership true})]
        (:access_token _gitlab-token))
    :body
    :data
    :projects
    :nodes
    (mapv :fullPath))

  (-> (gitlab-query [{(list 'createIssue
                       {:input {:projectPath "fatihict/fatih-test-private-repo"
                                :title "Clojure graphql!"}})
                      [{:issue [:id]}]}]
        (:access_token _gitlab-token))
    :body)

  ;createIssue(input: {projectPath: "fatihict/fatih-test-private-repo", title: "Fatih issue"}) {
  ;issue {
  ;       id
  ;       }
  ;}

  (query->graphql [{:currentUser
                    [:id :name]}])

  )


