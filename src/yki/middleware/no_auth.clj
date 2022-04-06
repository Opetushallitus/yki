(ns yki.middleware.no-auth
  (:require [clojure.tools.logging :refer [warn]]
            [integrant.core :as ig]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]))

(defmethod ig/init-key :yki.middleware.no-auth/with-authentication [_ {:keys [session-config]}]
  (defn with-authentication [handler]
    (warn "No authentication in use")
    (wrap-session
     handler
     {:store        (cookie-store {:key (.getBytes ^String (:key session-config))})
      :cookie-name  "yki"
      :cookie-attrs (:cookie-attrs session-config)})))
