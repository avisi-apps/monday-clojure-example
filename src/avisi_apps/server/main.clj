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
            [clj-http.util :as http-util]
            [clojure.string :as string])
  (:import (org.eclipse.jetty.server Server)))

(defonce dev-env {:user-id 16227277
                  :board-id 749796977
                  :item-id 964580336})

(declare gitlab-query)

(declare fetch-gitlab-access-token)

(def base-url "https://fatih.eu.ngrok.io")

(def gitlab-base-url "https://gitlab.com")

(def gitlab-api-url (str gitlab-base-url "/api/v4"))

(defn gitlab-gid [id]
  (str "gid://gitlab/Issue/" id))

(defn parse-int [s]
  (try (Integer/parseInt s)
       (catch Exception _
         nil)))

(defonce oauth-tokens (atom {}))

(defonce subscriptions (atom {}))

(defonce config-storage (atom {}))

(defn store-subscription [{:keys [subscriptionId recipeId webhookUrl inputFields project-id webhook-id]}]
  (let [gitlab-project (get-in inputFields [:gitlabProject :value])]
    (swap! subscriptions assoc project-id {:webhook-url webhookUrl
                                           :webhook-id webhook-id
                                           :subscription-id subscriptionId
                                           :gitlab-project gitlab-project
                                           :recipe-id recipeId})))

(defn remove-subscription [project-id]
  (swap! subscriptions dissoc project-id))

(defn fetch-subscription [project-id]
  (get @subscriptions project-id))

(defn store-config [ks v]
  (swap! config-storage assoc-in ks v))

(defn remove-config [k]
  (swap! config-storage dissoc k))

(defn fetch-config [ks]
  (get-in @config-storage ks))

(defonce app-config (read-config "config.edn"))

(defn unsign-claims [state]
  (jwt/unsign state (:monday/signing-secret app-config)))

(defn state->user-id [state]
  (:userId (unsign-claims state)))

(defn state->back-to-url [state]
  (:backToUrl (unsign-claims state)))

(defn store-token [{:keys [state token provider]}]
  (let [user-id (state->user-id state)]
    (swap! oauth-tokens assoc-in [provider user-id] token)))

(defn store-monday-oauth-tokens [env]
  (store-token (assoc env :provider :monday)))

(defn store-gitlab-oauth-tokens [env]
  (store-token (assoc env :provider :gitlab)))

(defn fetch-tokens [{:keys [user-id provider]}]
  (get-in @oauth-tokens [provider user-id]))

(defn fetch-monday-access-token [env]
  (:access_token (fetch-tokens (assoc env :provider :monday))))

(defn fetch-gitlab-access-token [env]
  (:access_token (fetch-tokens (assoc env :provider :gitlab))))

(defn redirect-response [location]
  {:status 302
   :headers {"location" location}})

(defn json-response [body]
  {:status 200
   :headers {"content-type" "application/json"}
   :body (json/write-value-as-bytes body)})

(defn monday-query [query access-token]
  (let [{:keys [body status]}
        (http/post
          "https://api.monday.com/v2"
          {:oauth-token access-token
           :content-type :json
           :form-params {:query (query->graphql query)}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status}))

(defn gitlab-query [query access-token]
  (let [{:keys [body status]}
        (http/post
          (str gitlab-base-url "/api/graphql")
          {:oauth-token access-token
           :content-type :json
           :form-params {:query (query->graphql query)}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status}))

(defn handle-gitlab-projects [{:keys [payload user-id] :as env}]
  (let [{:keys [inputFields]} payload
        projects (-> (gitlab-query [(list {:projects
                                            [{:nodes [:fullPath]}]}
                                       {:membership true})]
                        (fetch-gitlab-access-token env))
                   (get-in [:body :data :projects :nodes]))]
    {:status 200
     :headers {"content-type" "application/json"}
     :body
     (json/write-value-as-bytes (mapv (fn [{:keys [fullPath]}]
                                        {:id fullPath
                                         :value fullPath})
                                  projects))}))

(defn handle-gitlab-create-issue [{:keys [payload user-id] :as env}]
  (let [{:keys [inputFields]} payload
        {:keys [itemId gitlabProject]} inputFields
        item-name (-> (monday-query [(list {:items [:name]}
                                       {:ids [itemId]})]
                        (fetch-monday-access-token env))
                    (get-in [:body :data :items 0 :name]))
        {:keys [body status]} (-> (gitlab-query [{(list 'createIssue
                                                    {:input {:projectPath (:value gitlabProject)
                                                             :title item-name}})
                                                  [{:issue [:id]}]}]
                                    (fetch-gitlab-access-token env))
                                :body)]
    {:status status
     :body {:result "done"}}))

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
       :client_id (:monday/client-id app-config)})))

(defn fetch-monday-oauth-token [code]
  (let [{:keys [body status]}
        (http/post
          "https://auth.monday.com/oauth2/token"
          {:content-type :json
           :query-params {:client_id (:monday/client-id app-config)
                          :client_secret (:monday/client-secret app-config)
                          :code code
                          :redirect_uri (str base-url "/oauth/callback")}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status}))

(defn fetch-gitlab-oauth-token [code]
  (let [{:keys [body status]}
        (http/post
          "https://gitlab.com/oauth/token"
          {:content-type :json
           :query-params {:client_id (:gitlab/application-id app-config)
                          :client_secret (:gitlab/secret app-config)
                          :code code
                          :redirect_uri (str base-url "/gitlab/oauth/callback")
                          :grant_type "authorization_code"}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status}))

(defn gitlab-authorize-url [state]
  (str "https://gitlab.com/oauth/authorize?"
    (http/generate-query-string
      {:client_id (:gitlab/application-id app-config)
       :redirect_uri (str base-url "/gitlab/oauth/callback")
       :scopes "api" ; grants complete read/write access
       :response_type "code"
       :state state})))

(defn gitlab-create-webhook [{:keys [payload] :as env}]
  (let [project (-> (get-in payload [:inputFields :gitlabProject :value])
                  (http-util/url-encode))
        body (-> (http/post (str gitlab-api-url "/projects/" project "/hooks")
                                {:oauth-token (fetch-gitlab-access-token env)
                                 :content-type :json
                                 :form-params {:issues_events true
                                               ;:push_events_branch_filter "develop"
                                               :url (str base-url "/webhooks/gitlab/issue-created")}})
               :body
               (json/read-value (json/object-mapper {:decode-key-fn true})))]
    {:project-id (:project_id body)
     :webhook-id (:webhook-id body)}))

(defn gitlab-remove-webhook [{:keys [payload webhook-id] :as env}]
  (let [project (-> (get-in payload [:inputFields :gitlabProject :value])
                  (http-util/url-encode))]
    (http/delete (str gitlab-api-url "/projects/" project "/hooks/" webhook-id)
      {:oauth-token (fetch-gitlab-access-token env)})))

(defn handle-gitlab-issue-created-webhook [{:keys [body] :as env}]
  (let [project-id (get-in body [:project :id])
        issue-id (get-in body [:object_attributes :id])
        {:keys [webhook-url]} (fetch-subscription project-id)]
    (http/post webhook-url
      {:headers {"authorization" (:monday/signing-secret app-config)}
       :content-type :json
       :form-params {"trigger" {"outputFields" {"gitLabIssueId" (gitlab-gid issue-id)}}}})
    {:status 204}))

(defn monday-item-by-id [{:keys [item-id] :as env}]
  (-> (monday-query [(list {:items [:id :name]}
                       {:ids [item-id]})]
        (fetch-monday-access-token env))
    (get-in [:body :data :items 0])))

(defn gitlab-issue-by-id [{:keys [gitlab-issue-id board-id] :as env}]
  (-> (gitlab-query
        [(list {:issue [:id :title]}
           {:id gitlab-issue-id})]
        (fetch-gitlab-access-token env))
    (get-in [:body :data :issue])))

(defn create-monday-item [{:keys [gitlab-issue-id board-id] :as env}]
  (let [{:keys [title]} (gitlab-issue-by-id env)
        item-id (-> (monday-query
                      [{(list 'create_item
                          {:board_id board-id
                           :item_name title})
                        [:id]}]
                      (fetch-monday-access-token env))
                  (get-in [:body :data :create_item :id]))]
    (store-config [:create-n-sync gitlab-issue-id] {:item-id (parse-int item-id)
                                                    :gitlab-issue-id gitlab-issue-id})))

(defn update-monday-item [{:keys [board-id item-id new-columns] :as env}]
  (monday-query
    [{(list 'change_multiple_column_values
        {:item_id item-id
         :board_id board-id
         :column_values (json/write-value-as-string new-columns)})
      [:id]}]
    (fetch-monday-access-token env)))

(defn sync-monday-item-with-gitlab-issue [{:keys [board-id item-id new-columns] :as env}]
  (let [{:keys [title]} (gitlab-issue-by-id env)]
    (update-monday-item (assoc env :new-columns {:name title}))))

(defn handle-create-n-sync-item [{:keys [payload] :as env}]
  (let [{:keys [gitLabIssueId boardId]} (:inputFields payload)
        item-id (fetch-config [:create-n-sync gitLabIssueId :item-id])
        env (assoc env :gitlab-issue-id gitLabIssueId
                       :board-id boardId
                       :item-id item-id)]
    (cond
      ;; TODO Error handling: Item exist but pulse doesn't exist
      item-id (sync-monday-item-with-gitlab-issue env)
      :else (create-monday-item env))
    ;; Update Item
    {:status 204}))

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
                             (handle-gitlab-projects {:payload payload
                                                      :user-id (get-in request [:jwt :userId])}))}}]]
        ["/action"
         ["/gitlab"
          ["/create-issue"
           {:post {:parameters {:body [:map]}
                   :responses {200 {:body [any?]}}
                   :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                              (log/info "create gitlab issue")
                              (handle-gitlab-create-issue {:payload payload
                                                           :user-id (get-in request [:jwt :userId])}))}}]
          ["/create-n-sync-item"
           {:post {:parameters {:body [:map]}
                   :responses {200 {:body [any?]}}
                   :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                              (log/info "create-n-sync-item")

                              (handle-create-n-sync-item {:payload payload
                                                          :user-id (get-in request [:jwt :userId])}))}}]]]
        ["/subscribe"
         ["/gitlab"
          ["/issue-created" {:post {:parameters {:body [:map]}
                                    :responses {200 {:body [any?]}}
                                    :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                                               (log/info "subscribe gitlab create issue")
                                               (let [{:keys [project-id webhook-id]} (gitlab-create-webhook {:payload payload
                                                                                                             :user-id (get-in request [:jwt :userId])})]
                                                 (store-subscription (assoc payload :project-id project-id :webhook-id webhook-id))
                                                 (json-response {:webhookId project-id})))}}]]]
        ["/unsubscribe"
         ["/gitlab"
          ["/issue-created" {:post {:parameters {:body [:map]}
                                    :responses {200 {:body [any?]}}
                                    :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                                               (log/info "unsubscribe gitlab create issue")
                                               (let [project-id (:webhookId payload)
                                                     webhook-id (:webhook-id (fetch-subscription project-id))]
                                                 (remove-subscription project-id)
                                                 (gitlab-remove-webhook {:payload payload
                                                                         :webhook-id webhook-id})
                                                 {:status 204}))}}]]]]
       ["/webhooks"
        ["/gitlab"
         ["/issue-created" {:post {:parameters {:body [:map]}
                                   :responses {200 {:body [any?]}}
                                   :handler (fn [{{:keys [body]} :parameters :as request}]
                                              (log/info "webhook gitlab create issue")
                                              (handle-gitlab-issue-created-webhook {:body body
                                                                                    :user-id (get-in request [:jwt :userId])}))}}]]]
       ["/authorization"
        {:get {:handler (fn [request]
                          (let [{:keys [params]} request
                                {:strs [token]} params
                                env {:user-id (state->user-id token)}
                                monday-authorized? (some? (fetch-monday-access-token env))
                                gitlab-authorized? (some? (fetch-gitlab-access-token env))]
                            (redirect-response (cond
                                                 (and gitlab-authorized? monday-authorized?) (state->back-to-url token)
                                                 monday-authorized? (gitlab-authorize-url token)
                                                 :else (monday-authorize-url token)))
                            #_{:status 200
                             :body "<html>\n       <meta charset=\"utf-8\">\n       <body>\n       <div id=\"app\"></div>\n       <script src=\"/js/board-view.js\"></script>\n       </body>\n       </html>"}))}}]

       ["/oauth/callback"
        {:get {:handler (fn [request]
                          (let [{:keys [params]} request
                                {:strs [code state]} params
                                monday-oauth-token (:body (fetch-monday-oauth-token code))
                                _ (store-monday-oauth-tokens {:state state :token monday-oauth-token})
                                gitlab-auth-url (gitlab-authorize-url state)]
                            (redirect-response gitlab-auth-url)))}}]
       ["/gitlab"
        ["/oauth/callback"
         {:get {:handler (fn [request]
                           (let [{:keys [params]} request
                                 {:strs [code state]} params
                                 gitlab-token (:body (fetch-gitlab-oauth-token code))
                                 _ (store-gitlab-oauth-tokens {:state state :token gitlab-token})]
                             (redirect-response (state->back-to-url state))))}}]]]
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
               app-config)})
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

  ;; user-id
  ;; 16227277

  (monday-query [(list {:items [:id :name
                                (list {:column_values [:id]}
                                  {:ids "text"})]}
                   {:ids [749796988]})]
    (get-in @oauth-tokens [:monday 16227277 :access_token]))

  (monday-query [(list {:items [:id :name]}
                   {:ids [749796988]})]
    (get-in @oauth-tokens [:monday 16227277 :access_token]))

  (monday-query [{(list 'change_column_value
                    {:item_id 749796988
                     :column_id "text2"
                     :board_id 749796977
                     :value (json/write-value-as-string "This update will be added to the item")})
                  [:id]}]
    (get-in @oauth-tokens [:monday 16227277 :access_token]))

  (gitlab-query [{:currentUser
                  [:id :name]}]
    (get-in @oauth-tokens [:gitlab 16227277 :access_token]))

  (->> (gitlab-query [(list {:projects
                            [{:nodes [:fullPath]}]}
                       {:membership true})]
         (get-in @oauth-tokens [:gitlab 16227277 :access_token]))
    :body
    :data
    :projects
    :nodes
    (mapv :fullPath))

  (-> (gitlab-query [{(list 'createIssue
                       {:input {:projectPath "fatihict/fatih-test-private-repo"
                                :title "Clojure graphql!"}})
                      [{:issue [:id]}]}]
        (get-in @oauth-tokens [:gitlab 16227277 :access_token]))
    :body)

  (query->graphql [{:currentUser
                    [:id :name]}])


  (gitlab-query [(list {:issue [:id :title]}
                    {:id "gid://gitlab/Issue/77098853"})]
    (get-in @oauth-tokens [:gitlab 16227277 :access_token]))


  (let [query [(list {:items [:id :name]}
                 {:ids [749796988]})]
        {:keys [body status]}
        (http/post
          "https://api.monday.com/v2"
          {:oauth-token (get-in @oauth-tokens [:monday 16227277 :access_token])
           :content-type :json
           :form-params {:query (query->graphql query)}})]
    {:body (json/read-value body (json/object-mapper {:decode-key-fn true}))
     :status status})

  (let [id "fatihict%2Ffatih-test-private-repo"
        id-encoded (http/url-encode-illegal-characters id)
        {:keys [body status]} (http/post (str gitlab-api-url "/projects/" id-encoded "/hooks")
                                {:oauth-token (get-in @oauth-tokens [:gitlab 16227277 :access_token])
                                 :content-type :json
                                 :form-params {:issues_events true
                                               ;:push_events_branch_filter "develop"
                                               :url (str base-url "/webhooks/gitlab/issue-created")}})]
    (json/read-value body (json/object-mapper {:decode-key-fn true})))

  (let [id "fatihict/fatih-test-private-repo"
        id-encoded (http/url-encode-illegal-characters id)]
    (http/get (str gitlab-api-url "/projects/" id-encoded "/hooks")
      {:oauth-token (get-in @oauth-tokens [:gitlab 16227277 :access_token])}))

  (http-util/url-encode "fatihict/fatih-test-private-repo")

  )


