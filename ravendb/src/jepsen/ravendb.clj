(ns jepsen.ravendb
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
             [client :as client]
             [control :as ctrl]
             [db :as db]
             [generator :as gen]
             [tests :as tests]
             [util :as util]
             [core :as jepsen]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [jepsen.ravendb.defaults :refer :all]))

(defn client
  "A client for a single compare-and-set register"
  []
  (reify client/Client
    (setup! [_ test node] (client))

    (invoke! [this test op]
      (case (:f op)
        :read (assoc op :type :ok, :value nil)))

    (teardown! [_ test])
    ))

(defn install-server!
  "Downloads and installs the server executable for some version into a given node"
  [node version]
  (info node "installing RavenDB" version)
  (ctrl/su
    ;; Install required packages
    (debian/install {:libunwind8      "*",
                     :ca-certificates "*",
                     :curl            "*",
                     ;; We install -dev here because libicu55/57 can't be found
                     :libicu-dev      "*",})
    ;; Download and extract the tarball into the install path
    (cu/install-tarball! ctrl/*host* (download-url version) install-path)))

(defn start-server!
  [node]
  (ctrl/su
    (apply (partial cu/start-daemon!
                    {:logfile log-file-path
                     :pidfile pid-file-path
                     :chdir   install-path}
                    executable-path)
           (executable-arguments node))
    ))

(defn activate-license!
  "Activates the license for a given node. This is required because cluster features are paid for"
  [node]
  (ctrl/exec :curl
             :-L
             :-X "POST"
             :-H "Content-Type: application/json"
             :-d license
             (str (public-server-url node) "/admin/license/activate"))
  (info node "License activated!"))

(defn stop-server!
  [node]
  (ctrl/su (cu/stop-daemon! executable-path pid-file-path)))

(defn uninstall-server!
  [node]
  (ctrl/su
    (ctrl/exec :rm
               :--force
               :--recursive
               install-path)))

(defn link-to!
  [leader-node node]
  (info leader-node (str "Linking against " node))
  (let [url (str (public-server-url leader-node) "/admin/cluster/node?url=" (public-server-url node) "&assignedCores=" assigned-cores-per-node)]
    (ctrl/exec :curl
               :-L
               :-X "PUT"
               :-d ""
               url)))

;/license/status


(defn db
  "Set up a RavenDB instance with a particular version"
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "setting up RavenDB")
      ;; Ensure there aren't any other instances running on this node
      (ctrl/su (util/meh (ctrl/exec :killall
                                    executable-name)))
      ;; Download and install the server
      (install-server! node version)
      ;; Start and give some grace time
      (start-server! node)
      (Thread/sleep server-startup-grace-time-milliseconds)
      ;; Set up licensing
      (activate-license! node)
      ;; Ensure they are all activated and running before proceeding
      (jepsen/synchronize test))

    (teardown! [_ test node]
      (info node "tearing down RavenDB")
      (stop-server! node)
      (uninstall-server! node)
      (info node "RavenDB teardown succeeded"))

    db/Primary
    (setup-primary! [_ test node]
      (info node "RavenDB Primary setup running")
      ; TODO: do this proper.
      (link-to! "n1" "n2")
      (link-to! "n1" "n3")
      (link-to! "n1" "n4")
      (link-to! "n1" "n5")
      (info node "RavenDB primary setup finished"))

    db/LogFiles
    (log-files [_ test node]
      [log-file-path])
    ))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn ravendb-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name   "ravendb"
          :os     debian/os
          :db     (db "40019-Rc")
          :client (client)
          :generator (->> r
                          (gen/stagger 1)
                          (gen/clients)
                          (gen/time-limit 15))}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn ravendb-test})
                   (cli/serve-cmd))
            args))