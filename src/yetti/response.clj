;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright © Andrey Antukh <niwi@niwi.nz>
;;
;; Original code from ring branch:2.0 with small modifications.  this
;; is temporal approach until ring-2.0 is released.
;;
;; Copyright (c) 2009-2010 Mark McGranaghan
;; Copyright (c) 2009-2018 James Reeves
;;
;; Permission is hereby granted, free of charge, to any person
;; obtaining a copy of this software and associated documentation
;; files (the "Software"), to deal in the Software without
;; restriction, including without limitation the rights to use,
;; copy, modify, merge, publish, distribute, sublicense, and/or sell
;; copies of the Software, and to permit persons to whom the
;; Software is furnished to do so, subject to the following
;; conditions:
;;
;; The above copyright notice and this permission notice shall be
;; included in all copies or substantial portions of the Software.
;;
;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
;; EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
;; OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
;; NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
;; HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
;; WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
;; FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
;; OTHER DEALINGS IN THE SOFTWARE.

(ns yetti.response
  "Core protocols and functions for Ring 2 response maps."
  (:require [clojure.java.io :as io]))

(defprotocol Response
  "A protocol representing a HTTP response."
  (status  [resp])
  (headers [resp])
  (body    [resp]))

(defprotocol ResponseWithCookies
  (cookies [resp]))

(defprotocol StreamableResponseBody
  "A protocol for writing data to the response body via an output stream."
  (-write-body-to-stream [body response output-stream]))

(defn write-body-to-stream
  "Write a value representing a response body to an output stream. The stream
  will be automically closed after the function ends."
  [response output-stream]
  (-write-body-to-stream (body response) response output-stream))

(extend-protocol Response
  clojure.lang.IPersistentMap
  (status  [resp] (or (::status resp) (:status resp)))
  (headers [resp] (or (::headers resp) (:headers resp)))
  (body    [resp] (or (::body resp) (:body resp))))

(extend-protocol ResponseWithCookies
  clojure.lang.IPersistentMap
  (cookies [resp] (or (::cookies resp) (:cookies resp))))

(defn- ^java.io.Writer response-writer [response output-stream]
  (io/writer output-stream :encoding "UTF-8"))

(extend-protocol StreamableResponseBody
  (Class/forName "[B")
  (-write-body-to-stream [body _ ^java.io.OutputStream output-stream]
    (with-open [out output-stream]
      (.write out ^bytes body)))
  String
  (-write-body-to-stream [body response output-stream]
    (with-open [writer (response-writer response output-stream)]
      (.write writer body)))
  clojure.lang.ISeq
  (-write-body-to-stream [body response output-stream]
    (with-open [writer (response-writer response output-stream)]
      (doseq [chunk body]
        (.write writer (str chunk)))))
  java.io.InputStream
  (-write-body-to-stream [body _ ^java.io.OutputStream output-stream]
    (with-open [out output-stream, body body]
      (io/copy body out)))
  java.io.File
  (-write-body-to-stream [body _ ^java.io.OutputStream output-stream]
    (with-open [out output-stream]
      (io/copy body out)))
  nil
  (-write-body-to-stream [_ _ ^java.io.OutputStream output-stream]
    (.close output-stream)))

(defrecord ResponseWrapper [status headers body cookies]
  Response
  (status [_] status)
  (headers [_] headers)
  (body [_] body)

  ResponseWithCookies
  (cookies [_] cookies))

(defn response*
  ([] (ResponseWrapper. 204 {} nil nil))
  ([status] (ResponseWrapper. status {} nil nil))
  ([status body] (ResponseWrapper. status {} body nil))
  ([status body headers] (ResponseWrapper. status headers body nil))
  ([status body headers cookies] (ResponseWrapper. status headers body cookies)))

(defmacro response
  [& [param1 :as params]]
  (cond
    (integer? param1)
    `(response* ~@params)

    (keyword? param1)
    (let [{:keys [status body headers cookies]} params]
      `(response* ~status ~body ~headers ~cookies))

    (map? param1)
    (let [{:keys [status body headers cookies]} param1]
      `(response* ~status ~body ~headers ~cookies))

    (and (symbol? param1)
         (= 1 (count params)))
    `(map->ResponseWrapper ~param1)

    :else
    (throw (ex-info "invalid arguments" {}))))

