(ns glittering.pregel-test
  (:require [glittering.pregel :as p]
            [glittering.destructuring :as d]
            [glittering.core :as g]
            [glittering.generators :as gen]
            [glittering.test-utils :refer [untuple-all]]
            [sparkling.conf :as conf]
            [sparkling.core :as spark]
            [clojure.set :as set]
            [clojure.test :refer :all]))

(defn two-cliques [n]
  (let [clique1 (for [u (range n)
                      v (range n)]
                  (g/edge u v 1))
        clique2 (for [u (range n)
                      v (range n)]
                  (g/edge (+ u n) (+ v n) 1))]
    (concat clique1 clique2 [(g/edge 0 n 1)])))

;; Label propagation

(defn vertex-fn
  [vertex-id attribute message]
  (if (empty? message)
    attribute
    (key (apply max-key val message))))

(defn edge-fn
  [{:keys [src-attr dst-attr]}]
  {:src {dst-attr 1}
   :dst {src-attr 1}})

(defn ->vertex-id
  [vid attr]
  vid)

(deftest label-propagation
  (spark/with-context sc (-> (g/conf)
                             (conf/master "local[*]")
                             (conf/app-name "label-propagation-test"))
    (let [edges (spark/parallelize sc (two-cliques 5))
          labels (->> (g/graph-from-edges edges 1)
                      (g/map-vertices ->vertex-id)
                      (p/pregel {:initial-message {}
                                 :message-fn edge-fn
                                 :combiner (partial merge-with +)
                                 :vertex-fn vertex-fn
                                 :max-iterations 10})
                      (g/vertices)
                      (spark/collect)
                      (vec)
                      (untuple-all)
                      (group-by second))]
      (testing
          "returns two cliques"
        (is (= 2 (-> labels keys count)))))))

;; Semi-clustering

(def cmax 5)

(defn default-cluster [vid]
  {:id vid
   :ic 0
   :bc 0
   :score 1.0
   :vertices #{vid}})

(defn cluster-score [{:keys [ic bc vertices]}]
  (let [vc (count vertices)
        fb 0.0]
    (if (= vc 1)
      1.0
      (/ (- ic (* fb bc))
         (/ (* vc (dec vc))
            1)))))

(defn assoc-vertex-to-cluster [vid edges cluster]
  (let [vertices (:vertices cluster)
        grouped-edges (group-by (fn [[id weight]]
                                  (contains? vertices id)) edges)
        ic (reduce + (map second (get grouped-edges true)))
        bc (reduce + (map second (get grouped-edges false)))
        cluster (-> cluster
                    (update-in [:vertices] conj vid)
                    (update-in [:ic] + ic)
                    (update-in [:bc] + bc))]
    (assoc cluster :score (cluster-score cluster))))

(defn vertex-in-cluster? [vertex cluster]
  (-> cluster :vertices (contains? vertex)))

(defn sc-vertex-fn [vid attr {:keys [clusters edges] :as message}]
  (if (empty? message)
    #{(default-cluster vid)}
    (let [potential-clusters (->> clusters
                                  (remove (fn [cluster]
                                            (vertex-in-cluster? vid cluster)))
                                  (map (fn [cluster]
                                         (assoc-vertex-to-cluster vid edges cluster))))]
      (->> (concat clusters potential-clusters)
           (sort-by :score >)
           (take cmax)
           (set)))))

(def sc-message-fn
  ;; Caclculate boundary and internal weights
  (fn [{:keys [src-id src-attr dst-id dst-attr attr]}]
    [[:dst {:clusters src-attr
            :edges [[src-id attr]]}]]))

(defn sc-merge-fn [a b]
  {:clusters (set/union (:clusters a) (:clusters b))
   :edges (concat (:edges a) (:edges b))})

(deftest semi-clustering
  (spark/with-context sc (-> (g/conf)
                             (conf/master "local[*]")
                             (conf/app-name "semi-clustering-test"))
    (let [edges (spark/parallelize sc (two-cliques 5))
          labels (->> (g/graph-from-edges edges 1.0)
                      (p/pregel {:initial-message {}
                                 :message-fn sc-message-fn
                                 :combiner sc-merge-fn
                                 :vertex-fn sc-vertex-fn
                                 :max-iterations 10})
                      (g/vertices)
                      (spark/collect)
                      (vec)
                      (untuple-all)
                      (group-by second))]
      (testing
          "returns two cliques"
        (is (= 2 (-> labels keys count)))))))
