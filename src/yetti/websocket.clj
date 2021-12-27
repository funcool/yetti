(ns yetti.websocket
  (:require
   [clojure.string :as str]
   [yetti.util :as util])
  (:import
   clojure.lang.IFn
   java.nio.ByteBuffer
   java.time.Duration
   java.util.Locale
   javax.servlet.AsyncContext
   javax.servlet.http.HttpServlet
   javax.servlet.http.HttpServletRequest
   javax.servlet.http.HttpServletResponse
   org.eclipse.jetty.servlet.ServletHolder
   org.eclipse.jetty.websocket.api.RemoteEndpoint
   org.eclipse.jetty.websocket.api.Session
   org.eclipse.jetty.websocket.api.WebSocketAdapter
   org.eclipse.jetty.websocket.api.WebSocketPingPongListener
   org.eclipse.jetty.websocket.api.WriteCallback
   org.eclipse.jetty.websocket.common.JettyExtensionConfig
   org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest
   org.eclipse.jetty.websocket.server.JettyWebSocketCreator
   org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer))

;; (set! *warn-on-reflection* true)

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
  (reify WriteCallback
    (writeFailed [_ throwable]
      (cb throwable))
    (writeSuccess [_]
      (cb nil))))

(extend-protocol IWebSocketSend
  (Class/forName "[B")
  (-send!
    ([ba ws] (-send! (ByteBuffer/wrap ba) ws))
    ([ba ws cb] (-send! (ByteBuffer/wrap ba) ws cb)))

  ByteBuffer
  (-send!
    ([bb ws] (-> ^WebSocketAdapter ws .getRemote (.sendBytes ^ByteBuffer bb)))
    ([bb ws cb] (-> ^WebSocketAdapter ws .getRemote (.sendBytes ^ByteBuffer bb ^WriteCallback (wrap-callback cb)))))

  String
  (-send!
    ([s ws] (-> ^WebSocketAdapter ws .getRemote (.sendString ^String s)))
    ([s ws cb] (-> ^WebSocketAdapter ws .getRemote (.sendString ^String s ^WriteCallback (wrap-callback cb)))))

  IFn
  (-send! [f ws] (-> ^WebSocketAdapter ws .getRemote f)))

(extend-protocol IWebSocketPing
  (Class/forName "[B")
  (-ping!
    ([ba ws] (-ping! (ByteBuffer/wrap ba) ws))
    ([ba ws cb] (-ping! (ByteBuffer/wrap ba) ws cb)))

  ByteBuffer
  (-ping!
    ([bb ws] (-> ^WebSocketAdapter ws .getRemote (.sendPing ^ByteBuffer bb)))
    ([bb ws cb] (-> ^WebSocketAdapter ws .getRemote (.sendPing ^ByteBuffer bb ^WriteCallback (wrap-callback cb)))))

  String
  (-ping!
    ([s ws] (-ping! (ByteBuffer/wrap (.getBytes s)) ws))
    ([s ws cb] (-ping! (ByteBuffer/wrap (.getBytes s)) ws cb))))


(extend-protocol IWebSocketPong
  (Class/forName "[B")
  (-pong!
    ([ba ws] (-pong! (ByteBuffer/wrap ba) ws))
    ([ba ws cb] (-pong! (ByteBuffer/wrap ba) ws cb)))

  ByteBuffer
  (-pong!
    ([bb ws] (-> ^WebSocketAdapter ws .getRemote (.sendPong ^ByteBuffer bb)))
    ([bb ws cb] (-> ^WebSocketAdapter ws .getRemote (.sendPong ^ByteBuffer bb ^WriteCallback (wrap-callback cb)))))

  String
  (-pong!
    ([s ws] (-pong! (ByteBuffer/wrap (.getBytes s)) ws))
    ([s ws cb] (-pong! (ByteBuffer/wrap (.getBytes s)) ws cb))))


(extend-protocol IWebSocket
  WebSocketAdapter
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
    (.. this (getSession) (close)))
  (close! [this status-code reason]
    (.. this (getSession) (close status-code reason)))
  (remote-addr [this]
    (.. this (getSession) (getRemoteAddress)))
  (idle-timeout! [this ms]
    (.. this (getSession) (setIdleTimeout ^long ms)))
  (connected? [this]
    (. this (isConnected))))

(defn- create-websocket-adapter
  [{:keys [on-connect on-error on-text on-close on-bytes on-ping on-pong]
    :or {on-connect no-op
         on-error no-op
         on-text no-op
         on-close no-op
         on-bytes no-op
         on-ping no-op
         on-pong no-op}}]
  (proxy [WebSocketAdapter WebSocketPingPongListener] []
    (onWebSocketConnect [^Session session]
      (let [^WebSocketAdapter this this]
        (proxy-super onWebSocketConnect session))
      (on-connect this))
    (onWebSocketError [^Throwable e]
      (on-error this e))
    (onWebSocketText [^String message]
      (on-text this message))
    (onWebSocketClose [statusCode ^String reason]
      (let [^WebSocketAdapter this this]
        (proxy-super onWebSocketClose statusCode reason))
      (on-close this statusCode reason))
    (onWebSocketBinary [^bytes payload offset len]
      (on-bytes this payload offset len))
    (onWebSocketPing [^ByteBuffer bytebuffer]
      (on-ping this bytebuffer))
    (onWebSocketPong [^ByteBuffer bytebuffer]
      (on-pong this bytebuffer))))

(defn create-websocket-creator
  [factory-fn]
  (reify JettyWebSocketCreator
    (createWebSocket [this req resp]
      (let [handlers (factory-fn req)]
        (if-let [{:keys [code message headers]} (:error handlers)]
          (do
            (util/set-headers! resp headers)
            (.sendError resp code message))

          (do
            (when-let [sp (:subprotocol handlers)]
              (.setAcceptedSubProtocol resp sp))
            (when-let [exts (seq (:extensions handlers))]
              (.setExtensions resp (mapv #(JettyExtensionConfig. ^String %) exts)))

            (create-websocket-adapter handlers)))))))

(defn upgrade-websocket
  ([req res ws options] (upgrade-websocket req res nil ws options))
  ([^HttpServletRequest req ^HttpServletResponse res ^AsyncContext async-context ws
    {:keys [:websocket/idle-timeout
            :websocket/max-text-msg-size
            :websocket/max-binary-msg-size]}]
   (let [creator   (create-websocket-creator ws)
         container (JettyWebSocketServerContainer/getContainer (.getServletContext req))]
     (.setIdleTimeout container (Duration/ofMillis idle-timeout))
     (.setMaxTextMessageSize container max-text-msg-size)
     (.setMaxBinaryMessageSize container max-binary-msg-size)
     (.upgrade container creator req res)
     (when async-context
       (.complete async-context)))))

(defn upgrade-request?
  "Checks if a request is a websocket upgrade request."
  [{:keys [headers]}]
  (let [upgrade    (get headers "upgrade")
        connection (get headers "connection")]
    (and upgrade
         connection
         (str/includes? (str/lower-case upgrade) "websocket")
         (str/includes? (str/lower-case connection) "upgrade"))))

(defn upgrade
  "Returns a websocket upgrade response.

   ws-handler must be a map of handler fns:
   {:on-connect #(create-fn %)               ; ^Session ws-session
    :on-text   #(text-fn % %2 %3 %4)         ; ^Session ws-session message
    :on-bytes  #(binary-fn % %2 %3 %4 %5 %6) ; ^Session ws-session payload offset len
    :on-close  #(close-fn % %2 %3 %4)        ; ^Session ws-session statusCode reason
    :on-error  #(error-fn % %2 %3)}          ; ^Session ws-session e
   or a custom creator function take upgrade request as parameter and returns a handler fns map,
   negotiated subprotocol and extensions (or error info).

   The response contains HTTP status 101 (Switching Protocols)
   and the following headers:
   - connection: upgrade
   - upgrade: websocket
   "
  [request ws-handler]
  (let [ws-handler (cond (map? ws-handler) (constantly ws-handler)
                         (fn? ws-handler)  ws-handler
                         :else             (throw (IllegalArgumentException. "ws-handler should be a map or fn")))]

    {:status 101 ;; http 101 switching protocols
     :headers {"upgrade" "websocket"
               "connection" "upgrade"}
     :ws (fn [^JettyServerUpgradeRequest req]
           (-> request
               (assoc :websocket-subprotocols (into [] (.getSubProtocols req)))
               (assoc :websocket-extensions (into [] (.getExtensions req)))
               (dissoc :body)
               (ws-handler)))}))
