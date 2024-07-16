;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright © Andrey Antukh <niwi@niwi.nz>

(ns yetti.response
  (:require
   [clojure.java.io :as io]
   [ring.core.protocols :as rcp]
   [yetti.util :as yu])
  (:import
   io.undertow.server.HttpServerExchange
   io.undertow.util.HeaderMap
   io.undertow.util.HttpString))

(defprotocol IResponse
  "A protocol representing a HTTP response."
  (status  [resp])
  (headers [resp])
  (body    [resp]))

(defprotocol IResponseCookies
  (cookies [resp]))

(extend-protocol IResponse
  clojure.lang.IPersistentMap
  (status  [resp] (or (::status resp) (:status resp)))
  (headers [resp] (or (::headers resp) (:headers resp)))
  (body    [resp] (or (::body resp) (:body resp))))

(extend-protocol IResponseCookies
  clojure.lang.IPersistentMap
  (cookies [resp]
    (or (::cookies resp)
        (:cookies resp))))

(defn charset
  "Given a response map, return the charset of the content-type header."
  [response]
  (when-let [content-type (-> response headers (get "content-type"))]
    (second (re-find yu/re-charset content-type))))

(defn write-response!
  "Update the HttpServerExchange using a response map."
  [^HttpServerExchange exchange response]
  (when-not (.isResponseStarted exchange)
    (.setStatusCode exchange (or (status response) 200))
    (let [response-headers ^HeaderMap (.getResponseHeaders exchange)]
      (doseq [[key val-or-vals] (headers response)]
        (let [key (HttpString/tryFromString ^String key)]
          (if (coll? val-or-vals)
            (.putAll response-headers key ^Collection val-or-vals)
            (.put response-headers key ^String val-or-vals))))
      (when-let [cookies (cookies response)]
        (yu/set-cookies! exchange cookies))
      (let [output-stream (.getOutputStream exchange)]
        (rcp/write-body-to-stream (body response) response output-stream)))))
