(ns yki.handler.routing)

(def app-root "/yki")

(def api-root (str app-root "/api"))

(def auth-root (str app-root "/auth"))

(def auth-init-session-uri "/initsession")

(def auth-authenticate-uri "/authenticate")

(def virkailija-auth-uri "/cas")

(def virkailija-auth-callback (str auth-root virkailija-auth-uri "/callback"))

(def virkailija-auth-logout (str auth-root virkailija-auth-uri "/logout"))

(def oppija-auth-callback (str auth-root "/callback"))

(def auth-callback (str auth-root "/login"))

(def localisation-api-root (str api-root "/localisation"))

(def exam-date-api-root (str api-root "/exam-date"))

(def login-link-api-root (str api-root "/login-link"))

(def logout-link (str auth-root "/logout"))

(def payment-root (str app-root "/payment"))

(def payment-v2-root (str api-root "/payment/v2"))

(def paytrail-payment-root (str payment-v2-root "/paytrail"))

(def evaluation-payment-root (str app-root "/evaluation-payment"))

(def evaluation-payment-new-root (str api-root "/evaluation-payment/v2"))

(def evaluation-payment-new-paytrail-callback-root (str evaluation-payment-new-root "/paytrail"))

(def registration-api-root (str api-root "/registration"))

(def exam-session-public-api-root (str api-root "/exam-session"))

(def evaluation-root (str api-root "/evaluation"))

(def virkailija-api-root (str api-root "/virkailija"))

(def organizer-api-root (str virkailija-api-root "/organizer"))

(def quarantine-api-root (str virkailija-api-root "/quarantine"))

(def status-api-root (str api-root "/status"))

(def file-uri "/file")

(def exam-session-uri "/exam-session")

(def registration-uri "/registration")

(def post-admission-uri "/post-admission")

(def code-api-root (str api-root "/code"))

(def exam-date-uri "/exam-date")
