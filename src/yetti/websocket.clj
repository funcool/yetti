;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright © Andrey Antukh <niwi@niwi.nz>

(ns yetti.websocket
  (:require
   [clojure.string :as str]
   [yetti.request :as req]
   [yetti.util :as util])
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
   io.undertow.websockets.core.WebSockets
   io.undertow.websockets.extensions.PerMessageDeflateHandshake
   io.undertow.websockets.spi.WebSocketHttpExchange
   io.undertow.websockets.core.WebSocketUtils
   yetti.util.WebSocketListenerWrapper
   org.xnio.Pooled
   java.nio.ByteBuffer))

(set! *warn-on-reflection* true)

(defprotocol IWebSocket
  (send! [this msg] [this msg cb])
  (ping! [this msg] [this msg cb])
  (pong! [this msg] [this msg cb])
  (close! [this] [this status-code reason])
  (remote-addr [this])
  (idle-timeout! [this ms])
  (connected? [this]))

(defprotocol IWebSocketSend
  (-send! [x ws] [x ws cb] "How to encode content sent to the WebSocket clients"))

(defprotocol IWebSocketPing
  (-ping! [x ws] [x ws cb] "How to encode bytes sent with a ping"))

(defprotocol IWebSocketPong
  (-pong! [x ws] [x ws cb] "How to encode bytes sent with a pong"))

(def ^:private no-op (constantly nil))

(defn wrap-callback
  [cb]
  (reify WebSocketCallback
    (complete [_ _ _]
      (cb nil))
    (onError [_ _ _ cause]
      (cb cause))))

(extend-protocol IWebSocketSend
  (Class/forName "[B")
  (-send!
    ([ba channel] (-send! (ByteBuffer/wrap ba) channel))
    ([ba channel cb] (-send! (ByteBuffer/wrap ba) channel cb)))

  ByteBuffer
  (-send!
    ([bb channel] (WebSockets/sendBinaryBlocking ^ByteBuffer bb ^WebSocketChannel channel))
    ([bb channel cb] (WebSockets/sendBinary ^ByteBuffer bb
                                            ^WebSocketChannel channel
                                            ^WebSocketCallback (wrap-callback cb))))
  String
  (-send!
    ([s channel] (WebSockets/sendTextBlocking ^String s ^WebSocketChannel channel))
    ([s channel cb] (WebSockets/sendText ^String s
                                         ^WebSocketChannel channel
                                         ^WebSocketCallback (wrap-callback cb)))))

(extend-protocol IWebSocketPing
  (Class/forName "[B")
  (-ping!
    ([ba channel] (-ping! (ByteBuffer/wrap ba) channel))
    ([ba channel cb] (-ping! (ByteBuffer/wrap ba) channel cb)))

  String
  (-ping!
    ([s channel] (-ping! (WebSocketUtils/fromUtf8String s) channel))
    ([s channel cb] (-ping! (WebSocketUtils/fromUtf8String s) channel cb)))

  ByteBuffer
  (-ping!
    ([bb channel] (WebSockets/sendPingBlocking ^ByteBuffer bb ^WebSocketChannel channel))
    ([bb channel cb] (WebSockets/sendPing ^ByteBuffer bb
                                          ^WebSocketChannel channel
                                          ^WebSocketCallback (wrap-callback cb)))))


(extend-protocol IWebSocketPong
  (Class/forName "[B")
  (-pong!
    ([ba channel] (-pong! (ByteBuffer/wrap ba) channel))
    ([ba channel cb] (-pong! (ByteBuffer/wrap ba) channel cb)))

  String
  (-pong!
    ([s channel] (-pong! (WebSocketUtils/fromUtf8String s) channel))
    ([s channel cb] (-pong! (WebSocketUtils/fromUtf8String s) channel cb)))

  ByteBuffer
  (-pong!
    ([bb channel] (WebSockets/sendPongBlocking ^ByteBuffer bb ^WebSocketChannel channel))
    ([bb channel cb] (WebSockets/sendPong ^ByteBuffer bb
                                          ^WebSocketChannel channel
                                          ^WebSocketCallback (wrap-callback cb)))))

(extend-protocol IWebSocket
  WebSocketChannel
  (send!
    ([this msg] (-send! msg this))
    ([this msg cb] (-send! msg this cb)))
  (ping!
    ([this msg] (-ping! msg this))
    ([this msg cb] (-ping! msg this cb)))
  (pong!
    ([this msg] (-pong! msg this))
    ([this msg cb] (-pong! msg this cb)))
  (close! [this]
    (.. this (close)))
  (close! [this code reason]
    (.setCloseCode this code)
    (.setCloseReason this reason)
    (.sendClose this)
    (.close this))
  (remote-addr [this]
    (.. this (getDestinationAddress)))
  (idle-timeout! [this ms]
    (if (integer? ms)
      (.. this (setIdleTimeout ^long ms))
      (.. this (setIdleTimeout ^long (inst-ms ms)))))
  (connected? [this]
    (. this (isOpen))))

(defn upgrade-request?
  "Checks if a request is a websocket upgrade request."
  [request]
  (let [upgrade    (req/get-header request "upgrade")
        connection (req/get-header request "connection")]
    (and upgrade
         connection
         (str/includes? (str/lower-case upgrade) "websocket")
         (str/includes? (str/lower-case connection) "upgrade"))))

(defn- create-websocket-receiver
  [{:keys [on-error on-text on-close on-bytes on-ping on-pong]}]
  (WebSocketListenerWrapper. on-text
                             on-bytes
                             on-ping
                             on-pong
                             on-error
                             on-close))

(defn create-websocket-connecton-callback
  ^WebSocketConnectionCallback
  [on-connect {:keys [:websocket/idle-timeout]}]
  (reify WebSocketConnectionCallback
    (onConnect [_ exchange channel]
      (some->> idle-timeout long (.setIdleTimeout channel))
      (let [setter   (.getReceiveSetter ^WebSocketChannel channel)
            handlers (on-connect exchange channel)]
        (.set setter (create-websocket-receiver handlers))
        (.resumeReceives ^WebSocketChannel channel)))))

(defn upgrade-response
  [^HttpServerExchange exchange on-connect options]
  (let [callback (create-websocket-connecton-callback on-connect options)
        handler  (WebSocketProtocolHandshakeHandler. callback)]
    (.addExtension handler (PerMessageDeflateHandshake. false 6))
    (.handleRequest handler exchange)))

(defn upgrade
  "Returns a websocket upgrade response."
  [request handler]
  {::upgrade (fn [^WebSocketHttpExchange exchange ^WebSocketChannel channel]
               (-> request
                   (assoc ::exchange exchange)
                   (assoc ::channel channel)
                   (dissoc :body)
                   (handler)))})
