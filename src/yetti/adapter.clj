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

(defn normalize-response
  "Normalize response for ring spec"
  [response]
  (if (string? response)
    {:body response}
    response))

(defn- websocket-upgrade?
  [{:keys [status ws]}]
  (and (= 101 status) ws))

;; TODO: check if it really needs
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
              response-map (-> request-map handler normalize-response)]
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
                     (let [response-map (normalize-response response-map)]
                       (if (websocket-upgrade? response-map)
                         (ws/upgrade-websocket request response context (:ws response-map) {})
                         (util/update-servlet-response response context response-map))))
                   (fn [^Throwable exception]
                     (.sendError response 500 (.getMessage exception))
                     (.complete context))))
        (finally
          (.setHandled base-request true))))))

(defn- create-http-config
  "Creates jetty http configurator"
  [{:keys [ssl-port secure-scheme output-buffer-size request-header-size
           response-header-size send-server-version? send-date-header?
           header-cache-size]
    :or {ssl-port 443
         secure-scheme "https"
         output-buffer-size 32768
         request-header-size 8192
         response-header-size 8192
         send-server-version? true
         send-date-header? false
         header-cache-size 512}}]

  (doto (HttpConfiguration.)
    (.setSecureScheme secure-scheme)
    (.setSecurePort ssl-port)
    (.setOutputBufferSize output-buffer-size)
    (.setRequestHeaderSize request-header-size)
    (.setResponseHeaderSize response-header-size)
    (.setSendServerVersion send-server-version?)
    (.setSendDateHeader send-date-header?)
    (.setHeaderCacheSize header-cache-size)))

(defn- ^SslContextFactory$Server create-ssl-context-factory
  [{:keys [keystore keystore-type key-password client-auth key-manager-password
           truststore trust-password truststore-type ssl-protocols ssl-provider
           exclude-ciphers replace-exclude-ciphers? exclude-protocols replace-exclude-protocols?]}]
  (let [context-server (SslContextFactory$Server.)]
    (.setCipherComparator context-server HTTP2Cipher/COMPARATOR)
    (when ssl-provider
      (.setProvider context-server ssl-provider))
    (if (string? keystore)
      (.setKeyStorePath context-server keystore)
      (.setKeyStore context-server ^KeyStore keystore))
    (when (string? keystore-type)
      (.setKeyStoreType context-server keystore-type))
    (.setKeyStorePassword context-server key-password)
    (when key-manager-password
      (.setKeyManagerPassword context-server key-manager-password))
    (cond
      (string? truststore)
      (.setTrustStorePath context-server truststore)
      (instance? KeyStore truststore)
      (.setTrustStore context-server ^KeyStore truststore))
    (when trust-password
      (.setTrustStorePassword context-server trust-password))
    (when truststore-type
      (.setTrustStoreType context-server truststore-type))
    (case client-auth
      :need (.setNeedClientAuth context-server true)
      :want (.setWantClientAuth context-server true)
      nil)
    (when-let [exclude-ciphers exclude-ciphers]
      (let [ciphers (into-array String exclude-ciphers)]
        (if replace-exclude-ciphers?
          (.setExcludeCipherSuites context-server ciphers)
          (.addExcludeCipherSuites context-server ciphers))))
    (when ssl-protocols
      (.setIncludeProtocols context-server (into-array String ssl-protocols)))
    (when exclude-protocols
      (let [protocols (into-array String exclude-protocols)]
        (if replace-exclude-protocols?
          (.setExcludeProtocols context-server protocols)
          (.addExcludeProtocols context-server protocols))))
    context-server))

(defn- create-https-connector
  [server http-configuration ssl-context-factory h2? port host max-idle-time]
  (let [secure-connection-factory (concat (when h2? [(ALPNServerConnectionFactory. "h2,http/1.1")
                                                     (HTTP2ServerConnectionFactory. http-configuration)])
                                          [(HttpConnectionFactory. http-configuration)])]
    (doto (ServerConnector.
           ^Server server
           ^SslContextFactory$Server ssl-context-factory
           ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
           (into-array ConnectionFactory secure-connection-factory))
      (.setPort port)
      (.setHost host)
      (.setIdleTimeout max-idle-time))))

(defn- create-http-connector
  [server http-configuration h2c? port host max-idle-time proxy?]
  (let [plain-connection-factories (cond-> [(HttpConnectionFactory. http-configuration)]
                                     h2c? (concat [(HTTP2CServerConnectionFactory. http-configuration)])
                                     proxy? (concat [(ProxyConnectionFactory.)]))]
    (doto (ServerConnector.
           ^Server server
           ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
           (into-array ConnectionFactory plain-connection-factories))
      (.setPort port)
      (.setHost host)
      (.setIdleTimeout max-idle-time))))

(defn- create-thread-pool
  [{:keys [job-queue max-threads min-threads thread-pool threadpool-idle-timeout daemon?]
    :or {max-threads 200
         min-threads 5
         threadpool-idle-timeout 60000
         daemon? false}}]
  (or thread-pool
      (doto (QueuedThreadPool. (int max-threads)
                               (int min-threads)
                               (int threadpool-idle-timeout)
                               job-queue)
        (.setDaemon daemon?))))

(defn- create-connectors
  [server {:keys [ssl? ssl-port http? h2? h2c? max-idle-time proxy? host port]
           :or {ssl? false
                host "0.0.0.0"
                port 11010
                proxy?  false
                http? true
                max-idle-time 200000}
           :as options}]

  (let [config      (create-http-config options)
        ssl?        (or ssl? ssl-port)
        ssl-factory (create-ssl-context-factory options)]

    (cond-> []
      ssl?    (conj (create-https-connector server config ssl-factory h2? ssl-port host max-idle-time))
      http?   (conj (create-http-connector server config h2c? port host max-idle-time proxy?))
      :always (->> (into-array org.eclipse.jetty.server.Connector)))))

(defn- create-server
  "Construct a Jetty Server instance."
  [options]
  (let [pool   (create-thread-pool options)
        server (doto (Server. ^ThreadPool pool)
                 (.addBean (ScheduledExecutorScheduler.)))]
    (.setConnectors server (create-connectors server options))
    server))

(defn ^Server server
  "
  Start a Jetty webserver to serve the given handler according to the
  supplied options:

  :http? - allow connections over HTTP
  :port - the port to listen on (defaults to 80)
  :host - the hostname to listen on
  :async? - using Ring 1.6 async handler?
  :join? - blocks the thread until server ends (defaults to true)
  :daemon? - use daemon threads (defaults to false)
  :ssl? - allow connections over HTTPS
  :ssl-port - the SSL port to listen on (defaults to 443, implies :ssl?)
  :keystore - the keystore to use for SSL connections
  :keystore-type - the format of keystore
  :key-password - the password to the keystore
  :key-manager-password - the password for key manager
  :truststore - a truststore to use for SSL connections
  :truststore-type - the format of trust store
  :trust-password - the password to the truststore
  :ssl-protocols - the ssl protocols to use, default to [\"TLSv1.3\" \"TLSv1.2\"]
  :ssl-provider - the ssl provider, default to \"Conscrypt\"
  :exclude-ciphers      - when :ssl? is true, additionally exclude these
                          cipher suites
  :exclude-protocols    - when :ssl? is true, additionally exclude these
                          protocols
  :replace-exclude-ciphers?   - when true, :exclude-ciphers will replace rather
                                than add to the cipher exclusion list (defaults
                                to false)
  :replace-exclude-protocols? - when true, :exclude-protocols will replace
                                rather than add to the protocols exclusion list
                                (defaults to false)
  :thread-pool - the thread pool for Jetty workload
  :max-threads - the maximum number of threads to use (default 50), ignored if `:thread-pool` provided
  :min-threads - the minimum number of threads to use (default 8), ignored if `:thread-pool` provided
  :threadpool-idle-timeout - the maximum idle time in milliseconds for a thread (default 60000), ignored if `:thread-pool` provided
  :job-queue - the job queue to be used by the Jetty threadpool (default is unbounded), ignored if `:thread-pool` provided
  :max-idle-time  - the maximum idle time in milliseconds for a connection (default 200000)
  :ws-max-idle-time  - the maximum idle time in milliseconds for a websocket connection (default 500000)
  :ws-max-text-message-size  - the maximum text message size in bytes for a websocket connection (default 65536)
  :client-auth - SSL client certificate authenticate, may be set to :need, :want or :none (defaults to :none)
  :h2? - enable http2 protocol on secure socket port
  :h2c? - enable http2 clear text on plain socket port
  :proxy? - enable the proxy protocol on plain socket port (see http://www.eclipse.org/jetty/documentation/9.4.x/configuring-connectors.html#_proxy_protocol)
  :wrap-jetty-handler - a wrapper fn that wraps default jetty handler into another, default to `identity`, not that it's not a ring middleware
  "
  [handler {:keys [async? wrap-jetty-handler]
            :or {wrap-jetty-handler identity}
            :as options}]
  (let [server  (create-server options)
        handler (wrap-jetty-handler
                 (wrap-servlet-handler
                  (if async?
                    (wrap-async-handler handler)
                    (wrap-handler handler))))]
    (.setHandler ^Server server ^Handler handler)
    server))

(defn start!
  ([server] (start! server {}))
  ([^Server server {:keys [join?] :or {join? false}}]
   (.start ^Server server)
   (when join? (.join ^Server server))
   server))

(defn stop!
  [^Server s]
  (.stop s))
