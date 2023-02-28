;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright © Andrey Antukh <niwi@niwi.nz>

(ns yetti.adapter
  (:require
   [yetti.util :as yu]
   [yetti.websocket :as ws]
   [yetti.request :as req]
   [yetti.response :as resp]
   [clojure.stacktrace :as ctr])
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
   :xnio/dispatch true

   :socket/tcp-nodelay true
   :socket/backlog 1024
   :socket/reuse-address true
   :socket/read-timeout 300000
   :socket/write-timeout 300000

   :ring/async false
   :websocket/idle-timeout 500000})

(defn dispatch!
  ([^HttpServerExchange exchange ^Runnable f]
   (.dispatch exchange f))
  ([^HttpServerExchange exchange ^Executor executor ^Runnable f]
   (.dispatch exchange executor f)))

(defn- write-response!
  "Update the HttpServerExchange using a response map."
  [^HttpServerExchange exchange response]
  (when-not (.isResponseStarted exchange)
    (.setStatusCode exchange (or (resp/status response) 200))
    (let [response-headers ^HeaderMap (.getResponseHeaders exchange)]
      (doseq [[key val-or-vals] (resp/headers response)]
        (let [key (HttpString/tryFromString ^String key)]
          (if (coll? val-or-vals)
            (.putAll response-headers key ^Collection val-or-vals)
            (.put response-headers key ^String val-or-vals))))
      (when-let [cookies (resp/cookies response)]
        (yu/set-cookies! exchange cookies))
      (let [output-stream (.getOutputStream exchange)]
        (resp/write-body-to-stream response output-stream)))))

(defn- dispatch-async-fn
  [handler {:keys [:http/on-error] :as options}]
  (fn [^HttpServerExchange exchange]
    (let [request   (req/request exchange)
          responded (AtomicBoolean. false)]
      (handler
       request
       (fn [response]
         (when (.compareAndSet ^AtomicBoolean responded false true)
           (try
             (if-let [upgrade-fn (::ws/upgrade response)]
               (ws/upgrade-response exchange upgrade-fn options)
               (write-response! exchange response))
             (catch Throwable cause
               (if (fn? on-error)
                 (on-error cause request)
                 (ctr/print-cause-trace cause)))
             (finally
               (.endExchange ^HttpServerExchange exchange)))))
       (fn [cause]
         (when (.compareAndSet ^AtomicBoolean responded false true)
           (try
             (let [trace    (with-out-str (ctr/print-cause-trace cause))
                   response (resp/response 500 trace {"content-type" "text/plain"})]
               (write-response! exchange response))
             (finally
               (.endExchange ^HttpServerExchange exchange)))))))))

(defn- dispatch-sync-fn
  [handler {:keys [:http/on-error] :as options}]
  (letfn [(handle-error [cause request]
            (let [trace (with-out-str (ctr/print-cause-trace cause))]
              (if (fn? on-error)
                (on-error cause request)
                (println trace))
              {::resp/status 500
               ::resp/body trace
               ::resp/headers {"content-type" "text/plain"}}))

          (handle-response [response exchange request]
            (try
              (if-let [upgrade-fn (::ws/upgrade response)]
                (ws/upgrade-response exchange upgrade-fn options)
                (write-response! exchange response))
              (catch Throwable cause
                (if (fn? on-error)
                  (on-error cause request)
                  (ctr/print-cause-trace cause)))
              (finally
                (.endExchange ^HttpServerExchange exchange))))]

    (fn [^HttpServerExchange exchange]
      (let [request  (req/request exchange)
            response (try
                       (handler request)
                       (catch Throwable cause
                         (handle-error cause request)))]
        (handle-response response exchange request)))))

(defn- create-handler
  "Creates an instance of the final handler that will be attached to
  Server."
  [handler-fn {:keys [:xnio/dispatch :ring/async] :as options}]
  (let [dispatch-fn (if async
                      (dispatch-async-fn handler-fn options)
                      (dispatch-sync-fn handler-fn options))]
    (cond
      (instance? Executor dispatch)
      (reify HttpHandler
        (^void handleRequest [_ ^HttpServerExchange exchange]
         (.dispatch exchange
                    ^Executor dispatch
                    ^Runnable #(do (.startBlocking exchange)
                                   (dispatch-fn exchange)))))

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
  "Construct a Jetty Server instance."
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
  Creates and confgures an instance of jetty server. This is a list of options
  that you can provide:

  :ring/async                    - enables the ring 1.6 async handler
  :http/port                     - the port to listen on (defaults to 11010)
  :http/host                     - the hostname to listen on, defaults to 'localhost'
  :http/idle-timeout             - the max idle time in ms for a connection (default to 200000)
  :http/parse-timeout            - max time spend in parsing request (defaults to 30000)
  :http/max-headers-size         - max headers (all) size (defaults to 1 MiB)
  :http/max-body-size            - max body size (defaults to 6 MiB)
  :http/max-multipart-body-size  - max size for multipart uploads (defaults to 12 MiB)
  :http/max-cookies              - max number of allowed cookies in the request (defaults to 32)
  :http/max-headers              - max number of allowed headers in the request (defaults to 64)

  :xnio/buffer-size              - default http IO buffe size (default 64 KiB)
  :xnio/direct-buffers           - use or not direct buffers (default to false)
  :xnio/dispatch                 - dispatch or not the body of the handler to the worker executor
                                   (defaults to true, can be a custom executor instance)
  :websocket/idle-timeout        - websocket specific idle timeout (defaults to 500000)
  "
  ([handler-fn] (server handler-fn {}))
  ([handler-fn options]
   (let [options (merge defaults options)
         handler (create-handler handler-fn options)]
     (create-server handler options))))

(defn start!
  "Starts the jetty server. It accepts an optional `options` parameter
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
