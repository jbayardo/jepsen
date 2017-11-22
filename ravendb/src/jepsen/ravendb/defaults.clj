(ns jepsen.ravendb.defaults
  (:require [clojure.string :as str]))

(def install-path "/opt/ravendb")

(def executable-name "Raven.Server")

(def executable-path (str "Server/" executable-name))

(def log-file-path (str install-path "/jepsen.log"))

(def pid-file-path (str install-path "/jepsen.pid"))

(def license
  (->> (slurp "license.json")
       (#(str/replace % #"\r?\n" ""))
       (#(str/replace % #"\s+" " "))
       ))

(def assigned-cores-per-node 2)

(def studio-port 8888)

(def client-port 38888)

(def server-startup-grace-time-milliseconds 10000)

(defn download-url
  "Generate the URL to download RavenDB from"
  [version]
  (str "http://hibernatingrhinos.com/downloads/RavenDB%20for%20Ubuntu%2016.04%20x64/" version))

(defn into-url
  [protocol address port]
  (str protocol "://" address ":" port))

(defn server-url
  [node]
  (into-url "http" "0.0.0.0" studio-port))

(defn server-url-tcp
  [node]
  (into-url "tcp" "0.0.0.0" client-port))

(defn public-server-url
  [node]
  (into-url "http" (name node) studio-port))

(defn public-server-url-tcp
  [node]
  (into-url "tcp" (name node) client-port))

(defn format-arguments
  "Formats a dictionary as --key=value, or --key if the value is an empty string. This is the argument format used by
  the RavenDB server"
  [arguments]
  (map
    (fn [[real-key value]]
      (let [key (name real-key)
            kv-pair (if (str/blank? value) key (str key "=" value))]
        (str "--" kv-pair))) arguments))

(defn executable-arguments
  "Arguments string to pass into each server instance when executed"
  [node]
  (format-arguments
    {
     :ServerUrl                       (server-url node),
     :ServerUrl.Tcp                   (server-url-tcp node),
     :PublicServerUrl                 (public-server-url node),
     :PublicServerUrl.Tcp             (public-server-url-tcp node),
     :Security.UnsecuredAccessAllowed "PublicNetwork"
     }))