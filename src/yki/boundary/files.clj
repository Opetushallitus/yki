(ns yki.boundary.files
  (:require [yki.util.http-util :as http-util]
            [integrant.core :as ig]
            [jsonista.core :as json])
  (:import (java.text Normalizer Normalizer$Form)))

(defprotocol FileStore
  (upload-file [this tempfile filename]))

(defrecord LiiteriFileStore [url-helper]
  FileStore
  (upload-file [_ tempfile filename]
    (let [url  (url-helper :liiteri.files)
          resp (http-util/do-post url {:multipart [{:name     "file"
                                                    :content  tempfile
                                                    :filename (Normalizer/normalize filename Normalizer$Form/NFD)}]})]
      (when (= (:status resp) 200)
        (json/read-value (:body resp))))))

(defmethod ig/init-key :yki.boundary.files/liiteri-file-store [_ {:keys [url-helper]}]
  (->LiiteriFileStore url-helper))