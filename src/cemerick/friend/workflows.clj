(ns cemerick.friend.workflows
  (:require [cemerick.friend :as friend])
  (:use [clojure.string :only (trim)])
  (:import org.apache.commons.codec.binary.Base64))

(defn find-credential-fn
  [local-credential-fn request workflow]
  (or local-credential-fn
      (-> request ::friend/auth-config :credential-fn)
      (throw (IllegalArgumentException. (str "No :credential-fn available for " (name workflow))))))

(defn http-basic-deny
  [realm request]
  {:status 401
   :headers {"Content-Type" "text/plain"
             "WWW-Authenticate" (format "Basic realm=\"%s\"" realm)}})

(defn- username-as-identity
  [user-record]
  (if (:identity user-record)
    user-record
    (assoc user-record :identity (:username user-record))))

(defn http-basic
  [& {:keys [credential-fn realm]}]
  (fn [{{:strs [authorization]} :headers :as request}]
    (when authorization
      (if-let [[[_ username password]] (try (-> (re-matches #"\s*Basic\s+(.+)" authorization)
                                              second
                                              (.getBytes "UTF-8")
                                              Base64/decodeBase64
                                              (String. "UTF-8")
                                              (#(re-seq #"([^:]+):(.*)" %)))
                                         (catch Exception e
                                           ; could let this bubble up and have an error page take over,
                                           ;   but basic is going to be used predominantly for API usage, so...
                                           ; TODO should figure out logging for widely-used library; just use tools.logging?
                                           (println "Invalid Authorization header for HTTP Basic auth: " authorization)
                                           (.printStackTrace e)))]
      (if-let [user-record ((find-credential-fn credential-fn request :http-basic)
                             ^{::friend/workflow :http-basic}
                              {:username username, :password password})]
        (with-meta (username-as-identity user-record)
          {::friend/workflow :http-basic
           ::friend/transient true
           ::friend/redirect-on-auth? false
           :type ::friend/auth})
        (http-basic-deny realm request))
      {:status 400 :body "Malformed Authorization header for HTTP Basic authentication."}))))

(defn interactive-login-redirect
  [{:keys [params] :as request}]
  (ring.util.response/redirect (let [param (str "&login_failed=Y&username=" (:username params))
                                     login-uri (-> request ::friend/auth-config :login-uri)]
                                 (str (if (.contains login-uri "?") login-uri (str login-uri "?"))
                                      param))))

(defn interactive-form
  [& {:keys [login-uri credential-fn login-failure-handler]}]
  (fn [{:keys [uri request-method params] :as request}]
    (when (and (= login-uri uri)
               (= :post request-method))
      (let [{:keys [username password] :as creds} (select-keys params [:username :password])]
        (if-let [user-record (and username password
                                  ((find-credential-fn credential-fn request :interactive-form)
                                    (with-meta creds {::friend/workflow :interactive-form})))]
          (with-meta (username-as-identity user-record)
            {::friend/workflow :interactive-form
             :type ::friend/auth})
          ((or login-failure-handler #'interactive-login-redirect) request))))))

