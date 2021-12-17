(ns yetti.adapter
  (:require
   [yetti.util :as util]
   [yetti.websocket :as ws])
  (:import
   java.security.KeyStore
   jakarta.servlet.AsyncContext
   jakarta.servlet.http.HttpServletRequest
   jakarta.servlet.http.HttpServletResponse
   org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory
   org.eclipse.jetty.http2.HTTP2Cipher
   org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory
   org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory
   org.eclipse.jetty.server.ConnectionFactory
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
   org.eclipse.jetty.util.ssl.SslContextFactory
   org.eclipse.jetty.util.ssl.SslContextFactory$Server
   org.eclipse.jetty.util.thread.QueuedThreadPool
   org.eclipse.jetty.util.thread.ScheduledExecutorScheduler
   org.eclipse.jetty.util.thread.ThreadPool
   org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer))

;; (set! *warn-on-reflection* true)

(def base-defaults
  {:http true
   :http2 false
   :http2c false
   :async false
   :proxy false
   :port 11010
   :host "localhost"
   :output-buffer-size 32768
   :request-header-size 8192
   :response-header-size 8192
   :header-cache-size 512
   :max-idle-time 200000
   :ws-max-idle-time 500000
   :ws-max-msg-size 65536
   :wrap-jetty-handler identity})

(def ssl-defaults
  {:port 11011
   :scheme "https"
   :provider "Conscrypt"
   :protocols ["TLSv1.3" "TLSv1.2"]})

(def thread-pool-defaults
  {:max-threads 200
   :min-threads 5
   :idle-timeout 60000
   :job-queue nil
   :daemon true})

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
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request ^HttpServletRequest request ^HttpServletResponse response]
      (try
        (let [request-map  (util/build-request-map request)
              response-map (handler request-map)]
          (when response-map
            (if (websocket-upgrade? response-map)
              (ws/upgrade-websocket request response (:ws response-map) {})
              (util/update-servlet-response response response-map))))
        (catch Throwable e
          (.sendError response 500 (.getMessage e)))
        (finally
          (.setHandled base-request true))))))

(defn- wrap-async-handler
  "Returns an Jetty Handler implementation for the given Ring **async** handler."
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request ^HttpServletRequest request ^HttpServletResponse response]
      (try
        (let [^AsyncContext context (.startAsync request)]
          (handler (util/build-request-map request)
                   (fn [response-map]
                     (if (websocket-upgrade? response-map)
                       (ws/upgrade-websocket request response context (:ws response-map) {})
                       (util/update-servlet-response response context response-map)))
                   (fn [^Throwable exception]
                     (.sendError response 500 (.getMessage exception))
                     (.complete context))))
        (finally
          (.setHandled base-request true))))))

(defn- create-http-config
  "Creates jetty http configurator"
  [{:keys [output-buffer-size
           request-header-size
           response-header-size
           header-cache-size]
    :as options}]
  (let [secure-port   (or (get-in options [:ssl :port]) (:port ssl-defaults))
        secure-scheme (or (get-in options [:ssl :scheme]) (:scheme ssl-defaults))]
    (doto (HttpConfiguration.)
      (.setSecurePort secure-port)
      (.setSecureScheme secure-scheme)
      (.setOutputBufferSize output-buffer-size)
      (.setRequestHeaderSize request-header-size)
      (.setResponseHeaderSize response-header-size)
      (.setSendServerVersion false)
      (.setSendDateHeader false)
      (.setHeaderCacheSize header-cache-size))))

(defn- ^SslContextFactory$Server create-ssl-context-factory
  [{:keys [keystore keystore-type key-password client-auth key-manager-password
           truststore trust-password truststore-type protocols provider
           exclude-ciphers exclude-protocols]}]
  (let [context-server (SslContextFactory$Server.)]
    (.setCipherComparator context-server HTTP2Cipher/COMPARATOR)

    (when provider
      (.setProvider context-server provider))

    (if (string? keystore)
      (.setKeyStorePath context-server keystore)
      (.setKeyStore context-server ^KeyStore keystore))

    (when (string? keystore-type)
      (.setKeyStoreType context-server keystore-type))

    (.setKeyStorePassword context-server key-password)

    (when key-manager-password
      (.setKeyManagerPassword context-server key-manager-password))

    (cond
      (string? truststore) (.setTrustStorePath context-server truststore)
      (instance? KeyStore truststore) (.setTrustStore context-server ^KeyStore truststore))

    (when trust-password
      (.setTrustStorePassword context-server trust-password))

    (when truststore-type
      (.setTrustStoreType context-server truststore-type))

    (case client-auth
      :need (.setNeedClientAuth context-server true)
      :want (.setWantClientAuth context-server true)
      nil)

    (when (seq exclude-ciphers)
      (let [ciphers (into-array String exclude-ciphers)]
        (if (:replace (meta exclude-ciphers))
          (.setExcludeCipherSuites context-server ciphers)
          (.addExcludeCipherSuites context-server ciphers))))

    (when (seq protocols)
      (.setIncludeProtocols context-server (into-array String protocols)))

    (when (seq exclude-protocols)
      (let [protocols (into-array String exclude-protocols)]
        (if (:replace exclude-protocols)
          (.setExcludeProtocols context-server protocols)
          (.addExcludeProtocols context-server protocols))))

    context-server))

(defn- create-https-connector
  [server configuration {:keys [ssl http2 port host max-idle-time]}]
  (let [factories (cond-> []
                    (true? http2) (conj (ALPNServerConnectionFactory. "h2,http/1.1"))
                    (true? http2) (conj (HTTP2ServerConnectionFactory. configuration))
                    :always       (conj (HttpConnectionFactory. configuration)))]
    (doto (ServerConnector.
           ^Server server
           ^SslContextFactory$Server (create-ssl-context-factory ssl)
           ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
           (into-array ConnectionFactory factories))

      (.setPort port)
      (.setHost host)
      (.setIdleTimeout max-idle-time))))

(defn- create-http-connector
  [server configuration {:keys [http2c port host max-idle-time proxy]}]
  (let [factories (cond-> [(HttpConnectionFactory. configuration)]
                    (true? http2c) (conj (HTTP2CServerConnectionFactory. configuration))
                    (true? proxy)  (conj (ProxyConnectionFactory.)))]
    (doto (ServerConnector.
           ^Server server
           ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
           (into-array ConnectionFactory factories))
      (.setPort port)
      (.setHost host)
      (.setIdleTimeout max-idle-time))))

(defn- create-thread-pool
  [{:keys [job-queue max-threads min-threads idle-timeout daemon] :as options}]
  (doto (QueuedThreadPool. (int max-threads)
                           (int min-threads)
                           (int idle-timeout)
                           job-queue)
    (.setDaemon daemon)))

(defn- create-connectors
  [server {:keys [ssl http1 http2c] :as options}]
  (let [config (create-http-config options)
        ssl?   (:enabled ssl)
        http?  (or http2c http1)]
    (cond-> []
      ssl?    (conj (create-https-connector server config options))
      http?   (conj (create-http-connector server config options))
      :always (->> (into-array org.eclipse.jetty.server.Connector)))))

(defn- create-handler
  "Creates an instance of the final jetty handler that will be
  attached to Server."
  [{:keys [wrap-jetty-handler async]} handler]
  (wrap-jetty-handler
   (wrap-servlet-handler
    (if async
      (wrap-async-handler handler)
      (wrap-handler handler)))))

(defn- thread-pool?
  [o]
  (instance? ThreadPool o))

(defn- create-server
  "Construct a Jetty Server instance."
  [{:keys [thread-pool] :as options}]
  (let [pool   (cond
                 (thread-pool? thread-pool) thread-pool
                 (map? thread-pool)         (create-thread-pool thread-pool)
                 :else                      (throw (IllegalArgumentException. "invalid thread-pool argument")))
        server (doto (Server. ^ThreadPool pool)
                 (.addBean (ScheduledExecutorScheduler.)))]
    (.setConnectors server (create-connectors server options))
    server))

(defn ^Server server
  "
  Creates and confgures an instance of jetty server. This is a list of top-level options
  that you can provide:

  :http                 - enable http1 protocol
  :http2                - enable http2 protocol (h2) on secure socket port
  :http2c               - enable http2 clear text (h2c) on plain socket port
  :proxy                - enable proxy protocol on plain socket port

  :port                 - the port to listen on (defaults to 11010)
  :host                 - the hostname to listen on
  :async                - enables the ring 1.6 async handler

  :ssl                  - enables HTTPS (TLS), accepts options (see below)
  :thread-pool          - specifies the thread pool used for jetty workloads. Can be an instance
                          or map for configuring the default one. See below for options.

  :max-idle-time        - the maximum idle time in milliseconds for a connection (default 200000)
  :ws-max-idle-time     - the maximum idle time in milliseconds for a websocket connection (default 500000)
  :ws-max-msg-size      - the maximum text message size in bytes for a websocket connection (default 65536)
  :wrap-jetty-handler   - a wrapper fn that wraps default jetty handler into another,
                          default to `identity`, not that it's not a ring middleware

  The ssl options are (specified under `:ssl` option):

  :port                 - the SSL/TLS port to listen on (defaults to 443)
  :keystore             - the keystore to use for SSL connections
  :keystore-type        - the format of keystore
  :key-password         - the password to the keystore
  :key-manager-password - the password for key manager
  :truststore           - a truststore to use for SSL connections
  :truststore-type      - the format of trust store
  :trust-password       - the password to the truststore
  :protocols            - the SSL/TLS protocols to use, default to [\"TLSv1.3\" \"TLSv1.2\"]
  :provider             - the SSL/TLS provider, default to \"Conscrypt\"
  :exclude-ciphers      - additionally exclude these cipher suites
  :exclude-protocols    - additionally exclude these protocols
  :client-auth          - SSL/TLS client certificate authenticate, may be set to :need, :want
                          or :none (defaults to :none)

  Thread Pool configuration options (specified under `:thread-pool` option):

  :daemon               - use daemon threads (defaults to true)
  :max-threads          - the maximum number of threads to use (default 200)
  :min-threads          - the minimum number of threads to use (default 5)
  :idle-timeout         - the maximum idle time in milliseconds for a thread (default 60000)
  :job-queue            - the job queue to be used by the Jetty threadpool (default is unbounded)
  "
  ([handler] (server handler {}))
  ([handler options]
   (let [options (merge base-defaults options)
         options (cond-> options
                   (contains? options :ssl)
                   (update :ssl #(merge ssl-defaults %))

                   (map? (:thread-pool options))
                   (update :thread-pool #(merge thread-pool-defaults %))

                   (nil? (:thread-pool options))
                   (assoc :thread-pool thread-pool-defaults))

         server  (create-server options)
         handler (create-handler options handler)]
     (.setHandler ^Server server ^Handler handler)
     server)))

(defn start!
  ([server] (start! server {}))
  ([^Server server {:keys [join?] :or {join? false}}]
   (.start ^Server server)
   (when join? (.join ^Server server))
   server))

(defn stop!
  [^Server s]
  (.stop s))
