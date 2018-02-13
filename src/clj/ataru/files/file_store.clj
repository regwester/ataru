(ns ataru.files.file-store
  (:require [ataru.url :as url]
            [ataru.config.url-helper :refer [resolve-url]]
            [ataru.config.core :refer [config]]
            [org.httpkit.client :as http]
            [cheshire.core :as json])
  (:import (java.text Normalizer Normalizer$Form)))

(defn upload-file [{:keys [tempfile filename]}]
  (let [url  (resolve-url :liiteri.files)
        resp @(http/post url {:multipart [{:name     "file"
                                           :content  tempfile
                                           :filename (Normalizer/normalize filename Normalizer$Form/NFD)}]})]
    (when (= (:status resp) 200)
      (-> (:body resp)
          (json/parse-string true)
          (dissoc :version :deleted)))))

(defn delete-file [file-key]
  (let [url  (resolve-url :liiteri.file file-key)
        resp @(http/delete url)]
    (when (= (:status resp) 200)
      (json/parse-string (:body resp) true))))

(defn get-metadata [file-keys]
  (let [resp @(http/post (resolve-url :liiteri.metadata)
                         {:headers {"Content-Type" "application/json"}
                          :body (json/generate-string {:keys file-keys})})]
    (when (= (:status resp) 200)
      (json/parse-string (:body resp) true))))

(defn get-file [key]
  (let [url  (resolve-url :liiteri.file key)
        resp @(http/get url)]
    (when (= (:status resp) 200)
      {:body (:body resp)
       :content-disposition (-> resp :headers :content-disposition)})))
