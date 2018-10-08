(ns yki.middleware.no-auth
  (:require [ring.middleware.session :refer [wrap-session]]
            [clojure.tools.logging :refer [warn]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.middleware.no-auth/with-authentication [_ {:keys [session-config]}]
  (defn with-authentication [handler]
    (warn "No authentication in use")
    (-> handler
        (wrap-session {:store (cookie-store {:key (:key session-config)})
                       :cookie-name "yki"
                       :cookie-attrs (:cookie-attrs session-config)}))))

