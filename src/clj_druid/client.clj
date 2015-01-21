(ns clj-druid.client
  (:use plumbing.core)
  (:require [zookeeper :as zk]
            [zookeeper.data :as data]
            [clojure.data.json :as json]
            [clj-druid.schemas :as sch]
            [clj-druid.validations :as v]
            [swiss.arrows :refer :all]
            [org.httpkit.client :as http]
            [clojure.tools.logging :as log]))

(def nodes-list
  "contains all zk nodes discovered"
  (atom []))


(defn reset-node-list
  "update node list atom"
  [nodes]

  (reset! nodes-list nodes))

(defn make-node-path
  "make a zk path string"
  [discovery-path node-type]

  (str discovery-path "/druid:" node-type))


(defn make-host-http-str
  "make an http url string from a zk node entry"
  [c]

  (str "http://"
       (get c "address")
       ":"
       (get c "port")
       "/druid/v2/"))


(defn zk-watch-node-list
  "Retrieve hosts from zk discovery"
  [zk-client path]

  (-<>> path
        (zk/children zk-client <>
                     :watch? true
                     :watcher (fn [e] (zk-watch-node-list zk-client path)))

        (map #(data/to-string (:data (zk/data zk-client (str path "/" %)))))
        (map #(json/read-str %))
        (map #(make-host-http-str %))
        (reset-node-list)))


(defn from-zookeeper
  "Maintain a druid http server list from zookeeper"
  [config]

  (let [node-path (make-node-path (:discovery-path config)
                                  (:node-type config))

        zk-client (zk/connect (:host config))]

    (zk-watch-node-list zk-client node-path)))


(defn from-user
  "Maintain a druid http server list from user"
  [hosts]

  (reset-node-list hosts))


(defn randomized
  "Take a random host"
  []

  (if (empty? @nodes-list)
    (throw (Exception.
            "No druid node available for query")))

  (rand-nth @nodes-list))

(defn fixed
  "Always take first host"
  []
  (first @nodes-list))


(defnk connect
  "Create a druid client from zk or
  a user defined host"
  [{zk {}} {hosts []}]

  (or (if (not (empty? zk))
          (from-zookeeper zk))

      (if (not (empty? hosts))
          (from-user hosts))))


(defn query
  "Issue a druid query"
  [balance-strategy query-type druid-query & params]

  (let [conf (apply hash-map params)]

  (-<> (into druid-query {:queryType query-type})
       (v/validate query-type)
       (json/write-str <>)
       {:body <> :as :text}
       (http/post (balance-strategy) params <>))))


