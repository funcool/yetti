;; This code is derived from the ring-servlet package and adapted
;; yetti needs. It is not possible to use the ring-servlet package
;; directly because of the javax to jakarta java package name change.
;;
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

(ns yetti.util
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ring.core.protocols :as rp])
  (:import
   jakarta.servlet.AsyncContext
   jakarta.servlet.http.HttpServletRequest
   jakarta.servlet.http.HttpServletResponse
   java.io.File
   java.io.FileInputStream
   java.io.InputStream
   java.util.Locale))

;; (set! *warn-on-reflection* true)

(defn get-headers
  "Creates a name/value map of all the request headers."
  [^HttpServletRequest request]
  (reduce
    (fn [headers, ^String name]
      (assoc headers
        (.toLowerCase name Locale/ENGLISH)
        (->> (.getHeaders request name)
             (enumeration-seq)
             (str/join ","))))
    {}
    (enumeration-seq (.getHeaderNames request))))

(defn get-content-length
  "Returns the content length, or nil if there is no content."
  [^HttpServletRequest request]
  (let [length (.getContentLength request)]
    (if (>= length 0) length)))

(defn build-request-map
  "Create the request map from the HttpServletRequest object."
  [^HttpServletRequest request]
  {:server-port        (.getServerPort request)
   :server-name        (.getServerName request)
   :remote-addr        (.getRemoteAddr request)
   :uri                (.getRequestURI request)
   :query-string       (.getQueryString request)
   :scheme             (keyword (.getScheme request))
   :request-method     (keyword (.toLowerCase (.getMethod request) Locale/ENGLISH))
   :protocol           (.getProtocol request)
   :headers            (get-headers request)
   :content-type       (.getContentType request)
   :content-length     (get-content-length request)
   :character-encoding (.getCharacterEncoding request)
   :ssl-client-cert    nil
   :body               (.getInputStream request)})

(defn set-headers!
  "Update a HttpServletResponse with a map of headers."
  [^HttpServletResponse response, headers]
  (doseq [[key val-or-vals] headers]
    (if (string? val-or-vals)
      (.setHeader response key val-or-vals)
      (doseq [val val-or-vals]
        (.addHeader response key val))))

  (when-let [content-type (get headers "content-type")]
    (.setContentType response content-type)))

(defn- make-output-stream
  [^HttpServletResponse response ^AsyncContext context]
  (let [os (.getOutputStream response)]
    (if (nil? context)
      os
      (proxy [java.io.FilterOutputStream] [os]
        (write
          ([b]         (.write os ^bytes b))
          ([b off len] (.write os ^bytes b off len)))
        (close []
          (.close os)
          (.complete context))))))

(defn update-servlet-response
  "Update the HttpServletResponse using a response map. Takes an optional
  AsyncContext."
  ([response response-map]
   (update-servlet-response response nil response-map))
  ([^HttpServletResponse response context response-map]
   (let [{:keys [status headers body]} response-map]
     (when (nil? response)
       (throw (NullPointerException. "HttpServletResponse is nil")))
     (when (nil? response-map)
       (throw (NullPointerException. "Response map is nil")))
     (when status
       (.setStatus response status))
     (set-headers! response headers)
     (let [output-stream (make-output-stream response context)]
       (rp/write-body-to-stream body response-map output-stream)))))
