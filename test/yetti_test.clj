;; This tests code is derived from the ring package and adapted to
;; yetti needs.
;; Copyright (c) 2021-Now Andrey Antukh
;; EPL-1.0 License.

;; The original license of the code:
;; Copyright (c) 2009-2010 Mark McGranaghan
;; Copyright (c) 2009-2018 James Reeves
;;
;; Permission is hereby granted, free of charge, to any person
;; obtaining a copy of this software and associated documentation
;; files (the "Software"), to deal in the Software without
;; restriction, including without limitation the rights to use,
;; copy, modify, merge, publish, distribute, sublicense, and/or sell
;; copies of the Software, and to permit persons to whom the
;; Software is furnished to do so, subject to the following
;; conditions:
;;
;; The above copyright notice and this permission notice shall be
;; included in all copies or substantial portions of the Software.
;;
;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
;; EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
;; OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
;; NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
;; HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
;; WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
;; FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
;; OTHER DEALINGS IN THE SOFTWARE.

(ns yetti-test
  (:require
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [ring.core.protocols :as p]
   [yetti.adapter :as yt])
  (:import
   java.io.ByteArrayInputStream
   java.io.IOException
   java.io.InputStream
   java.io.SequenceInputStream
   java.net.ConnectException
   java.net.ServerSocket
   org.apache.http.MalformedChunkCodingException
   org.eclipse.jetty.server.Request
   org.eclipse.jetty.server.Server
   org.eclipse.jetty.util.thread.QueuedThreadPool))

(defn- hello-world [request]
  {:status  200
   :headers {"content-type" "text/plain"}
   :body    "Hello World"})

(defn client-cert-handler [request]
  (if (nil? (:ssl-client-cert request))
    {:status 403}
    {:status 200}))

(defn- content-type-handler [content-type]
  (constantly
   {:status  200
    :headers {"content-type" content-type}
    :body    ""}))

(defn- echo-handler [request]
  {:status 200
   :headers {"request-map" (str (dissoc request :body))}
   :body (:body request)})

(defmacro with-server [app options & body]
  `(let [server# (yt/server ~app ~options)
         server# (yt/start! server#)]
     (try
       ~@body
       (finally
         (yt/stop! server#)))))

(defn- find-free-local-port []
  (let [socket (ServerSocket. 0)]
    (let [port (.getLocalPort socket)]
      (.close socket)
      port)))

(def test-port (find-free-local-port))
(def test-url (str "http://localhost:" test-port))

(deftest test-http-server
  (with-server hello-world {:http/port test-port}
    (let [response (http/get test-url)]
      (is (= (:status response) 200))
      (is (.startsWith (get-in response [:headers "content-type"])
                       "text/plain"))
      (is (= (:body response) "Hello World")))))

(deftest test-daemon-threads
  (testing "default (daemon off)"
    (let [server (yt/server hello-world {:http/port test-port})]
      (is (.. server getThreadPool isDaemon))
      (.stop server)))
  (testing "daemon on"
    (let [server (yt/server hello-world {:http/port test-port :thread-pool/daemon true})]
      (is (.. server getThreadPool isDaemon))
      (.stop server)))
  (testing "daemon off"
    (let [server (yt/server hello-world {:http/port test-port :thread-pool/daemon false})]
      (is (not (.. server getThreadPool isDaemon)))
      (.stop server))))

(deftest test-set-max-idle-timeout
  (let [server (yt/server hello-world {:http/port test-port
                                       :http/idle-timeout 5000})
        connectors (. server getConnectors)]
    (is (= 5000 (. (first connectors) getIdleTimeout)))
    (.stop server)))

(deftest test-min-threads
  (let [server (yt/server hello-world {:http/port test-port :thread-pool/min-threads 3})
        thread-pool (. server getThreadPool)]
    (is (= 3 (. thread-pool getMinThreads)))
    (.stop server)))

(deftest test-thread-idle-timeout
  (let [server (yt/server hello-world {:http/port test-port :thread-pool/idle-timeout 1000})
        thread-pool (. server getThreadPool)]
    (is (= 1000 (. thread-pool getIdleTimeout)))
    (.stop server)))

(deftest test-providing-custom-thread-pool
  (let [pool   (QueuedThreadPool.)
        server (yt/server hello-world {:http/port test-port :thread-pool/instance pool})]
    (is (identical? pool (.getThreadPool server)))
    (.stop server)))

(deftest test-default-character-encoding
  (with-server (content-type-handler "text/plain") {:http/port test-port}
    (let [response (http/get test-url)]
      (is (.contains (get-in response [:headers "content-type"]) "text/plain")))))

(deftest test-custom-content-type
  (with-server (content-type-handler "text/plain;charset=UTF-16;version=1") {:http/port test-port}
    (let [response (http/get test-url)]
      (is (= (get-in response [:headers "content-type"])
             "text/plain;charset=UTF-16;version=1")))))

(deftest test-request-translation
  (with-server echo-handler {:http/port test-port}
    (let [response (http/post (str test-url "/foo/bar/baz?surname=jones&age=123") {:body "hello"})]
      (is (= (:status response) 200))
      (is (= (:body response) "hello"))
      (let [request-map (read-string (get-in response [:headers "request-map"]))]
        (is (= (:query-string request-map) "surname=jones&age=123"))
        (is (= (:uri request-map) "/foo/bar/baz"))
        (is (= (:content-length request-map) 5))
        (is (= (:character-encoding request-map) "UTF-8"))
        (is (= (:request-method request-map) :post))
        (is (= (:content-type request-map) "text/plain; charset=UTF-8"))
        (is (= (:remote-addr request-map) "127.0.0.1"))
        (is (= (:scheme request-map) :http))
        (is (= (:server-name request-map) "localhost"))
        (is (= (:server-port request-map) test-port))
        (is (= (:ssl-client-cert request-map) nil))))))

(defn- chunked-stream-with-error
  ([request]
   {:status  200
    :headers {"Transfer-Encoding" "chunked"}
    :body    (SequenceInputStream.
              (ByteArrayInputStream. (.getBytes (str (range 100000)) "UTF-8"))
              (proxy [InputStream] []
                (read
                  ([] (throw (IOException. "test error")))
                  ([^bytes _] (throw (IOException. "test error")))
                  ([^bytes _ _ _] (throw (IOException. "test error"))))))})
  ([request response raise]
   (response (chunked-stream-with-error request))))

(defn- chunked-lazy-seq-with-error
  ([request]
   {:status  200
    :headers {"Transfer-Encoding" "chunked"}
    :body    (lazy-cat (range 100000)
                       (throw (IOException. "test error")))})
  ([request response raise]
   (response (chunked-lazy-seq-with-error request))))

(deftest streaming-with-error
  (testing "chunked stream without sending termination chunk on error"
    (with-server chunked-stream-with-error {:http/port test-port}
      (is (thrown? MalformedChunkCodingException (http/get test-url)))))

  (testing "chunked sequence without sending termination chunk on error"
    (with-server chunked-lazy-seq-with-error {:http/port test-port}
      (is (thrown? MalformedChunkCodingException (http/get test-url)))))

  (testing "async chunked stream without sending termination chunk on error"
    (with-server chunked-stream-with-error {:http/port test-port :async? true}
      (is (thrown? MalformedChunkCodingException (http/get test-url)))))

  (testing "async chunked sequence without sending termination chunk on error"
    (with-server chunked-lazy-seq-with-error {:http/port test-port :async? true}
      (is (thrown? MalformedChunkCodingException (http/get test-url))))))

(def thread-exceptions (atom []))

(defn- hello-world-cps [request respond raise]
  (respond {:status  200
            :headers {"Content-Type" "text/plain"}
            :body    "Hello World"}))

(defn- hello-world-cps-future [request respond raise]
  (future
    (try (respond {:status  200
                   :headers {"Content-Type" "text/plain"}
                   :body    "Hello World"})
         (catch Exception ex
           (swap! thread-exceptions conj ex)))))

(defn- hello-world-streaming [request respond raise]
  (future
    (respond
     {:status  200
      :headers {"Content-Type" "text/event-stream"}
      :body    (reify p/StreamableResponseBody
                 (write-body-to-stream [_ _ output]
                   (future
                     (with-open [w (io/writer output)]
                       (Thread/sleep 100)
                       (.write w "data: hello\n\n")
                       (.flush w)
                       (Thread/sleep 100)
                       (.write w "data: world\n\n")
                       (.flush w)))))})))

(defn- hello-world-streaming-long [request respond raise]
  (respond
   {:status  200
    :headers {"Content-Type" "text/event-stream"}
    :body    (reify p/StreamableResponseBody
               (write-body-to-stream [_ _ output]
                 (future
                   (with-open [w (io/writer output)]
                     (dotimes [i 10]
                       (Thread/sleep 100)
                       (.write w (str "data: " i "\n\n"))
                       (.flush w))))))}))

(defn- error-cps [request respond raise]
  (raise (ex-info "test" {:foo "bar"})))

(defn- sometimes-error-cps [request respond raise]
  (if (= (:uri request) "/error")
    (error-cps request respond raise)
    (hello-world-cps request respond raise)))

(defn- hello-world-slow-cps [request respond raise]
  (future (Thread/sleep 1000)
          (respond {:status  200
                    :headers {"Content-Type" "text/plain"}
                    :body    "Hello World"})))

(deftest test-jetty-cps
  (testing "async response in future"
    (reset! thread-exceptions [])
    (with-server hello-world-cps-future {:http/port test-port, :ring/async true}
      (let [response (http/get test-url)]
        (Thread/sleep 100)
        (is (empty? @thread-exceptions))
        (is (= (:status response) 200))
        (is (.startsWith (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (:body response) "Hello World")))))

  (testing "async response"
    (with-server hello-world-cps {:http/port test-port, :ring/async true}
      (let [response (http/get test-url)]
        (is (= (:status response) 200))
        (is (.startsWith (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (:body response) "Hello World")))))

  (testing "streaming response"
    (with-server hello-world-streaming {:http/port test-port, :ring/async true}
      (let [response (http/get test-url)]
        (is (= (:status response) 200))
        (is (.startsWith (get-in response [:headers "content-type"])
                         "text/event-stream"))
        (is (= (:body response)
               "data: hello\n\ndata: world\n\n")))))

  (testing "error response"
    (with-server error-cps {:http/port test-port, :ring/async true}
      (let [response (http/get test-url {:throw-exceptions false})]
        (is (= (:status response) 500)))))

  (testing "mixed error with normal responses"
    (with-server sometimes-error-cps {:http/port test-port, :ring/async true}
      (let [response (http/get (str test-url "/error") {:throw-exceptions false})]
        (is (= (:status response) 500)))
      (let [response (http/get test-url {:throw-exceptions false})]
        (is (= (:status response) 200)))
      (let [response (http/get (str test-url "/error") {:throw-exceptions false})]
        (is (= (:status response) 500)))
      (let [response (http/get test-url {:throw-exceptions false})]
        (is (= (:status response) 200)))))

  (testing "async context default"
    (with-server hello-world-streaming-long {:http/port test-port, :ring/async true}
      (let [response (http/get test-url)]
        (is (= (:body response)
               (apply str (for [i (range 10)] (str "data: " i "\n\n"))))))))

  #_(testing "async timeout handler"
    (testing "when no timeout handler is passed, behaviour is unchanged"
      (with-server hello-world-slow-cps {:http/port test-port
                                         :ring/async true
                                         :ring/async-timeout 250}
        (let [response (http/get test-url {:throw-exceptions false})]
          (is (= (:status response)
                 500)))))

    (testing "with timeout handlers, ring-style responses are generated"
      (with-server hello-world-slow-cps
        {:http/port test-port
         :ring/async true
         :ring/async-timeout 200
         :ring/async-timeout-handler (fn [request respond raise]
                                  (respond
                                   {:status 503
                                    :headers {"Content-Type" "text/plain"}
                                    :body "Request timed out"}))}
        (let [response (http/get test-url {:throw-exceptions false})]
          (is (= (:body response)
                 "Request timed out"))
          (is (= (:status response)
                 503))))

      (with-server hello-world-slow-cps
        {:http/port test-port
         :ring/async true
         :ring/async-timeout 200
         :ring/async-timeout-handler (fn [request respond raise]
                                  (raise
                                   (ex-info "An exception was thrown" {})))}
        (let [response (http/get (str test-url "/test-path/testing")
                                 {:throw-exceptions false})]
          (is (.contains ^String (:body response)
                         "An exception was thrown"))
          (is (= (:status response)
                 500)))))))

(def call-count (atom 0))

(defn- broken-handler [request]
  (swap! call-count inc)
  (throw (ex-info "unhandled exception" {})))

(defn- broken-handler-cps [request respond raise]
  (swap! call-count inc)
  (raise (ex-info "unhandled exception" {})))

(testing "broken handler is only called once"
  (reset! call-count 0)
  (with-server broken-handler {:http/port test-port}
    (try (http/get test-url)
      (catch Exception _ nil))
    (is (= 1 @call-count))))

(testing "broken async handler is only called once"
  (reset! call-count 0)
  (with-server broken-handler-cps {:ring/async true :http/port test-port}
    (try (http/get test-url)
      (catch Exception _ nil))
    (is (= 1 @call-count))))
