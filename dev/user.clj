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
   [clojure.tools.namespace.repl :as repl]
   [clojure.walk :refer [macroexpand-all]]))

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

(defn hello-world-handler
  [request]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body "Hello world\n"})
