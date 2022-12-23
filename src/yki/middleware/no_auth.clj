(ns yki.middleware.no-auth
  (:require [clojure.tools.logging :refer [warn]]
            [integrant.core :as ig]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]))

(defmethod ig/init-key :yki.middleware.no-auth/with-authentication [_ {:keys [session-config]}]
  (fn with-authentication [handler]
    (warn "No authentication in use")
    (wrap-session
      handler
      {:store        (cookie-store {:key (.getBytes ^String (:key session-config))})
       :cookie-name  "yki"
       :cookie-attrs (:cookie-attrs session-config)})))

(defmethod ig/init-key :yki.middleware.no-auth/with-fake-oid [_ {:keys [oid]}]
  (fn with-authentication [handler]
    (warn "No authentication in use, injecting a constant OID into the session identity.")
    (fn [request]
      (-> request
          (assoc-in [:session :identity :oid] oid)
          (handler)))))
