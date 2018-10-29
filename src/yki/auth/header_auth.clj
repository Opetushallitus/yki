(ns yki.auth.header-auth
  (:require [ring.util.http-response :refer [found]]
            [yki.boundary.onr :as onr]
            [clojure.string :as str])
  (:import [java.util UUID]))

(defn- iso-8859-1->utf-8 [s]
  "Shibboleth encodes headers in UTF-8. Servlet container handles them as ISO-8859-1,
  so we need to convert values back to UTF-8.
  See https://wiki.shibboleth.net/confluence/display/SHIB2/NativeSPAttributeAccess"
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
        {:strs [etunimet sukunimi kutsumanimi oidHenkilo]}  (onr/get-person-by-ssn onr-client nationalidentificationnumber)
        address {:post-office    vakinainenkotimainenlahiosoitepostitoimipaikkas
                 :zip            vakinainenkotimainenlahiosoitepostinumero
                 :street-address vakinainenkotimainenlahiosoites}
        redirect-url (or (:success-redirect session) (url-helper :yki.default.login-success.redirect {"lang" lang}))]
    (if (and sn firstname nationalidentificationnumber)
      (-> (found redirect-url)
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
      unauthorized)))