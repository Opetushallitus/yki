(ns yki.handler.file
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :refer [error]]
            [compojure.api.sweet :refer [context GET POST]]
            [integrant.core :as ig]
            [ring.middleware.multipart-params :as mp]
            [ring.util.http-response :refer [ok bad-request not-found]]
            [ring.util.response :refer [header]]
            [yki.boundary.files :as files]
            [yki.boundary.organizer-db :as organizer-db]
            [yki.spec :as ys]))

(defmethod ig/init-key :yki.handler/file [_ {:keys [db file-store]}]
  (fn [oid]
    (context "/" []
      :middleware [mp/wrap-multipart-params]
      (POST "/" {multipart-params :multipart-params}
        :return ::ys/external-id-type
        (let [file     (multipart-params "file")
              tempfile (:tempfile file)
              filename (:filename file)]
          (try
            (if-let [resp (files/upload-file file-store tempfile filename)]
              (when (organizer-db/create-attachment-metadata! db oid "agreement" (resp "key"))
                (ok {:external_id (resp "key")}))
              (bad-request {:error "Failed to upload file"}))
            (catch Exception e
              (error e "Failed to upload file")
              (throw e))
            (finally
              (io/delete-file tempfile true)))))
      (context "/:external-id" []
        (GET "/" _
          :path-params [external-id :- ::ys/external_id]
          (if (organizer-db/get-attachment-metadata db external-id oid)
            (if-let [file-response (files/get-file file-store external-id)]
              (header (ok (:body file-response))
                      "Content-Disposition"
                      (:content-disposition file-response))
              (not-found))
            (not-found)))))))
