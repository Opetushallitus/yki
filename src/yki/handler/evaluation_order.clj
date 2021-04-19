(ns yki.handler.evaluation-order
  (:require [compojure.api.sweet :refer [api context GET]]
            [clojure.tools.logging :as log]
            [yki.handler.routing :as routing]
            [yki.boundary.registration-db :as registration-db]
            [yki.util.audit-log :as audit]
            [yki.spec :as ys]
            [yki.registration.paytrail-payment :as paytrail-payment]
            [yki.middleware.access-log]
            [ring.util.http-response :refer [ok internal-server-error found]]
            [integrant.core :as ig]))
