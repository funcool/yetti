;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright © Andrey Antukh <niwi@niwi.nz>

(ns yetti.util
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   io.undertow.server.HttpServerExchange
   io.undertow.server.handlers.Cookie
   io.undertow.server.handlers.CookieImpl
   io.undertow.server.handlers.form.FormData
   io.undertow.server.handlers.form.FormData$FileItem
   io.undertow.server.handlers.form.FormData$FormValue
   io.undertow.server.handlers.form.FormDataParser
   io.undertow.server.handlers.form.FormEncodedDataDefinition
   io.undertow.server.handlers.form.FormParserFactory
   io.undertow.server.handlers.form.MultiPartParserDefinition
   io.undertow.util.HeaderMap
   io.undertow.util.HeaderValues
   io.undertow.util.HttpString
   io.undertow.util.Headers
   java.lang.reflect.Field
   java.nio.file.Paths
   java.time.Duration
   java.time.Instant
   java.util.Collections
   java.util.Date
   java.util.Deque
   java.util.HashMap
   java.util.Iterator
   java.util.Map
   java.util.Map
   java.util.Map$Entry
   java.util.concurrent.Executor
   yetti.util.ByteBufferHelpers))

(set! *warn-on-reflection* true)

(def default-temp-dir
  (Paths/get "/tmp/undertow/" (into-array String [])))

(def re-charset
  #"(?x);(?:.*\s)?(?i:charset)=(?:
      ([!\#$%&'*\-+.0-9A-Z\^_`a-z\|~]+)|  # token
      \"((?:\\\"|[^\"])*)\"               # quoted
    )\s*(?:;|$)")

(defn get-exchange
  [request]
  (or (:yetti.adapter/exchange request)
      (:exchange request)))

(defn- headers->map
  [^HeaderMap headers]
  (loop [m {}
         c (.fastIterateNonEmpty headers)]
    (if (pos? c)
      (let [hvs ^HeaderValues (.fiCurrent headers c)
            hk (.. hvs getHeaderName toString toLowerCase)
            hv (if (= 1 (.size hvs)) (.getFirst hvs) (str/join "," hvs))]
        (recur (assoc m hk hv)
               (.fiNext headers c)))
      m)))

(defn parser-factory
  [{:keys [item-max-size temp-dir executor]
    :or {item-max-size -1
         temp-dir default-temp-dir}}]
  (let [multipart (doto (MultiPartParserDefinition.)
                    (.setFileSizeThreshold 0)
                    (.setMaxIndividualFileSize item-max-size)
                    (.setTempFileLocation temp-dir)
                    (.setDefaultEncoding "UTF-8")
                    (.setExecutor ^Executor executor))
        xform     (doto (FormEncodedDataDefinition.)
                    (.setDefaultEncoding "UTF-8"))]
    (.. (FormParserFactory/builder)
        (withParsers [xform multipart])
        (build))))

(defn parse-query-data
  ([request] (parse-query-data request {}))
  ([request {:keys [key-fn] :or {key-fn keyword}}]
   (when-let [^HttpServerExchange exchange (get-exchange request)]
     (let [params  (.getQueryParameters exchange)
           entries (.entrySet ^Map params)
           it      (.iterator entries)]
       (loop [rs {}]
         (if (.hasNext ^Iterator it)
           (let [item (.next ^Iterator it)
                 k    (.getKey ^Map$Entry item)
                 v    (.getValue ^Map$Entry item)]
             (if (= 1 (.size ^Deque v))
               (recur (assoc rs (key-fn k) (.peek ^Deque v)))
               (recur (assoc rs (key-fn k) (into [] v)))))
           rs))))))

(defn set-cookies!
  [^HttpServerExchange exchange cookies]
  (let [^Map rcookies (.getResponseCookies exchange)]
    (doseq [[k cookie-map] cookies]
      (let [{:keys [path value domain max-age expires same-site secure http-only comment]} cookie-map
            item (doto (CookieImpl. ^String k ^String (str value))
                   (cond-> (boolean? secure)
                     (.setSecure ^Boolean secure))
                   (cond-> (string? comment)
                     (.setComment ^String comment))
                   (cond-> (string? path)
                     (.setPath ^String path))
                   (cond-> (string? domain)
                     (.setDomain ^String domain))
                   (cond-> (boolean? http-only)
                     (.setHttpOnly ^Boolean http-only))
                   (cond-> (int? max-age)
                     (.setMaxAge ^Integer (int max-age)))
                   (cond-> (instance? Duration max-age)
                     (.setMaxAge ^Integer (int (.getSeconds ^Duration max-age))))
                   (cond-> (instance? Instant expires)
                     (.setExpires ^Date (Date/from expires)))
                   (cond-> (instance? Date expires)
                     (.setExpires ^Date expires))
                   (cond-> (keyword? same-site)
                     (.setSameSiteMode (case same-site
                                         :lax "Lax"
                                         :strict "Strict"
                                         :none "None")))
                   (cond-> (string? same-site)
                     (.setSameSiteMode ^Strict same-site)))]

        (.put ^Map rcookies ^String k ^Cookie item)))))

(defn- parse-form-value
  [^String key ^FormData$FormValue fval]
  (if (.isFileItem fval)
    (let [^FormData$FileItem fitem (.getFileItem fval)
          headers (headers->map (.getHeaders fval))
          mtype   (get headers "content-type")]
      (cond-> {:name key
               :headers headers
               :filename (.getFileName fval)
               :path (.getFile fitem)
               :size (.getFileSize fitem)}
        (some? mtype)
        (assoc :mtype mtype)))
    (.getValue fval)))

(defn- append-form-entry
  [val v]
  (cond
    (nil? val)
    v

    (vector? val)
    (conj val v)

    :else
    [val v]))

(defn parse-form-data
  ([request] (parse-form-data request {}))
  ([request {:keys [key-fn] :or {key-fn keyword} :as options}]
   (when-let [exchange (get-exchange request)]
     (let [factory (parser-factory options)
           parser  (.createParser ^FormParserFactory factory
                                  ^HttpServerExchange exchange)
           form    (some-> parser .parseBlocking)]
       (reduce (fn [result key]
                 (let [fval (.get ^FormData form ^String key)]
                   (if (instance? FormData$FormValue fval)
                     (update result key append-form-entry (parse-form-value key fval))
                     (reduce (fn [result fval]
                               (update result key append-form-entry (parse-form-value key fval)))
                             result
                             fval))))
                {}
                (seq form))))))

(defn get-request-header
  [^HttpServerExchange exchange ^String name]
  (let [^HeaderMap headers (.getRequestHeaders exchange)]
    (when-let [^HeaderValues entry (.get headers name)]
      (if (= 1 (.size entry))
        (.getFirst entry)
        (str/join "," entry)))))

(defn get-request-headers
  "Creates a name/value map of all the request headers."
  [^HttpServerExchange exchange]
  (headers->map (.getRequestHeaders exchange)))

(defn- parse-cookie
  [^Cookie cookie]
  {:name (.getName cookie)
   :value (.getValue cookie)})

(defn get-request-cookies
  [^HttpServerExchange exchange]
  (into {}
        (map (fn [[k cookie]]
               [k (parse-cookie cookie)]))
        (.getRequestCookies ^HttpServerExchange exchange)))

(defn get-request-cookie
  [^HttpServerExchange exchange ^String name]
  (let [^Map cookies (.getRequestCookies ^HttpServerExchange exchange)]
    (some-> (.get cookies name) parse-cookie)))

(defn copy-many
  [data]
  (ByteBufferHelpers/copyMany data))
