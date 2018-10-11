(ns yki.middleware.access-log
  (:require
   [jsonista.core :as json]
   [clj-time.core :as t]
   [clojure.string :as s]
   [integrant.core :as ig]
   [ring.logger :as logger]))

(def request-keys
  [:request-method :uri :server-name :remote-addr :headers :cookies :query-string :status :session])

(defn- remove-ticket [query-string]
  (s/replace query-string #"ticket.*?(?=&|\?|$)" "ticket=******"))

(defmethod ig/init-key :yki.middleware.access-log/with-logging [_ {:keys [env]}]
  (defn- log-transformer
    [{{:keys [request-method remote-addr headers cookies query-string uri status session ring.logger/ms]} :message :as opt}]
    (assoc opt :message
           (let [method      (-> request-method name s/upper-case)
                 request     (str method " " uri (when query-string (str "?" (remove-ticket query-string))))
                 log-map     {:timestamp           (.toString (t/to-time-zone (t/now) (t/time-zone-for-id "Europe/Helsinki")))
                              :responseCode        status
                              :request             request
                              :responseTime        ms
                              :requestMethod       method
                              :service             "yki"
                              :environment         env
                              :user-agent          (headers "user-agent")
                              :caller-id           (headers "Caller-Id")
                              :clientSubsystemCode (headers "clientSubSystemCode")
                              :x-forwarded-for     (headers "x-forwarded-for")
                              :x-real-ip           (headers "x-real-ip")
                              :remote-ip           remote-addr
                              :session             (:yki-session-id session)
                              :referer             (headers "referer")}
                 log-message (json/write-value-as-string log-map)]
             log-message)))

  (defn with-logging [handler]
    (-> handler
        (logger/wrap-log-response {:transform-fn log-transformer
                                   :request-keys request-keys}))))

