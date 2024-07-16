# Undertow based HTTP server for Clojure

A pragmatic and efficient ring adapter for the high performance
**undertow** http server.

Relevant characteristics:

- Unified http and websocket handlers; there are no separate routings
  for http and http. Any http request can be upgraded to websocket.
- No HTTPS support; this is intended to be used behind http proxy
  which is in most cases the responsible of handling TLS; this is a
  practical decission to not maintain code that is almost never used
  in practice (when serving content to the internet).
- Based on ring-2.0 for performance reasons and it is the default, but
  it also can work in a ring1 compliant mode.
- By default uses Virtual Threads for request dispatching so, it
  requires JDK >= 21 (although you have the option of using platform
  threads as well).
- No ring-async support, with virtual threads there are no real need
  for callback based API, so we opted to directly to no support it.


**NOTE: currently, the future of ring-2.0 is completely
uncertain. Regardless of that, this library only depends on ring's
stable APIs (protocols), and the rest of ring-2.0's ideas and
proposals are bundled internally.**


## Usage

### Quick Start

On deps.edn:

```clojure
funcool/yetti
{:git/tag "v10.0"
 :git/sha "520613f"
 :git/url "https://github.com/funcool/yetti.git"}
```

In the REPL:

```clojure
(require '[yetti.adapter :as yt]
         '[yett.response :as yrs])

;; Using Response type

(defn handler
  [request]
  {::yrs/status 200
   ::yrs/body "hello world"})

(-> handler
    (yt/server {:http/port 11010})
    (yt/start!))
```

If you want a ring1 compatible request:

```clojure
(require '[yetti.adapter :as yt])

(defn handler
  [{:keys [request-method] :as request}]
  {:status 200
   :body (str "hello world " (name request-method))})

(-> handler
    (yt/server {:http/port 11010 :ring/compat :ring1})
    (yt/start!))
```

The possible values for `:ring/compat` are:

 - `:ring2`: the default, receives a ring2 compatible, map-like type
   (with main focus on performance) and expectes a ring2 or ring1
   response to be returned indistinctly
 - `:ring1`: receives a ring1 compliant map and expectes ring1
   response to be returned


### WebSocket

Any handler can upgrade to websocket protocol, there is an example:

```clojure
(require '[yetti.websocket :as yws]
         '[yetti.response :as-alias yrs]
         '[yetti.websocket :as-alias yws])

(defn handler
  [request]
  ;; We prefer use `yws/upgrade-request?` over `rws/upgrade-request?`
  ;; in case if you use ring2 requests, for performance reasons.
  (if (yws/upgrade-request? request)
    {::yws/listener {:on-open  (fn [ws])
                     :on-error (fn [ws e])
                     :on-close (fn [ws status-code reason])
                     :on-message  (fn [ws message])
                     :on-ping  (fn [ws data])
                     :on-pong  (fn [ws data])}}
    {::yrs/status 404}))
```

This is the main API for interacting with websocket channel/connection:

- `(yws/open? ws msg)`
- `(yws/send ws msg)`
- `(yws/send ws msg callback)`
- `(yws/ping ws msg)`
- `(yws/pong ws msg)`
- `(yws/close ws)`
- `(yws/get-remote-addr ws)`
- `(yws/set-idle-timeout! ws timeout)`


Notice that we support different type of msg:

* **byte[]** and **ByteBuffer**: send binary websocket message
* **String**: send text websocket message

All this internally uses the `ring-websocket-protocols` so it has full
interop with already existing ring websocket code.


## License

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.

Copyright © 2021-Now Andrey Antukh
```
