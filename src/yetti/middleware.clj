;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright © Andrey Antukh <niwi@niwi.nz>

(ns yetti.middleware
  "Undertow based middleware."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [yetti.request :as req]
   [yetti.response :as resp]
   [yetti.util :as yu]))

(defn wrap-params
  ([handler] (wrap-params handler {}))
  ([handler options]
   (letfn [(process-request [request]
             (let [qparams (yu/parse-query-data request options)
                   request (-> request
                               (assoc :query-params qparams)
                               (update :params merge qparams))
                   mtype   (req/get-header request "content-type")]
               (if (and (string? mtype)
                        (or (str/starts-with? mtype "application/x-www-form-urlencoded")
                            (str/starts-with? mtype "multipart/form-data")))
                 (let [params (yu/parse-form-data request)]
                   (-> request
                       (assoc :body-params params)
                       (update :params merge params)))
                 request)))]
     (fn
       ([request] (handler (process-request request)))
       ([request respond raise]
        (try
          (let [request (process-request request)]
            (handler request respond raise))
          (catch Exception cause
            (raise cause))))))))

(defn wrap-server-timing
  [handler]
  (letfn [(get-age [start]
            (float (/ (- (System/nanoTime) start) 1000000000)))

          (update-headers [headers start]
            (assoc headers "Server-Timing" (str "total;dur=" (get-age start))))]
    (fn
      ([request]
       (let [start (System/nanoTime)]
         (-> (handler request)
             (update :headers update-headers start))))
      ([request respond raise]
       (let [start (System/nanoTime)]
         (handler request #(respond (update % :headers update-headers start)) raise))))))
