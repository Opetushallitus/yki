(ns yki.auth.header-auth
  (:require [ring.util.http-response :refer [see-other]]
            [yki.boundary.onr :as onr]
            [clojure.string :as str])
  (:import [java.util UUID]))

(defn login [{:keys [query-params headers session]} onr-client url-helper]
  (let [lang (or (:lang query-params) "fi")
        {:strs [vakinainenkotimainenlahiosoites
                vakinainenkotimainenlahiosoitepostitoimipaikkas
                vakinainenkotimainenlahiosoitepostinumero
                sn firstname nationalidentificationnumber]} headers
        {:strs [etunimet sukunimi kutsumanimi oidHenkilo]} (onr/get-person-by-ssn onr-client nationalidentificationnumber)
        address {:post-office    vakinainenkotimainenlahiosoitepostitoimipaikkas
                 :zip            vakinainenkotimainenlahiosoitepostinumero
                 :street-address vakinainenkotimainenlahiosoites}
        redirect-url (or (:success session) (url-helper :yki.default.login-success.redirect {"lang" lang}))]
    (if (and sn firstname nationalidentificationnumber)
      (-> (see-other redirect-url)
          (assoc :session {:identity (merge
                                      {:firstname       (first
                                                         (if etunimet
                                                           (str/split etunimet #" ")
                                                           (str/split firstname #" ")))
                                       :lastname         (or sukunimi sn)
                                       :nickname         kutsumanimi
                                       :ssn              nationalidentificationnumber
                                       :external-user-id oidHenkilo}
                                      address)
                           :yki-session-id (str (UUID/randomUUID))}))
      {:status 403 :body {:error "Authentication failed"}})))
