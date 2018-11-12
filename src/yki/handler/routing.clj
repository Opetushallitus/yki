(ns yki.handler.routing)

(def app-root "/yki")

(def api-root (str app-root "/api"))

(def auth-root (str app-root "/auth"))

(def auth-init-session-uri "/initsession")

(def auth-authenticate-uri "/authenticate")

(def virkailija-auth-uri "/cas")

(def virkailija-auth-callback (str auth-root virkailija-auth-uri "/callback"))

(def virkailija-auth-logout (str auth-root virkailija-auth-uri "/logout"))

(def auth-callback (str auth-root "/login"))

(def localisation-api-root (str api-root "/localisation"))

(def login-link-api-root (str api-root "/login-link"))

(def payment-root (str app-root "/payment"))

(def virkailija-api-root (str api-root "/virkailija"))

(def organizer-api-root (str virkailija-api-root "/organizer"))

(def status-api-root (str api-root "/status"))

(def file-uri "/file")

(def exam-session-uri "/exam-session")
