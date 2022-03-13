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
   [yetti.adapter :as yt]
   [yetti.websocket :as yw]
   [yetti.util :as yu]
   [yetti.middleware :as ymw]
   [yetti.response :as resp]
   [promesa.core :as p]
   [promesa.exec :as px]
   [taoensso.nippy :as nippy]
   [clojure.tools.namespace.repl :as repl]
   [clojure.walk :refer [macroexpand-all]])
  (:import
   java.util.concurrent.ForkJoinPool))

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
  ([request]
   ;; (prn "hello-world-handler" "sync" (yu/tname))
   ;; (prn "request" "query-params:" (:query-params request))
   ;; (prn "request" "body-params:" (:body-params request))
   ;; (prn "request" "params:" (:oparams request))

   {:status 200
    :headers {"content-type" "application/octet-stream"
              "x-foo-bar" ["baz" "foo"]}
    :body (nippy/freeze nippy/stress-data)
    :cookies {"sample-cookie" {:value (rand-int 1000)
                               :same-site :lax
                               :path "/foo"
                               :domain "localhost"
                               :max-age 2000}}})

  ([request respond raise]
   ;; (prn "hello-world-handler" "async" (yu/tname))
   ;; (prn "request" "query-params:" (:query-params request))
   ;; (prn "request" "body-params:" (:body-params request))
   ;; (prn "request" "params:" (:params request))

   (respond
    (resp/response
     :status  200
     :body    (nippy/fast-freeze nippy/stress-data)
     :headers {"content-type" "application/octet-stream"
               "x-foo-bar" ["foo" "bar"]}))))

     ;; :cookies {"sample-cookie" {:value (rand-int 1000)
     ;;                            :same-site :lax
     ;;                            :path "/foo"
     ;;                            :domain "localhost"
     ;;                            :max-age 2000}}))))

(defn hello-websocket-handler
  [request respond raise]
  (respond
   (yw/upgrade request (fn [request]
                         {:on-open (fn [channel]
                                     (prn "ws:on-connect" (yu/tname)))
                          :on-text (fn [channel message]
                                     (prn "ws:on-text" message (yu/tname))
                                     (yw/send! channel message))
                          :on-close (fn [channel code reason]
                                      (prn "ws:on-close" code reason (yu/tname)))
                          :on-error (fn [channel cause]
                                      (prn "on-error" (yu/tname) cause))}))))

(def server nil)

(defn- start
  []
  (let [options {:ring/async true
                 :xnio/io-threads 2
                 :xnio/direct-buffers true
                 :xnio/worker-threads 6
                 :xnio/dispatch true #_(ForkJoinPool/commonPool)}
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
