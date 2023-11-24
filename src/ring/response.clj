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

(ns ring.response
  "Core protocols and functions for Ring 2 response maps."
  (:require
   [clojure.java.io :as io]
   [ring.util.parsing :refer [re-charset]]))

(defprotocol Response
  "A protocol representing a HTTP response."
  (status  [resp])
  (headers [resp])
  (body    [resp]))

(defprotocol StreamableResponseBody
  "A protocol for writing data to the response body via an output stream."
  (-write-body-to-stream [body response output-stream]))

(defn write-body-to-stream
  "Write a value representing a response body to an output stream. The stream
  will be automically closed after the function ends."
  [response output-stream]
  (-write-body-to-stream (body response) response output-stream))

(defn charset
  "Given a response map, return the charset of the content-type header."
  [response]
  (when-let [content-type (-> response headers (get "content-type"))]
    (second (re-find re-charset content-type))))

(defn- ^java.io.Writer response-writer
  [response output-stream]
  (if-let [encoding (charset response)]
    (io/writer output-stream :encoding encoding)
    (io/writer output-stream)))

(extend-protocol Response
  clojure.lang.IPersistentMap
  (status  [resp] (or (::status resp) (:status resp)))
  (headers [resp] (or (::headers resp) (:headers resp)))
  (body    [resp] (or (::body resp) (:body resp))))

(extend-protocol StreamableResponseBody
  (Class/forName "[B")
  (-write-body-to-stream [body _ ^java.io.OutputStream output-stream]
    (with-open [out output-stream]
      (.write out ^bytes body)))
  String
  (-write-body-to-stream [body response output-stream]
    (with-open [writer (response-writer response output-stream)]
      (.write writer body)))
  clojure.lang.ISeq
  (-write-body-to-stream [body response output-stream]
    (with-open [writer (response-writer response output-stream)]
      (doseq [chunk body]
        (.write writer (str chunk)))))
  java.io.InputStream
  (-write-body-to-stream [body _ ^java.io.OutputStream output-stream]
    (with-open [out output-stream, body body]
      (io/copy body out)))
  java.io.File
  (-write-body-to-stream [body _ ^java.io.OutputStream output-stream]
    (with-open [out output-stream]
      (io/copy body out)))
  nil
  (-write-body-to-stream [_ _ ^java.io.OutputStream output-stream]
    (.close output-stream)))
