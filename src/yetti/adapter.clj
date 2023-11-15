;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright © Andrey Antukh <niwi@niwi.nz>

(ns yetti.adapter
  (:require
   [yetti.util :as yu]
   [yetti.websocket :as ws]
   [yetti.request :as yrq]
   [yetti.response :as yrs]
   [ring.response :as rresp]
   [ring.request :as rreq]
   [clojure.stacktrace :as ctr]
   [ring.websocket :as rws])
  (:import
   io.undertow.Undertow
   io.undertow.UndertowOptions
   io.undertow.server.DefaultByteBufferPool
   io.undertow.server.HttpHandler
   io.undertow.server.HttpServerExchange
   io.undertow.util.HeaderMap
   io.undertow.util.HttpString
   io.undertow.util.SameThreadExecutor
   java.util.concurrent.Executor
   java.util.concurrent.Executors
   java.util.concurrent.atomic.AtomicBoolean
   java.util.function.BiConsumer))

(set! *warn-on-reflection* true)

(def defaults
  {:http/headers-cache-size 64
   :http/max-cookies 32
   :http/max-headers 64
   :http/max-headers-size (* 1024 1024) ; 1 MiB
   :http/max-body-size (* 1024 1024 6) ; 6 MiB
   :http/max-multipart-body-size (* 1024 1024 12) ; 12 MiB
   :http/port 11010
   :http/host "localhost"
   :http/idle-timeout 300000
   :http/parse-timeout 30000
   :xnio/buffer-size (* 1024 16) ; 16 KiB
   :xnio/direct-buffers true
   :xnio/dispatch :virtual
   :ring/compat :ring2
   :socket/tcp-nodelay true
   :socket/backlog 1024
   :socket/reuse-address true
   :socket/read-timeout 300000
   :socket/write-timeout 300000
   :websocket/idle-timeout 500000})

(def ^:private default-executor
  (Executors/newVirtualThreadPerTaskExecutor))

(defn dispatch!
  ([^HttpServerExchange exchange ^Runnable f]
   (.dispatch exchange ^Executor default-executor f))
  ([^HttpServerExchange exchange ^Executor executor ^Runnable f]
   (.dispatch exchange executor f)))

(defn- handle-error
  [request cause {:keys [:http/on-error] :as options}]
  (let [trace (with-out-str (ctr/print-cause-trace cause))]
    (if (fn? on-error)
      (on-error cause request)
      (println trace))
    {::rresp/status 500
     ::rresp/body trace
     ::rresp/headers {"content-type" "text/plain"}}))

(defn- handle-response
  [exchange request response {:keys [:http/on-error] :as options}]
  (try
    (if-let [listener (::rws/listener response)]
      (ws/upgrade-response exchange listener options)
      (yrs/write-response! exchange response))
    (catch Throwable cause
      (if (fn? on-error)
        (on-error cause request)
        (ctr/print-cause-trace cause)))
    (finally
      (.endExchange ^HttpServerExchange exchange))))

(defn- dispatch-fn
  [handler {:keys [:http/on-error :ring/compat] :as options}]
  (let [exchange->request (case compat
                            :ring1 yrq/exchange->ring1-request
                            :ring2 yrq/exchange->ring2-request)]

    (fn [^HttpServerExchange exchange]
      (let [request  (exchange->request exchange)
            response (try
                       (handler request)
                       (catch Throwable cause
                         (handle-error request cause options)))]
        (handle-response exchange request response options)))))

(defn- create-handler
  "Creates an instance of the final handler that will be attached to
  Server."
  [handler-fn {:keys [:xnio/dispatch] :as options}]
  (let [dispatch-fn (dispatch-fn handler-fn options)]
    (cond
      (instance? Executor dispatch)
      (reify HttpHandler
        (^void handleRequest [_ ^HttpServerExchange exchange]
         (.dispatch exchange
                    ^Executor dispatch
                    ^Runnable #(do (.startBlocking exchange)
                                   (dispatch-fn exchange)))))

      (= :virtual dispatch)
      (let [executor (Executors/newVirtualThreadPerTaskExecutor)]
        (reify HttpHandler
          (^void handleRequest [_ ^HttpServerExchange exchange]
           (.dispatch exchange
                      ^Executor executor
                      ^Runnable #(do (.startBlocking exchange)
                                     (dispatch-fn exchange))))))

      (false? dispatch)
      (reify HttpHandler
        (^void handleRequest [_ ^HttpServerExchange exchange]
         (.dispatch exchange
                    ^Executor SameThreadExecutor/INSTANCE
                    ^Runnable #(dispatch-fn exchange))))

      :else
      (reify HttpHandler
        (^void handleRequest [_ ^HttpServerExchange exchange]
         (.dispatch exchange
                    ^Runnable #(do (.startBlocking exchange)
                                   (dispatch-fn exchange))))))))

(defn- create-server
  "Construct a Server instance."
  [handler {:keys [:http/port
                   :http/host
                   :http/idle-timeout
                   :http/headers-cache-size
                   :http/max-body-size
                   :http/max-multipart-body-size
                   :http/max-headers-size
                   :http/max-cookies
                   :http/max-headers
                   :xnio/direct-buffers
                   :xnio/buffer-size
                   :xnio/io-threads
                   :xnio/worker-threads
                   :socket/send-buffer
                   :socket/receive-buffer
                   :socket/write-timeout
                   :socket/read-timeout
                   :socket/reuse-address
                   :socket/tcp-nodelay
                   :socket/backlog
                   ]
            :as options}]

  (-> (Undertow/builder)
      (.addHttpListener port host)
      (cond-> io-threads             (.setIoThreads io-threads))
      (cond-> worker-threads         (.setWorkerThreads worker-threads))
      (cond-> buffer-size            (.setBufferSize buffer-size))
      (cond-> (some? direct-buffers) (.setDirectBuffers direct-buffers))

      (cond-> (some? backlog)        (.setSocketOption org.xnio.Options/BACKLOG (int backlog)))
      (cond-> (some? read-timeout)   (.setSocketOption org.xnio.Options/READ_TIMEOUT (int read-timeout)))
      (cond-> (some? write-timeout)  (.setSocketOption org.xnio.Options/WRITE_TIMEOUT (int write-timeout)))
      (cond-> (some? tcp-nodelay)    (.setSocketOption org.xnio.Options/TCP_NODELAY ^Boolean tcp-nodelay))
      (cond-> (some? reuse-address)  (.setSocketOption org.xnio.Options/REUSE_ADDRESSES ^Boolean reuse-address))
      (cond-> (some? send-buffer)    (.setSocketOption org.xnio.Options/SEND_BUFFER (int send-buffer)))
      (cond-> (some? receive-buffer) (.setSocketOption org.xnio.Options/RECEIVE_BUFFER (int receive-buffer)))

      (.setServerOption UndertowOptions/MAX_COOKIES (int max-cookies))
      (.setServerOption UndertowOptions/MAX_HEADERS (int max-headers))
      (.setServerOption UndertowOptions/MAX_HEADER_SIZE (int max-headers-size))
      (.setServerOption UndertowOptions/ALWAYS_SET_KEEP_ALIVE, false)
      (.setServerOption UndertowOptions/BUFFER_PIPELINED_DATA false)
      (.setServerOption UndertowOptions/IDLE_TIMEOUT (int idle-timeout))
      (.setServerOption UndertowOptions/ENABLE_HTTP2 true)
      (.setServerOption UndertowOptions/HTTP_HEADERS_CACHE_SIZE (int headers-cache-size))
      (.setServerOption UndertowOptions/MULTIPART_MAX_ENTITY_SIZE max-multipart-body-size)
      (.setServerOption UndertowOptions/MAX_ENTITY_SIZE max-body-size)
      (.setServerOption UndertowOptions/HTTP2_SETTINGS_ENABLE_PUSH false)
      (.setHandler  ^HttpHandler handler)
      (.build)))

(defn ^Undertow server
  "
  Creates and confgures an instance of the server. This is a list of options
  that you can provide:

  :http/port                     - the port to listen on (defaults to 11010)
  :http/host                     - the hostname to listen on, defaults to 'localhost'
  :http/idle-timeout             - the max idle time in ms for a connection (default to 200000)
  :http/parse-timeout            - max time spend in parsing request (defaults to 30000)
  :http/max-headers-size         - max headers (all) size (defaults to 1 MiB)
  :http/max-body-size            - max body size (defaults to 6 MiB)
  :http/max-multipart-body-size  - max size for multipart uploads (defaults to 12 MiB)
  :http/max-cookies              - max number of allowed cookies in the request (defaults to 32)
  :http/max-headers              - max number of allowed headers in the request (defaults to 64)
  :ring/compat                   - ring compatibility mode: :ring2 (default), :ring2-map, :ring1

  :xnio/buffer-size              - default http IO buffe size (default 64 KiB)
  :xnio/direct-buffers           - use or not direct buffers (default to false)
  :xnio/dispatch                 - dispatch or not the body of the handler to the worker executor
                                   (defaults to :virtual, can be a custom executor instance)
  :websocket/idle-timeout        - websocket specific idle timeout (defaults to 500000)
  "
  ([handler-fn] (server handler-fn {}))
  ([handler-fn options]
   (let [options (merge defaults options)
         handler (create-handler handler-fn options)]
     (create-server handler options))))

(defn start!
  "Starts the server. It accepts an optional `options` parameter
  that accepts the following attrs:

  :join - blocks the thread until the server is starts (defaults false)
  "
  [^Undertow server]
  (.start server)
  server)

(defn stop!
  "Stops the server."
  [^Undertow s]
  (.stop s)
  s)
