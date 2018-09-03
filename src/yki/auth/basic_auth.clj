(ns yki.auth.basic-auth
    (:require [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
              [integrant.core :as ig]
              [buddy.auth.backends.httpbasic :refer [http-basic-backend]]))

  (def authdata
    {:admin "test2"
     :test "test2"})

  (defn basic-authfn
    [req {:keys [username password]}]
    (when-let [user-password (get authdata (keyword username))]
      (when (= password user-password)
        (keyword username))))

  (def basic-auth-backend
    (http-basic-backend {:realm "yki"
                         :authfn basic-authfn}))
  (defn auth
    [handler]
    (wrap-authentication handler basic-auth-backend))

  (defmethod ig/init-key :yki.auth.basic-auth/auth [_ options]
    auth)
