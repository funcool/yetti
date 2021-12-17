# Jetty (11) adapter for Ring

Ring adapter for Jetty (11), with HTTP2 and WebSocket support.

This package is as fork of [rj9a][1] with cleaned api (with cosmetic
API changes and deprecated API removal). Additionally, it does not
depends on ring-servlet and uses the latest jakarta (not javax)
servlet API (look at [jetty10vs11][2] for more info). Many thanks to
@sunng87 for all the work on the original rj9a adatper.

[1]: https://github.com/sunng87/ring-jetty9-adapter
[2]: https://webtide.com/jetty-10-and-11-have-arrived

It requires JDK >= 11.

## Usage

On deps.edn:

```clojure
funcool/yetti {:git/tag "v1.0" :git/sha "e3d7794"
               :git/url "https://github.com/funcool/yetti.git"}
```

### Code

In the REPL:

```clojure
(require '[yetti.adapter :as yt])

(-> app
    (yt/server {:port 11010})
    (yt/start!))
```

### Ring Async handler

```clojure
(require '[yetti.adapter :as yt])

(defn app
  [request send-response raise-error]
  (send-response {:body "It works!"}))

(-> app
    (yt/server {:port 11010 :async? true})
    (yt/start!))
```

### HTTP/2

To enable HTTP/2 on cleartext and secure transport, you can simply add
options to `server` like:

```clojure
(yt/server app {:port 11010
                :h2c? true  ;; enable cleartext http/2
                :h2? true   ;; enable http/2
                :ssl? true  ;; ssl is required for http/2
                :ssl-port 11011
                :keystore "dev/keystore.jks"
                :key-password "111111"
                :keystore-type "jks"})
```

### WebSocket

Any ring handler can upgrade to websocket protocol, there is an example:

```clojure
(require '[yetti.websocket :as yws])

(defn handler
  [request]
  (if (yws/upgrade-request? request)
    (yws/upgrade request (fn [upgrade-request]
                           {:on-connect (fn [ws])
                            :on-error (fn [ws e])
                            :on-close (fn [ws status-code reason])
                            :on-text (fn [ws text-message])
                            :on-bytes (fn [ws bytes offset len])
                            :on-ping (fn [ws bytebuffer])
                            :on-pong (fn [ws bytebuffer])}))
    {:status 404}))
```

IWebSocket protocol allows you to read and write data on the `ws` value:

* `(yws/send! ws msg)`
* `(yws/send! ws msg callback)`
* `(yws/ping! ws msg)`
* `(yws/ping! ws msg callback)`
* `(yws/close! ws)`
* `(yws/remote-addr ws)`
* `(yws/idle-timeout! ws timeout)`

Notice that we support different type of msg:

* **byte[]** and **ByteBuffer**: send binary websocket message
* **String** and other Object: send text websocket message
* **(fn [ws])** (clojure function): Custom function you can operate on Jetty's [RemoteEndpoint][3]

[3]: https://www.eclipse.org/jetty/javadoc/jetty-11/org/eclipse/jetty/websocket/api/RemoteEndpoint.html

A callback can also be specified for `send!`:

```clojure
(yws/send! ws msg (fn [err])
;; The `err` is null if operation terminates successfuly

```

## License

```
Copyright © 2021-Now Andrey Antukh
Copyright © 2013-2021 Sun Ning
Distributed under the Eclipse Public License, the same as Clojure.
```
