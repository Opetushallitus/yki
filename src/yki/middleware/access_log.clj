(ns yki.middleware.access-log
  (:require
    [clj-time.core :as t]
    [clojure.string :as s]
    [jsonista.core :as json]
    [integrant.core :as ig]
    [ring.logger :as logger]))

(def request-keys
  [:request-method :uri :server-name :remote-addr :headers :cookies :query-string :status :session])

(defn- remove-ticket [query-string]
  (s/replace query-string #"ticket.*?(?=&|\?|$)" "ticket=******"))

(defn- env->log-transformer
  [env]
  (fn [{{:keys [request-method remote-addr headers cookies query-string uri status session ring.logger/ms]} :message :as opt}]
    (let [method      (-> request-method name s/upper-case)
          request     (str method " " uri (when query-string (str "?" (remove-ticket query-string))))
          log-map     {:timestamp       (str (t/to-time-zone (t/now) (t/time-zone-for-id "Europe/Helsinki")))
                       :responseCode    status
                       :request         request
                       :responseTime    ms
                       :requestMethod   method
                       :service         "yki"
                       :environment     env
                       :user-agent      (headers "user-agent")
                       :caller-id       (headers "Caller-Id")
                       :x-forwarded-for (headers "x-forwarded-for")
                       :x-real-ip       (headers "x-real-ip")
                       :remote-ip       remote-addr
                       :session         (:yki-session-id session)
                       :referer         (headers "referer")}
          log-message (json/write-value-as-string log-map)]
      (assoc opt :message log-message))))

(defmethod ig/init-key :yki.middleware.access-log/with-logging [_ {:keys [env]}]
  (fn with-logging [handler]
    (logger/wrap-log-response
      handler
      {:transform-fn (env->log-transformer env), :request-keys request-keys})))
