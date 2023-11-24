;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns user
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint print-table]]
   [clojure.repl :refer :all]
   [clojure.test :as test]
   [clojure.tools.namespace.repl :as repl]
   [clojure.walk :refer [macroexpand-all]]
   [promesa.core :as p]
   [promesa.exec :as px]
   [ring.request :as rreq]
   [ring.response :as rres]
   [ring.websocket :as rws]
   [taoensso.nippy :as nippy]
   [criterium.core  :as crit]
   [yetti.adapter :as yt]
   [yetti.middleware :as ymw]
   [yetti.util :as yu])
  (:import
   java.io.InputStream
   java.io.OutputStream
   java.util.concurrent.ForkJoinPool))

(defmacro run-quick-bench
  [& exprs]
  `(crit/with-progress-reporting (crit/quick-bench (do ~@exprs) :verbose)))


(defn run-tests
  ([] (run-tests #"^yetti-test.*$"))
  ([o]
   (repl/refresh)
   (cond
     (instance? java.util.regex.Pattern o)
     (test/run-all-tests o)

     (symbol? o)
     (if-let [sns (namespace o)]
       (do (require (symbol sns))
           (test/test-vars [(resolve o)]))
       (test/test-ns o)))))

(defn hello-http-handler
  [request]

  {::rres/status 200
   ::rres/headers {"content-type" "text/plain"
                   "test" "foooo"
                   "x-foo-bar" ["baz" "foo"]}
   ::rres/body    (with-out-str
                    (println "Values:")
                    (prn (yu/parse-query-data request))
                    (prn (rreq/headers request)))

   ::rres/cookies {"sample-cookie" {:value (rand-int 1000)
                                    :same-site :lax
                                    :path "/foo"
                                    :domain "localhost"
                                    :max-age 2000}}})

;; (defn hello-http-handler
;;   [request]
;;   (prn "hello-world-handler" (Thread/currentThread))
;;   (prn "request" request)
;;   (prn "request" "query-params:" (:query-params request))
;;   (prn "request" "body-params:" (:body-params request))
;;   (prn "request" "params:" (:params request))
;;   {::rres/status 200
;;    ::rres/headers {"content-type" "application/octet-stream"}
;;    ::rres/body (reify rres/StreamableResponseBody
;;                  (-write-body-to-stream [_ _ output-stream]
;;                    (try
;;                      (with-open [^InputStream input (io/input-stream "caddy_linux_amd64")]
;;                        (io/copy input output-stream))
;;                      (catch java.io.IOException _)
;;                      (finally
;;                        (.close ^OutputStream output-stream)))))})

(def server nil)

(defn- on-error
  [cause request]
  (prn "on-error" cause))

(defn- start
  []
  (let [options {:xnio/io-threads 2
                 :xnio/direct-buffers true
                 :http/on-error on-error
                 :ring/compat :ring2}
        handler (-> hello-http-handler
                    (ymw/wrap-server-timing)
                    (ymw/wrap-params)
                    )]

    (alter-var-root #'server (fn [server]
                               (when server (yt/stop! server))
                               (-> (yt/server handler options)
                                   (yt/start!))))
    :started))

(defn- stop
  []
  (alter-var-root #'server (fn [server]
                             (when server (yt/stop! server))
                             nil))
  :stoped)

(defn restart
  []
  (stop)
  (repl/refresh :after 'user/start))


(defn -main
  [& args]
  (start))
