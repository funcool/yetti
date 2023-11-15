;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright © Andrey Antukh <niwi@niwi.nz>

(ns yetti.response
  (:require
   [clojure.java.io :as io]
   [ring.core.protocols :as rcp]
   [ring.response :as rres]
   [yetti.util :as yu])
  (:import
   io.undertow.server.HttpServerExchange
   io.undertow.util.HeaderMap
   io.undertow.util.HttpString))

(defprotocol ResponseCookies
  (cookies [resp]))

(extend-protocol ResponseCookies
  clojure.lang.IPersistentMap
  (cookies [resp]
    (or (::rres/cookies resp)
        (::cookies resp)
        (:cookies resp))))

;; TODO: add headers parsing optimization

(defn write-response!
  "Update the HttpServerExchange using a response map."
  [^HttpServerExchange exchange response]
  (when-not (.isResponseStarted exchange)
    (.setStatusCode exchange (or (rres/status response) 200))
    (let [response-headers ^HeaderMap (.getResponseHeaders exchange)]
      (doseq [[key val-or-vals] (rres/headers response)]
        (let [key (HttpString/tryFromString ^String key)]
          (if (coll? val-or-vals)
            (.putAll response-headers key ^Collection val-or-vals)
            (.put response-headers key ^String val-or-vals))))
      (when-let [cookies (cookies response)]
        (yu/set-cookies! exchange cookies))
      (let [output-stream (.getOutputStream exchange)]
        (rres/write-body-to-stream response output-stream)))))
