(ns jepsen.ravendb
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
             [control :as c]
             [db :as db]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [jepsen.core :as jepsen]))

;; See https://stackoverflow.com/questions/21098784/is-there-an-idiomatic-way-to-avoid-long-clojure-string-literals
(defmacro compile-time-slurp
  "Read a file into Clojure in compile time. Used to avoid long strings in code."
  [file]
  (slurp file))

(def install-path "/opt/ravendb")

(def executable-path "Server/Raven.Server")

(def logfile (str install-path "/jepsen.log"))

(def pidfile (str install-path "/jepsen.pid"))

(def license
  (->> (compile-time-slurp "license.json")
       (#(str/replace % #"\r?\n" ""))
       (#(str/replace % #"\s+" " "))
       ))

(def assigned-cores-per-node 2)

(def studio-port 8888)

(def client-port 38888)

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

(defn install-server!
  [node version]
  (info node "installing RavenDB" version)
  (c/su
    ;; Install required packages
    (debian/install {:libunwind8      "*",
                     :ca-certificates "*",
                     :curl            "*",
                     ;; We install -dev here because libicu55/57 can't be found
                     :libicu-dev      "*",})
    ;; Download and extract the tarball into the install path
    (cu/install-tarball! c/*host* (download-url version) install-path)))

(defn format-raven-arguments
  [arguments]
  (map
    (fn [[real-key value]]
      (let [key (name real-key)
            kv-pair (if (str/blank? value) key (str key "=" value))]
        (str "--" kv-pair))) arguments))

(defn raven-arguments
  [node]
  (format-raven-arguments
    {
     :ServerUrl                       (server-url node),
     :ServerUrl.Tcp                   (server-url-tcp node),
     :PublicServerUrl                 (public-server-url node),
     :PublicServerUrl.Tcp             (public-server-url-tcp node),
     :Security.UnsecuredAccessAllowed "PublicNetwork",
     :log-to-console                  "",
     }))

(defn start-server!
  [node]
  (info node "starting RavenDB server")
  (c/su
    (apply (cu/start-daemon!
             {:logfile logfile
              :pidfile pidfile
              :chdir   install-path}
             executable-path)
           raven-arguments)
    ))

(defn activate-license!
  [node]
  (c/exec :curl
          :-L
          :-X "POST"
          :-d license
          (str (public-server-url node) "/admin/license/activate")))

(defn configure!
  [node]
  (activate-license! node))

(defn stop-server!
  [node]
  (info node "stopping RavenDB server")
  (c/su
    (cu/stop-daemon! executable-path pidfile)))

(defn uninstall-server!
  [node]
  (info node "uninstalling RavenDB")
  (c/su
    (c/exec :rm
            :--force
            :--recursive
            install-path)))

(defn link-to!
  [leader-node node]
  (let [url (str (public-server-url leader-node) "/admin/cluster/node?url=" (public-server-url node) "&assignedCores=" assigned-cores-per-node)]
    (c/exec :curl
            :-L
            :-X "PUT"
            :-d ""
            url)
    ))

(defn link-cluster!
  [test node]
  (->> (:nodes test)
       ;; Avoid linking against itself
       (filter (partial not= node))
       (map (partial link-to! node))))

(defn db
  "Set up a RavenDB instance with a particular version"
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up RavenDB")
      (install-server! node version)
      (start-server! node)
      (Thread/sleep 5000)
      (configure! node)
      (jepsen/synchronize test))

    (teardown! [_ test node]
      (info node "tearing down RavenDB")
      (stop-server! node)
      (uninstall-server! node))

    db/Primary
    (setup-primary! [_ test node]
      (link-cluster! test node))

    db/LogFiles
    (log-files [_ test node]
      [logfile])
    ))

(defn ravendb-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name "ravendb"
          :os   debian/os
          :db   (db "40019-Rc")}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn ravendb-test})
                   (cli/serve-cmd))
            args))