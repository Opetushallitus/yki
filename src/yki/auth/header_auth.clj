(ns yki.auth.header-auth
  (:require [ring.util.http-response :refer [found ok see-other]]
            [yki.boundary.onr :as onr]
            [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import [java.util UUID]))

(defn- iso-8859-1->utf-8
  "Shibboleth encodes headers in UTF-8. Servlet container handles them as ISO-8859-1,
  so we need to convert values back to UTF-8.
  See https://wiki.shibboleth.net/confluence/display/SHIB2/NativeSPAttributeAccess"
  [s]
  (if s
    (String. (.getBytes s "ISO-8859-1") "UTF-8")))

(def unauthorized {:status 401
                   :body "Unauthorized"
                   :headers {"Content-Type" "text/plain; charset=utf-8"}})

(defn login [{:keys [query-params headers session]} onr-client url-helper]
  (let [lang (or (:lang query-params) "fi")
        {:strs [vakinainenkotimainenlahiosoites
                vakinainenkotimainenlahiosoitepostitoimipaikkas
                vakinainenkotimainenlahiosoitepostinumero
                sn firstname nationalidentificationnumber]} (reduce-kv #(assoc %1 %2 (iso-8859-1->utf-8 %3)) {} headers)
        {:strs [etunimet sukunimi kutsumanimi oidHenkilo kansalaisuus]} (onr/get-person-by-ssn onr-client nationalidentificationnumber)
        address {:post_office    vakinainenkotimainenlahiosoitepostitoimipaikkas
                 :zip            vakinainenkotimainenlahiosoitepostinumero
                 :street_address vakinainenkotimainenlahiosoites}
        redirect-url (or (:success-redirect session) (url-helper :yki.default.login-success.redirect lang))]
    (log/info "User" (or oidHenkilo sn) "logged in, redirecting to" redirect-url)
    (if (and sn firstname nationalidentificationnumber)
      (assoc
       (found redirect-url)
       :session
       {:identity
        (merge
         {:first_name
          (if etunimet
            etunimet
            firstname)
          :last_name (or sukunimi sn)
          :nick_name kutsumanimi
          :ssn nationalidentificationnumber
          :oid oidHenkilo
          :nationalities (mapv #(get % "kansalaisuusKoodi") kansalaisuus)
          :external-user-id (or oidHenkilo nationalidentificationnumber)}
         address)
        :auth-method "SUOMIFI"
        :yki-session-id (str (UUID/randomUUID))})
      unauthorized)))

    (defn logout [url-helper lang]
      (let [redirect-url (url-helper :tunnistus.logout (url-helper :yki.default.logout.redirect "fi"))]
        (-> (see-other redirect-url)
            (assoc :session nil))))
