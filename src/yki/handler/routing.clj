(ns yki.handler.routing)

(def app-root "/yki")

(def api-root (str app-root "/api"))

(def virkailija-api-root (str api-root "/virkailija"))

(def organizer-api-root (str virkailija-api-root "/organizer"))

(def status-api-root (str api-root "/status"))
