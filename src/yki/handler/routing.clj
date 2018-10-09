(ns yki.handler.routing)

(def app-root "/yki")

(def api-root (str app-root "/api"))

(def auth-root (str app-root "/auth"))

(def virkailija-auth-root (str auth-root "/cas"))

(def virkailija-auth-callback (str virkailija-auth-root "/callback"))

(def virkailija-auth-logout (str virkailija-auth-root "/logout"))

(def localisation-api-root (str api-root "/localisation"))

(def virkailija-api-root (str api-root "/virkailija"))

(def organizer-api-root (str virkailija-api-root "/organizer"))

(def status-api-root (str api-root "/status"))

(def file-uri "/file")

(def exam-session-uri "/exam-session")
