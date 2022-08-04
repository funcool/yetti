# Undertow adapter for Ring

[Ring](https://github.com/ring-clojure/ring) adapter for
[Undertow](https://undertow.io/), with HTTP2C and WebSocket support.

This adapter is intended to be used under a http proxy for TLS
offloding (such that NGINX or HAPROXY); this is the reason there are
no options for configure SSL/TLS. This is a practical decission to not
maintain code that is almost never used on our own use cases.

It requires JDK >= 11.

**WARNING**: this adapter does not depends on ring becase it is based
on ring-2.0 branch, and until ring-2.0 is released, some of the ring2
protocols built-in in this adapter. It is compatible with the map based
ring responses.

## Usage

### Quick Start

On deps.edn:

```clojure
funcool/yetti {:git/tag "v9.4" :git/sha "13d6434"
               :git/url "https://github.com/funcool/yetti.git"}
```

In the REPL:

```clojure
(require '[yetti.adapter :as yt])

(-> app
    (yt/server {:http/port 11010})
    (yt/start!))
```


### Ring Async

This adapter supports the ring async interface:

```clojure
(require '[yetti.adapter :as yt]
         '[yetti.response :as yrs])

(defn app
  [request respond raise]
  (respond (yrs/response 200 "It works!")))

(-> app
    (yt/server {:http/port 11010 :ring/async true})
    (yt/start!))
```

### WebSocket

Any ring handler can upgrade to websocket protocol, there is an example:

```clojure
(require '[yetti.websocket :as yws])

(defn handler
  [request]
  (if (yws/upgrade-request? request)
    (yws/upgrade request (fn [upgrade-request]
                           {:on-open  (fn [ws])
                            :on-error (fn [ws e])
                            :on-close (fn [ws status-code reason])
                            :on-text  (fn [ws text-message])
                            :on-bytes (fn [ws bytebuffers])
                            :on-ping  (fn [ws bytebuffers])
                            :on-pong  (fn [ws bytebuffers])}))
    (yrs/response 404)))
```

IWebSocket protocol allows you to read and write data on the `ws` value:

- `(yws/send! ws msg)`
- `(yws/send! ws msg callback)`
- `(yws/ping! ws msg)`
- `(yws/ping! ws msg callback)`
- `(yws/pong! ws msg)`
- `(yws/pong! ws msg callback)`
- `(yws/close! ws)`
- `(yws/remote-addr ws)`
- `(yws/idle-timeout! ws timeout)`

Notice that we support different type of msg:

* **byte[]** and **ByteBuffer**: send binary websocket message
* **String**: send text websocket message

A callback can also be specified for `send!`, `ping!` or `pong!`:

```clojure
(yws/send! ws msg (fn [err])
;; The `err` is null if operation terminates successfuly
```

## License

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.

Copyright © 2021-Now Andrey Antukh
```
