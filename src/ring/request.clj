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

(ns ring.request
  "Core protocols and functions for Ring 2 request maps."
  {:added "2.0"}
  (:require [ring.util.parsing :as parsing]))

(set! *warn-on-reflection* true)

(defprotocol Request
  "A protocol representing a HTTP request."
  (server-port     [req])
  (server-name     [req])
  (remote-addr     [req])
  (ssl-client-cert [req])
  (method          [req])
  (scheme          [req])
  (path            [req])
  (query           [req])
  (protocol        [req])
  (headers         [req])
  (body            [req])
  (get-header      [req name]))

(defprotocol StreamableRequestBody
  "A protocol for reading the request body as an input stream."
  (-body-stream [body request]))

(defn ^java.io.InputStream body-stream
  "Given a request map, return an input stream to read the body."
  [request]
  (-body-stream (body request) request))

(defn charset
  "Given a request map, return the charset of the content-type header."
  [request]
  (when-let [content-type (get-header request "content-type")]
    (second (re-find parsing/re-charset content-type))))

(extend-protocol Request
  clojure.lang.IPersistentMap
  (server-port     [req] (::server-port     req (:server-port req)))
  (server-name     [req] (::server-name     req (:server-name req)))
  (remote-addr     [req] (::remote-addr     req (:remote-addr req)))
  (ssl-client-cert [req] (::ssl-client-cert req (:ssl-client-cert req)))
  (method          [req] (::method          req (:request-method req)))
  (scheme          [req] (::scheme          req (:scheme req)))
  (path            [req] (::path            req (:uri req)))
  (query           [req] (::query           req (:query-string req)))
  (protocol        [req] (::protocol        req (:protocol req)))
  (headers         [req] (::headers         req (:headers req)))
  (body            [req] (::body            req (:body req)))
  (get-header [req name] (get (headers req) name)))

(extend-protocol StreamableRequestBody
  (Class/forName "[B")
  (-body-stream [bs _]
    (java.io.ByteArrayInputStream. ^bytes bs))
  java.io.InputStream
  (-body-stream [stream _] stream)

  String
  (-body-stream [^String s request]
    (java.io.ByteArrayInputStream.
     (if-let [encoding (charset request)]
       (.getBytes s ^String encoding)
       (.getBytes s "utf-8"))))
  nil
  (-body-stream [_ _] nil))
