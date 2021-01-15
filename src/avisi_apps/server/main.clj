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

(def issue-created-webhook-subscription-type :create-n-sync)
(def commit-pushed-webhook-subscription-type :commit-pushed)

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

;; TODO, clear this after removing a subscription?
(defonce config-storage (atom {}))

(comment
  @subscriptions

  @config-storage

  )

(defn store-subscription [{:keys [subscriptionId recipeId webhookUrl inputFields subscription-type project-id webhook-id]}]
  (let [gitlab-project (get-in inputFields [:gitlabProject :value])]
    (swap! subscriptions assoc (str subscription-type "-" project-id) {:webhook-url webhookUrl
                                                                  :webhook-id webhook-id
                                                                  :subscription-id subscriptionId
                                                                  :gitlab-project gitlab-project
                                                                  :recipe-id recipeId})))

(defn remove-subscription [subscriptions-type project-id]
  (swap! subscriptions dissoc (str subscriptions-type "-" project-id)))

(defn fetch-subscription [subscriptions-type project-id]
  (get @subscriptions (str subscriptions-type "-" project-id)))

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

(defn monday-board-columns [{:keys [board-id] :as env}]
  (-> (monday-query [(list {:boards [:id :name
                                     {:columns [:id
                                                :title
                                                :type]}]}
                   {:ids [board-id]})]
    (fetch-monday-access-token env))
    (get-in [:body :data :boards 0 :columns])))

(defn monday-board-text-columns [env]
  (filter
    #(contains? #{"text" "long-text" "name"} (:type %))
    (monday-board-columns env)))

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
                                 :form-params {:push_events true
                                               ;:push_events_branch_filter "develop"
                                               :url (str base-url "/webhooks/gitlab/issue-created")}})
               :body
               (json/read-value (json/object-mapper {:decode-key-fn true})))]
    {:project-id (:project_id body)
     :webhook-id (:id body)}))

(defn gitlab-commit-pushed-webhook [{:keys [payload] :as env}]
  (let [project (-> (get-in payload [:inputFields :gitlabProject :value])
                  (http-util/url-encode))
        body (-> (http/post (str gitlab-api-url "/projects/" project "/hooks")
                   {:oauth-token (fetch-gitlab-access-token env)
                    :content-type :json
                    :form-params {:issues_events true
                                  ;:push_events_branch_filter "develop"
                                  :url (str base-url "/webhooks/gitlab/commit-pushed")}})
               :body
               (json/read-value (json/object-mapper {:decode-key-fn true})))]
    {:project-id (:project_id body)
     :webhook-id (:id body)}))

(defn gitlab-remove-webhook [{:keys [payload project-id webhook-id] :as env}]
  (http/delete (str gitlab-api-url "/projects/" project-id "/hooks/" webhook-id)
    {:oauth-token (fetch-gitlab-access-token env)}))

(defn handle-gitlab-issue-created-webhook [{:keys [body] :as env}]
  (let [project (:project body)
        issue (:object_attributes body)
        user (:user body)
        issue-id (:id issue)
        {:keys [webhook-url]} (fetch-subscription issue-created-webhook-subscription-type (:id project))]
    (http/post webhook-url
      {:headers {"authorization" (:monday/signing-secret app-config)}
       :content-type :json
       :form-params {"trigger" {"outputFields" {"gitlabIssueId" (gitlab-gid issue-id)
                                                "gitlabIssue" {"title" (:title issue)
                                                               "description" (:description issue)
                                                               "authorName" (:name user)
                                                               "authorUsername" (:username user)}}}}})
    {:status 204}))

(defn handle-gitlab-commit-pushed-webhook [{:keys [body] :as env}]
  (let [project (:project body)
        issue (:object_attributes body)
        latest-commit (get-in body [:commits 0])
        commit-author (:author latest-commit)
        issue-id (:id issue)
        {:keys [webhook-url]} (fetch-subscription commit-pushed-webhook-subscription-type (:id project))]
    (http/post webhook-url
      {:headers {"authorization" (:monday/signing-secret app-config)}
       :content-type :json
       :form-params {"trigger" {"outputFields" {"gitlabIssueId" (gitlab-gid issue-id)
                                                "gitlabCommit" {"commitTitle" (:title latest-commit)
                                                                "commitMessage" (:message latest-commit)
                                                                "commitAuthorName" (:name commit-author)
                                                                "commitAuthorEmail" (:email commit-author)}}}}})
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

(defn create-monday-item-update [{:keys [item-id body] :as env}]
  (monday-query
    [{(list 'create_update
        {:item_id item-id
         :body body})
      [:id]}]
    (fetch-monday-access-token env))
  {:status 204})

(defn create-monday-item [{:keys [gitlab-issue-id board-id column-mapping subscription-type title] :as env}]
  (let [item-id (-> (monday-query
                      [{(list 'create_item
                          {:board_id board-id
                           :item_name title
                           :column_values (json/write-value-as-string column-mapping)})
                        [:id]}]
                      (fetch-monday-access-token env))
                  (get-in [:body :data :create_item :id]))]
    (store-config [subscription-type gitlab-issue-id] {:item-id (parse-int item-id)
                                                       :gitlab-issue-id gitlab-issue-id})))

(defn update-monday-item [{:keys [board-id item-id column-mapping] :as env}]
  (monday-query
    [{(list 'change_multiple_column_values
        {:item_id item-id
         :board_id board-id
         :column_values (json/write-value-as-string column-mapping)})
      [:id]}]
    (fetch-monday-access-token env)))

(defn handle-column-mapping [{:keys [payload] :as env}]
  (let [{:keys [inputFields]} payload
        env (assoc env :board-id (:boardId payload))
        columns (monday-board-text-columns env)
        res (mapv (fn [{:keys [id title]}]
                    {:id id
                     :title title
                     :outboundType "text"
                     :inboundTypes ["text"]})
              columns)]
    {:status 200
     :headers {"content-type" "application/json"}
     :body
     (json/write-value-as-bytes res)}))

(defn handle-gitlab-commit [env]
  {:status 200
   :headers {"content-type" "application/json"}
   :body
   (json/write-value-as-bytes (mapv
                                (fn [{:keys [id title]}]
                                  {:id id
                                   :title title
                                   :outboundType "text"
                                   :inboundTypes ["text"]})
                                [{:id "commitTitle"
                                  :title "Commit Title"}
                                 {:id "commitMessage"
                                  :title "Commit Message"}
                                 {:id "commitAuthorName"
                                  :title "Commit Author Name"}
                                 {:id "commitAuthorEmail"
                                  :title "Commit Author Email"}]))})

(defn handle-gitlab-issue [env]
  {:status 200
   :headers {"content-type" "application/json"}
   :body
   (json/write-value-as-bytes (mapv
                                (fn [{:keys [id title]}]
                                  {:id id
                                   :title title
                                   :outboundType "text"
                                   :inboundTypes ["text"]})
                                [{:id "title"
                                  :title "Title"}
                                 {:id "description"
                                  :title "Description"}
                                 {:id "authorName"
                                  :title "Author Name"}
                                 {:id "authorUsername"
                                  :title "Author Username"}]))})

(defn handle-gitlab-create-n-sync-item [{:keys [payload] :as env}]
  (let [{:keys [gitlabIssueId boardId columnMapping]} (:inputFields payload)
        item-id (fetch-config [issue-created-webhook-subscription-type gitlabIssueId :item-id])
        env (assoc env :gitlab-issue-id gitlabIssueId
                       :column-mapping columnMapping
                       :board-id boardId
                       :item-id item-id
                       :subscription-type issue-created-webhook-subscription-type
                       :title (:name columnMapping))]
    (cond
      ;; TODO Error handling: Item exist but pulse doesn't exist
      item-id (update-monday-item env)
      :else (create-monday-item env))
    ;; Update Item
    {:status 204}))

(defn handle-gitlab-create-item [{:keys [payload] :as env}]
  (let [{:keys [gitlabIssueId boardId columnMapping]} (:inputFields payload)
        item-id (fetch-config [issue-created-webhook-subscription-type gitlabIssueId :item-id])
        env (assoc env :gitlab-issue-id gitlabIssueId
                       :column-mapping columnMapping
                       :board-id boardId
                       :item-id item-id
                       :subscription-type issue-created-webhook-subscription-type
                       :title (:name columnMapping))]
    (try
      (create-monday-item env)
      (catch Exception e
        (log/debug "create item failed" e)))
    ;; Update Item
    {:status 204}))

(defn handle-gitlab-item-update [{:keys [payload] :as env}]
  (let [{:keys [gitlabIssueId boardId gitlabCommit itemId]} (:inputFields payload)
        {:keys [commitTitle commitAuthorName]} gitlabCommit
        env (assoc env :gitlab-issue-id gitlabIssueId
                       :board-id boardId
                       :body (str "Commit pushed with title \"" commitTitle "\"" " by author " commitAuthorName)
                       :item-id itemId)]
    (try
      (create-monday-item-update env)
      (catch Exception e
        (log/debug "item update failed" e)))
    ;; Update Item
    {:status 204}))

(def app
  (ring/ring-handler
    ;; Routes
    (ring/router
      [["/integrations"
        {:middleware [[monday-authentication-middleware]]}
        ["/field-types"
         ;; TODO make field definition
         ["/gitlab-issue"
          {:post {:parameters {:body [:map]}
                  :responses {200 {:body [any?]}}
                  :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                             (log/info "gitlab-issue")
                             (handle-gitlab-issue {:payload payload
                                                   :user-id (get-in request [:jwt :userId])}))}}]
         ["/gitlab-commit"
          {:post {:parameters {:body [:map]}
                  :responses {200 {:body [any?]}}
                  :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                             (log/info "gitlab-commit")
                             (handle-gitlab-commit {:payload payload
                                                   :user-id (get-in request [:jwt :userId])}))}}]
         ["/gitlab-projects"
          {:post {:parameters {:body [:map]}
                  :responses {200 {:body [any?]}}
                  :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                             (log/info "gitlab-projects")
                             (handle-gitlab-projects {:payload payload
                                                      :user-id (get-in request [:jwt :userId])}))}}]
         ["/column-mapping"
          {:post {:parameters {:body [:map]}
                  :responses {200 {:body [any?]}}
                  :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                             (log/info "column mapping")
                             (handle-column-mapping {:payload payload
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
                              (log/info "create-n-sync-gitlab-item")
                              (handle-gitlab-create-n-sync-item {:payload payload
                                                          :user-id (get-in request [:jwt :userId])}))}}]
          ["/create-mapped-item"
           {:post {:parameters {:body [:map]}
                   :responses {200 {:body [any?]}}
                   :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                              (log/info "create mapped gitlab item")
                              (handle-gitlab-create-item {:payload payload
                                                          :user-id (get-in request [:jwt :userId])}))}}]
          ["/create-item-update"
           {:post {:parameters {:body [:map]}
                   :responses {200 {:body [any?]}}
                   :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                              (log/info "create item update")
                              (handle-gitlab-item-update {:payload payload
                                                          :user-id (get-in request [:jwt :userId])}))}}]]]
        ["/subscribe"
         ["/gitlab"
          ["/issue-created" {:post {:parameters {:body [:map]}
                                    :responses {200 {:body [any?]}}
                                    :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                                               (log/info "subscribe gitlab create issue")
                                               (let [{:keys [project-id webhook-id]} (gitlab-create-webhook {:payload payload
                                                                                                             :user-id (get-in request [:jwt :userId])})]
                                                 (store-subscription (assoc payload :project-id project-id
                                                                                    :webhook-id webhook-id
                                                                                    :subscription-type issue-created-webhook-subscription-type))
                                                 (json-response {:webhookId (str issue-created-webhook-subscription-type "-" project-id)})))}}]
          ["/commit-pushed" {:post {:parameters {:body [:map]}
                                    :responses {200 {:body [any?]}}
                                    :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                                               (log/info "subscribe gitlab commit pushed")
                                               (let [{:keys [project-id webhook-id]} (gitlab-commit-pushed-webhook {:payload payload
                                                                                                                    :user-id (get-in request [:jwt :userId])})]
                                                 (store-subscription (assoc payload :project-id project-id
                                                                                    :webhook-id webhook-id
                                                                                    :subscription-type commit-pushed-webhook-subscription-type))
                                                 (json-response {:webhookId (str commit-pushed-webhook-subscription-type "-" project-id)})))}}]]]
        ["/unsubscribe"
         ["/gitlab"
          ["/issue-created" {:post {:parameters {:body [:map]}
                                    :responses {200 {:body [any?]}}
                                    :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                                               (log/info "unsubscribe gitlab create issue")
                                               (let [project-id (:webhookId payload)
                                                     webhook-id (:webhook-id (fetch-subscription issue-created-webhook-subscription-type project-id))]
                                                 (remove-subscription issue-created-webhook-subscription-type project-id)
                                                 (gitlab-remove-webhook {:payload payload
                                                                         :project-id project-id
                                                                         :webhook-id webhook-id
                                                                         :user-id (get-in request [:jwt :userId])})
                                                 {:status 204}))}}]
          ["/commit-pushed" {:post {:parameters {:body [:map]}
                                    :responses {200 {:body [any?]}}
                                    :handler (fn [{{{:keys [payload]} :body} :parameters :as request}]
                                               (log/info "unsubscribe gitlab commit pushed")
                                               (let [project-id (:webhookId payload)
                                                     webhook-id (:webhook-id (fetch-subscription commit-pushed-webhook-subscription-type project-id))]
                                                 (remove-subscription commit-pushed-webhook-subscription-type project-id)
                                                 (gitlab-remove-webhook {:payload payload
                                                                         :project-id project-id
                                                                         :webhook-id webhook-id
                                                                         :user-id (get-in request [:jwt :userId])})
                                                 {:status 204}))}}]]]]
       ["/webhooks"
        ["/gitlab"
         ["/issue-created" {:post {:parameters {:body [:map]}
                                   :responses {200 {:body [any?]}}
                                   :handler (fn [{{:keys [body]} :parameters :as request}]
                                              (log/info "webhook gitlab create issue")
                                              (handle-gitlab-issue-created-webhook {:body body
                                                                                    :user-id (get-in request [:jwt :userId])}))}}]
         ["/commit-pushed" {:post {:parameters {:body [:map]}
                                   :responses {200 {:body [any?]}}
                                   :handler (fn [{{:keys [body]} :parameters :as request}]
                                              (log/info "webhook gitlab create issue")
                                              (handle-gitlab-commit-pushed-webhook {:body body
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
                                                 :else (monday-authorize-url token)))))}}]

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

  (monday-query [(list {:boards [:id :name
                                 {:columns [:id
                                            :title]}
                                #_(list {:column_values [:id]}
                                  {:ids "text"})]}
                   {:ids [749796977]})]
    (get-in @oauth-tokens [:monday 16227277 :access_token]))


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

  projects (-> (gitlab-query [(list {:projects
                                     [{:nodes [:fullPath]}]}
                                {:membership true})]
                 (fetch-gitlab-access-token env))
             (get-in [:body :data :projects :nodes]))

  (monday-query [(list {:boards [:id :name
                                 {:columns [:id
                                            :title
                                            :type]}]}
                   {:ids [(:board-id dev-env)]})]
    (fetch-monday-access-token dev-env))



  (let [column-mapping {:long_text "ooh oke, now I get it",
                        :name "bar",
                        :text2 "fdemir",
                        :text "Fatih"}
        board-id (:board-id dev-env)]
    (monday-query
      [{(list 'create_item
          (cond-> {:board_id board-id
                   :item_name (:name column-mapping)}
            (seq columns) (assoc :column_values (json/write-value-as-string column-mapping))))
        [:id]}]
      (fetch-monday-access-token dev-env)))

  (monday-query
    [{(list 'create_update
        {:item_id 970901943
         :body "This is an update"})
      [:id]}]
    (fetch-monday-access-token dev-env))

  )


