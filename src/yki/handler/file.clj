(ns yki.handler.file
  (:require [compojure.api.sweet :refer [context GET POST]]
            [yki.boundary.organizer-db :as organizer-db]
            [yki.boundary.files :as files]
            [yki.spec :as ys]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info error]]
            [ring.util.http-response :refer [ok bad-request not-found]]
            [ring.util.request]
            [ring.util.response :refer [header]]
            [clojure.spec.alpha :as s]
            [ring.middleware.multipart-params :as mp]
            [integrant.core :as ig]))

(defmethod ig/init-key :yki.handler/file [_ {:keys [db file-store]}]
  (fn [oid]
    (context "/" []
      :middleware [mp/wrap-multipart-params]
      (POST "/" {multipart-params :multipart-params}
        :return ::ys/external-id-type
        (let [file (multipart-params "file")
              tempfile (:tempfile file)
              filename (:filename file)]
          (try
            (if-let [resp (files/upload-file file-store tempfile filename)]
              (if (organizer-db/create-attachment-metadata! db oid "agreement" (resp "key"))
                (ok {:external_id (resp "key")}))
              (bad-request {:error "Failed to upload file"}))
            (catch Exception e
              (error e "Failed to upload file")
              (throw e))
            (finally
              (io/delete-file tempfile true)))))
      (context "/:external-id" []
        (GET "/" request
          :path-params [external-id :- ::ys/external_id]
          (if-let [metadata (organizer-db/get-attachment-metadata db external-id oid)]
            (if-let [file-response (files/get-file file-store external-id)]
              (header (ok (:body file-response))
                      "Content-Disposition"
                      (:content-disposition file-response))
              (not-found))
            (not-found)))))))

