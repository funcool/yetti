;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright © Andrey Antukh <niwi@niwi.nz>

(ns yetti.middleware
  "Yetti specific middlewates that works with the native Request type."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [yetti.request :as yrq]
   [yetti.util :as yu]
   [yetti.response :as-alias yrs]))

(defn wrap-params
  ([handler] (wrap-params handler {}))
  ([handler options]
   (fn [request]
     (let [qparams (yu/parse-query-data request options)
           request (if (yrq/request? request)
                     (-> request
                         (assoc :query-params qparams)
                         (update :params merge qparams))
                     (-> request
                         (assoc ::yrq/query-params qparams)
                         (update ::yrq/params merge qparams)))

           mtype   (yrq/get-header request "content-type")
           request (if (and (string? mtype)
                            (or (str/starts-with? mtype "application/x-www-form-urlencoded")
                                (str/starts-with? mtype "multipart/form-data")))
                     (let [params (yu/parse-form-data request options)]
                       (-> request
                           (assoc :body-params params)
                           (update :params merge params)))
                     request)]
       (handler request)))))

(defn wrap-server-timing
  [handler]
  (letfn [(get-age [start]
            (float (/ (- (System/nanoTime) start) 1000000000)))

          (update-headers [headers start]
            (assoc headers "Server-Timing" (str "total;dur=" (get-age start))))]

    (fn [request]
      (let [start (System/nanoTime)]
        (-> (handler request)
            (update ::yrs/headers update-headers start))))))

