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
  "Core protocols and functions for Ring 2 request maps."
  (:require [yetti.util :as yu])
  (:import
   clojure.lang.Keyword
   org.xnio.XnioWorker
   java.util.concurrent.Executor
   io.undertow.server.ServerConnection
   io.undertow.server.HttpServerExchange))

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

(defprotocol RequestWithCookies
  (cookies         [req])
  (get-cookie      [req name]))

(defprotocol UndertowRequest
  (exchange [req] "Get internal exchange instance"))

(defrecord ExchangeWrapper [^Keyword method
                            ^String path
                            ^HttpServerExchange exchange]
  Request
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

  RequestWithCookies
  (cookies [_]         (yu/get-request-cookies exchange))
  (get-cookie [_ name] (yu/get-request-cookie exchange name))

  UndertowRequest
  (exchange [_] exchange)

  Executor
  (execute [_ r]
    (let [sconn (.getConnection exchange)
          exc   (.getWorker ^ServerConnection sconn)]
      (.execute ^Executor exc ^Runnable r))))

(defn request
  "Create the request from the HttpServerExchange."
  [^HttpServerExchange exchange]
  (let [method  (keyword (.. exchange getRequestMethod toString toLowerCase))
        path    (.getRequestURI exchange)]
    (ExchangeWrapper. ^Keyword method ^String path exchange)))
