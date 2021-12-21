(ns yetti.adapter
  (:require
   [yetti.util :as util]
   [yetti.websocket :as ws])
  (:import
   jakarta.servlet.AsyncContext
   jakarta.servlet.http.HttpServletRequest
   jakarta.servlet.http.HttpServletResponse
   org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory
   org.eclipse.jetty.server.ConnectionFactory
   org.eclipse.jetty.server.Connector
   org.eclipse.jetty.server.Handler
   org.eclipse.jetty.server.HttpConfiguration
   org.eclipse.jetty.server.HttpConnectionFactory
   org.eclipse.jetty.server.ProxyConnectionFactory
   org.eclipse.jetty.server.Request
   org.eclipse.jetty.server.Server
   org.eclipse.jetty.server.ServerConnector
   org.eclipse.jetty.server.handler.AbstractHandler
   org.eclipse.jetty.server.handler.HandlerList
   org.eclipse.jetty.servlet.ServletContextHandler
   org.eclipse.jetty.servlet.ServletHolder
   org.eclipse.jetty.util.thread.QueuedThreadPool
   org.eclipse.jetty.util.thread.ScheduledExecutorScheduler
   org.eclipse.jetty.util.thread.ThreadPool
   org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer))

;; (set! *warn-on-reflection* true)

(def base-defaults
  {:jetty/wrap-handler identity
   :http/output-buffer-size 32768
   :http/request-header-size 8192
   :http/response-header-size 8192
   :http/header-cache-size 512
   :http/port 11010
   :http/host "localhost"
   :http/protocols #{:h1 :h2c}
   :http/idle-timeout 200000

   :ring/async false

   :websockes/idle-timeut 500000
   :websockes/max-text-msg-size 65536
   :websockes/max-binary-msg-size 65536

   :thread-pool/max-threads 200
   :thread-pool/min-threads 5
   :thread-pool/idle-timeout 60000
   :thread-pool/job-queue nil
   :thread-pool/daemon true})

(defn- websocket-upgrade?
  [{:keys [status ws]}]
  (and (= 101 status) ws))

(defn- wrap-servlet-handler
  "Wraps a Jetty handler in a ServletContextHandler.

   Websocket upgrades require a servlet context which makes it
   necessary to wrap the handler in a servlet context handler."
  [handler]
  (doto (ServletContextHandler.)
    (.setContextPath "/*")
    (.setAllowNullPathInfo true)
    (JettyWebSocketServletContainerInitializer/configure nil)
    (.setHandler handler)))

(defn- wrap-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler options]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request ^HttpServletRequest request ^HttpServletResponse response]
      (try
        (let [request-map  (util/build-request-map request)
              response-map (handler request-map)]
          (when response-map
            (if (websocket-upgrade? response-map)
              (ws/upgrade-websocket request response (:ws response-map) options)
              (util/update-servlet-response response response-map))))
        (catch Throwable e
          (.sendError response 500 (.getMessage e)))
        (finally
          (.setHandled base-request true))))))

(defn- wrap-async-handler
  "Returns an Jetty Handler implementation for the given Ring **async** handler."
  [handler options]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request ^HttpServletRequest request ^HttpServletResponse response]
      (try
        (let [^AsyncContext context (.startAsync request)]
          (.setTimeout context (:http/idle-timeout options))
          (handler (util/build-request-map request)
                   (fn [response-map]
                     (if (websocket-upgrade? response-map)
                       (ws/upgrade-websocket request response context (:ws response-map) options)
                       (util/update-servlet-response response context response-map)))
                   (fn [^Throwable exception]
                     (.sendError response 500 (.getMessage exception))
                     (.complete context))))
        (finally
          (.setHandled base-request true))))))

(defn- create-http-config
  "Creates jetty http configurator"
  [{:keys [:http/output-buffer-size
           :http/request-header-size
           :http/response-header-size
           :http/header-cache-size]}]
  (doto (HttpConfiguration.)
    ;; (.setSecurePort secure-port)
    (.setSecureScheme "https")
    (.setOutputBufferSize output-buffer-size)
    (.setRequestHeaderSize request-header-size)
    (.setResponseHeaderSize response-header-size)
    (.setSendServerVersion false)
    (.setSendDateHeader false)
    (.setHeaderCacheSize header-cache-size)))

(defn- create-thread-pool
  [{:keys [:thread-pool/instance
           :thread-pool/job-queue
           :thread-pool/max-threads
           :thread-pool/min-threads
           :thread-pool/idle-timeout
           :thread-pool/daemon]}]
  (or instance
      (doto (QueuedThreadPool. (int max-threads)
                               (int min-threads)
                               (int idle-timeout)
                               job-queue)
        (.setDaemon daemon))))

(defn- create-connectors
  [server {:keys [:http/protocols
                  :http/port
                  :http/host
                  :http/idle-timeout]
           :as options}]
  (let [config    (create-http-config options)
        factories (cond-> []
                    (contains? protocols :h1)    (conj (HttpConnectionFactory. config))
                    (contains? protocols :h2c)   (conj (HTTP2CServerConnectionFactory. config))
                    (contains? protocols :proxy) (conj (ProxyConnectionFactory.)))

        connector (doto (ServerConnector.
                         ^Server server
                         ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
                         (into-array ConnectionFactory factories))
                    (.setPort port)
                    (.setHost host)
                    (.setIdleTimeout idle-timeout))]
    (into-array Connector [connector])))

(defn- create-handler
  "Creates an instance of the final jetty handler that will be
  attached to Server."
  [{:keys [:ring/async ::handler] :as options}]
  (let [wrap-jetty-handler (:jetty/wrap-handler options)]
    (wrap-jetty-handler
     (wrap-servlet-handler
      (if async
        (wrap-async-handler handler options)
        (wrap-handler handler options))))))

(defn- thread-pool?
  [o]
  (instance? ThreadPool o))

(defn- create-server
  "Construct a Jetty Server instance."
  [{:keys [thread-pool] :as options}]
  (let [pool   (create-thread-pool options)
        server (doto (Server. ^ThreadPool pool)
                 (.addBean (ScheduledExecutorScheduler.)))]
    (.setConnectors server (create-connectors server options))
    server))

(defn ^Server server
  "
  Creates and confgures an instance of jetty server. This is a list of options
  that you can provide:

  :ring/async                    - enables the ring 1.6 async handler
  :ring/version                  - specifies the ring version (only supports 1)

  :thread-pool/daemon            - use daemon threads (defaults to true)
  :thread-pool/max-threads       - the max number of threads to use (default 200)
  :thread-pool/min-threads       - the min number of threads to use (default 5)
  :thread-pool/idle-timeout      - the max idle time in milliseconds for a thread (default 60000)
  :thread-pool/job-queue         - the job queue to be used by the Jetty threadpool (default is unbounded)
  :thread-pool/instance          - specifies the thread pool used for jetty workloads. If you specifies
                                   this option, all the other will be ignored.

  :http/protocols                - a set of enabled protocols #{:h1 :h2c :proxy} (defaults to #{:h1 :h2c})
  :http/port                     - the port to listen on (defaults to 11010)
  :http/host                     - the hostname to listen on, defaults to 'localhost'
  :http/idle-timeout             - the max idle time in ms for a connection (default 200000)

  :websocket/idle-timeout        - the max idle time in ms for a websocket connection (default 500000)
  :websocket/max-text-msg-size   - the max text message size in bytes for a websocket connection (default 65536)
  :websocket/max-binary-msg-size - the max binary message size in bytes for a websocket connection (default 65536)

  :jetty/wrap-handler            - a wrapper fn that wraps default jetty handler into another, default to
                                   the `identity` fn, note that it's not a ring middleware
  "
  ([handler] (server handler {}))
  ([handler options]
   (let [options (merge base-defaults options)
         server  (create-server options)
         handler (create-handler (assoc options ::handler handler))]
     (.setHandler ^Server server ^Handler handler)
     server)))

(defn start!
  "Starts the jetty server. It accepts an optional `options` parameter
  that accepts the following attrs:

  :join - blocks the thread until the server is starts (defaults false)
  "
  ([server] (start! server {}))
  ([^Server server {:keys [join] :or {join false}}]
   (.start ^Server server)
   (when join (.join ^Server server))
   server))

(defn stop!
  "Stops the server."
  [^Server s]
  (.stop s))
