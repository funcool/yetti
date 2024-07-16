;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright © Andrey Antukh <niwi@niwi.nz>

(ns yetti.websocket
  (:require
   [clojure.string :as str]
   [ring.websocket.protocols :as rwp]
   [yetti.request :as yrq]
   [yetti.util :as yu])
  (:import
   clojure.lang.IFn
   io.undertow.server.HttpServerExchange
   io.undertow.websockets.WebSocketConnectionCallback
   io.undertow.websockets.WebSocketProtocolHandshakeHandler
   io.undertow.websockets.core.AbstractReceiveListener
   io.undertow.websockets.core.BufferedBinaryMessage
   io.undertow.websockets.core.BufferedTextMessage
   io.undertow.websockets.core.CloseMessage
   io.undertow.websockets.core.WebSocketCallback
   io.undertow.websockets.core.WebSocketChannel
   io.undertow.websockets.core.WebSocketUtils
   io.undertow.websockets.core.WebSockets
   io.undertow.websockets.extensions.PerMessageDeflateHandshake
   io.undertow.websockets.spi.WebSocketHttpExchange
   java.nio.ByteBuffer
   java.util.concurrent.CompletableFuture
   java.util.concurrent.CompletionException
   java.util.concurrent.ExecutionException
   java.util.concurrent.atomic.AtomicBoolean
   org.xnio.Pooled
   yetti.util.WebSocketListenerWrapper))

(set! *warn-on-reflection* true)

(defn- fn->callback
  [succeed fail]
  (reify WebSocketCallback
    (complete [_ _ _]
      (succeed))
    (onError [_ _ _ cause]
      (fail cause))))

(defn- cf->callback
  [^CompletableFuture ft]
  (reify WebSocketCallback
    (complete [_ _ _]
      (.complete ft nil))
    (onError [_ _ _ cause]
      (.completeExceptionally ft cause))))

(extend-type WebSocketChannel
  rwp/Socket
  (-open? [this]
    (. this (isOpen)))

  (-send [this msg]
    (let [ft (CompletableFuture.)]
      (cond
        (string? msg)
        (WebSockets/sendText ^String msg
                             ^WebSocketChannel this
                             ^WebSocketCallback (cf->callback ft))

        (bytes? msg)
        (WebSockets/sendBinary ^ByteBuffer (ByteBuffer/wrap ^bytes msg)
                               ^WebSocketChannel this
                               ^WebSocketCallback (cf->callback ft))


        (instance? ByteBuffer msg)
        (WebSockets/sendBinary ^ByteBuffer (ByteBuffer/wrap ^bytes msg)
                               ^WebSocketChannel this
                               ^WebSocketCallback (cf->callback ft))

        :else
        (throw (IllegalArgumentException. "invalid message")))

      (.get ft)))

  (-ping [this msg]
    (let [ft  (CompletableFuture.)
          msg (cond
                (bytes? msg) (ByteBuffer/wrap ^bytes msg)
                (instance? ByteBuffer msg) msg
                (string? msg) (WebSocketUtils/fromUtf8String ^String msg)
                :else (throw (IllegalArgumentException. "invalid mesage type")))]
      (WebSockets/sendPing ^ByteBuffer msg
                           ^WebSocketChannel this
                           ^WebSocketCallback (cf->callback ft))
      (.get ft)))

  (-pong [this msg]
    (let [ft  (CompletableFuture.)
          msg (cond
                (bytes? msg) (ByteBuffer/wrap ^bytes msg)
                (instance? ByteBuffer msg) msg
                (string? msg) (WebSocketUtils/fromUtf8String ^String msg)
                :else (throw (IllegalArgumentException. "invalid mesage type")))]
      (WebSockets/sendPong ^ByteBuffer msg
                           ^WebSocketChannel this
                           ^WebSocketCallback (cf->callback ft))
      (.get ft)))

  (-close [this code reason]
    (when (some? code)
      (.setCloseCode this code))
    (when (some? reason)
      (.setCloseReason this reason))
    (.sendClose this)
    (.close this))

  rwp/AsyncSocket
  (-send-async [this msg succeed fail]
    (cond
      (string? msg)
      (WebSockets/sendText ^String msg
                           ^WebSocketChannel this
                           ^WebSocketCallback (fn->callback succeed fail))

      (bytes? msg)
      (WebSockets/sendBinary ^ByteBuffer (ByteBuffer/wrap ^bytes msg)
                             ^WebSocketChannel this
                             ^WebSocketCallback (fn->callback succeed fail))

      (instance? ByteBuffer msg)
      (WebSockets/sendBinary ^ByteBuffer (ByteBuffer/wrap ^bytes msg)
                             ^WebSocketChannel this
                             ^WebSocketCallback (fn->callback succeed fail))

      :else
      (throw (IllegalArgumentException. "invalid message")))))

(defn add-close-callback!
  "Adds on-close task to the websocket channel. Returns `channel`
  instance."
  [^WebSocketChannel channel callback]
  (.addCloseTask channel
                 (reify org.xnio.ChannelListener
                   (handleEvent [_ channel]
                     (try
                       (callback channel)
                       (catch Throwable cause)))))
  channel)

(defn set-idle-timeout!
  [^WebSocketChannel channel ms]
  (if (integer? ms)
    (.. channel (setIdleTimeout ^long ms))
    (.. channel (setIdleTimeout ^long (inst-ms ms))))
  channel)

(defn get-remote-addr
  [^WebSocketChannel channel]
  (.. channel (getDestinationAddress)))

(defn upgrade-request?
  "Checks if a request is a websocket upgrade request.

  This is a ring2 aware, more efficient version of
  `ring.websocket/upgrade-request?` function."
  [request]
  (let [upgrade    (yrq/get-header request "upgrade")
        connection (yrq/get-header request "connection")]
    (and (string? upgrade)
         (string? connection)
         (str/includes? (str/lower-case upgrade) "websocket")
         (str/includes? (str/lower-case connection) "upgrade"))))

(defn open?
  [socket]
  (boolean (rwp/-open? socket)))

(defn send
  ([socket message]
   (rwp/-send socket message))
  ([socket message succeed fail]
   (rwp/-send-async socket message succeed fail)))

(defn ping
  ([socket]
   (rwp/-ping socket (ByteBuffer/allocate 0)))
  ([socket data]
   (rwp/-ping socket data)))

(defn pong
  ([socket]
   (rwp/-pong socket (ByteBuffer/allocate 0)))
  ([socket data]
   (rwp/-pong socket data)))

(defn close
  ([socket]
   (rwp/-close socket 1000 ""))
  ([socket code reason]
   (rwp/-close socket code reason)))

(defn upgrade-request?
  "Returns true if the request map is a websocket upgrade request."
  [request]
  (let [{{:strs [connection upgrade]} :headers} request]
    (and upgrade
         connection
         (re-find #"\b(?i)upgrade\b" connection)
         (.equalsIgnoreCase "websocket" upgrade))))

(defn websocket-response?
  "Returns true if the response contains a websocket listener."
  [response]
  (contains? response ::listener))

(defn request-protocols
  "Returns a collection of websocket subprotocols from a request map."
  [request]
  (some-> (:headers request)
          (get "sec-websocket-protocol")
          (str/split #"\s*,\s*")))

(defn- listener->handler
  [listener]
  (WebSocketProtocolHandshakeHandler.
   (reify WebSocketConnectionCallback
     (onConnect [_ exchange channel]
       (let [setter     (.getReceiveSetter ^WebSocketChannel channel)
             closed     (AtomicBoolean. false)

             on-message (fn [channel message]
                          (rwp/on-message listener channel message))
             on-pong    (fn [channel buffers]
                          (rwp/on-pong listener channel (yu/copy-many buffers)))
             on-ping    (fn [channel buffers]
                          (rwp/on-ping listener channel (yu/copy-many buffers)))
             on-error   (fn [channel cause]
                          (when (.compareAndSet ^AtomicBoolean closed false true)
                            (rwp/on-error listener channel cause)))
             on-close   (fn [channel code reason]
                          (when (.compareAndSet ^AtomicBoolean closed false true)
                            (rwp/on-close listener channel code reason)))]

         (rwp/on-open listener channel)

         (add-close-callback! channel #(on-close % -1 "connection interrumpted"))

         (.set setter (WebSocketListenerWrapper. on-message
                                                 on-ping
                                                 on-pong
                                                 on-error
                                                 on-close))
         (.resumeReceives ^WebSocketChannel channel))))))


(defn upgrade-response
  [^HttpServerExchange exchange listener options]

  (assert (or (satisfies? rwp/Listener listener)
              (fn? listener))
          "listener should satisfy Listener protocol or be a callback")

  (let [^WebSocketProtocolHandshakeHandler handler (listener->handler listener)]
    (.addExtension handler (PerMessageDeflateHandshake. false 6))
    (.handleRequest handler exchange)))
