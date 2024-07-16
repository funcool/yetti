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

(ns yetti.request
  (:require
   [yetti.util :as yu])
  (:import
   clojure.lang.Keyword
   org.xnio.XnioWorker
   java.util.concurrent.Executor
   io.undertow.server.ServerConnection
   io.undertow.server.HttpServerExchange))

(set! *warn-on-reflection* true)

(defprotocol IRequest
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

(defprotocol IRequestCookies
  (cookies         [req])
  (get-cookie      [req name]))

(defprotocol IStreamableRequestBody
  "A protocol for reading the request body as an input stream."
  (-body-stream [body request]))

(defn ^java.io.InputStream body-stream
  "Given a request map, return an input stream to read the body."
  [request]
  (-body-stream (body request) request))

(defrecord Request [^Keyword method ^String path ^HttpServerExchange exchange]
  IRequest
  (method [_]          method)
  (path [_]            path)
  (body [_]            (.getInputStream exchange))
  (headers [_]         (yu/get-request-headers exchange))
  (query [_]           (.getQueryString exchange))
  (server-port [_]     (.. exchange getDestinationAddress getPort))
  (server-name [_]     (.getHostName exchange))
  (remote-addr [_]     (.. exchange getSourceAddress getAddress getHostAddress))
  (scheme [_]          (keyword (.. exchange getRequestScheme)))
  (protocol [_]        (.. exchange getProtocol toString))
  (get-header [_ name] (yu/get-request-header exchange name))

  IRequestCookies
  (cookies [_]         (yu/get-request-cookies exchange))
  (get-cookie [_ name] (yu/get-request-cookie exchange name))

  Executor
  (execute [_ r]
    (let [sconn (.getConnection exchange)
          exc   (.getWorker ^ServerConnection sconn)]
      (.execute ^Executor exc ^Runnable r))))

(defn charset
  "Given a request map, return the charset of the content-type header."
  [request]
  (when-let [content-type (get-header request "content-type")]
    (second (re-find yu/re-charset content-type))))


(defn request?
  [o]
  (instance? Request o))

(defn exchange->ring1-request
  {:no-doc true}
  [^HttpServerExchange exchange]
  {:server-port (-> exchange .getDestinationAddress .getPort)
   :server-name (.getHostName exchange)
   :remote-addr (-> exchange .getSourceAddress .getAddress .getHostAddress)
   :uri (.getRequestURI exchange)
   :query-string (let [qs (.getQueryString exchange)] (if-not (.equals "" qs) qs))
   :scheme (-> exchange .getRequestScheme keyword)
   :request-method (-> exchange .getRequestMethod .toString .toLowerCase keyword)
   :protocol (-> exchange .getProtocol .toString)
   :headers (yu/get-request-headers exchange)
   :body (if (.isBlocking exchange) (.getInputStream exchange))})

(defn exchange->ring2-request
  "Create the request from the HttpServerExchange."
  {:no-doc true}
  [^HttpServerExchange exchange]
  (let [method (keyword (.. exchange getRequestMethod toString toLowerCase))
        path   (.getRequestURI exchange)]
    (Request. ^Keyword method ^String path exchange)))

(extend-protocol IRequest
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

(extend-protocol IStreamableRequestBody
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

