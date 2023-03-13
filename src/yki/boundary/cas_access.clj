(ns yki.boundary.cas-access)

(defprotocol CasAccess
  (validate-ticket [this ticket])
  (validate-oppija-ticket [this ticket callback-url])
  (cas-authenticated-post [this url body])
  (cas-authenticated-get [this url]))
