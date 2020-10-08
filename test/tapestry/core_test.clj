(ns tapestry.core-test
  (:require [tapestry.core :as sut]
            [clojure.test :refer [deftest testing is]]
            [manifold.stream :as s]
            [manifold.deferred :as d]))

(deftest with-max-parallelism-test
  (testing "with-max-parallism limits parallel execution"
    (let [state        (atom {:running 0 :max-seen 0 :count 0})
          update-state (fn [{:keys [count running max-seen]}]
                         {:count    (inc count)
                          :running  (inc running)
                          :max-seen (max max-seen (inc running))})]
      (is (= (range 100)
             (sut/with-max-parallelism 10
               @(apply d/zip
                       (mapv (fn [x]
                               (sut/fiber
                                 (swap! state update-state)
                                 (Thread/sleep 1)
                                 (swap! state update :running dec)
                                 x))
                             (range 100))))))
      (is (= {:count 100 :running 0 :max-seen 10}
             @state))))

  (testing "with-max-parallelism can be nested"
    (let [state        (atom {:running 0 :max-seen 0 :count 0})
          update-state (fn [{:keys [count running max-seen]}]
                         {:count    (inc count)
                          :running  (inc running)
                          :max-seen (max max-seen (inc running))})]
      (is (= (range 100)
             (sut/with-max-parallelism 10
               (flatten
                 @(apply d/zip
                         (mapv (fn [x]
                                 (->>
                                   (sut/with-max-parallelism 10
                                     (->> (range 10 )
                                          (mapv (fn [y]
                                                  (sut/fiber
                                                    (swap! state update-state)
                                                    (Thread/sleep 2)
                                                    (swap! state update :running dec)
                                                    (+ (* 10 x) y))))
                                          (apply d/zip)))))
                               (range 10)))))))
      (is (= 100 (:count @state)))
      (is (zero? (:running @state)))
      (is (< 10 (:max-seen @state) 100)))))

(deftest asyncly-test
  (testing "unbounded concurrency"
    (is (=  [2 3 4]
            (->> (s/->source [1 2 3])
                 (sut/asyncly inc)
                 (s/stream->seq)
                 (sort)))))

  (testing "seq mode"
    (is (= [2 3 4]
           (->> [1 2 3]
                (sut/asyncly inc)
                sort))))
  (testing "bounded concurrency"
    (let [state        (atom {:running 0 :max-seen 0})
          update-state (fn [{:keys [running max-seen]}]
                         {:running  (inc running)
                          :max-seen (max (inc running) max-seen)})]
      (is (=  (range 10)
              (->> (s/->source (range 10))
                   (sut/asyncly 3 #(do (swap! state update-state)
                                       (Thread/sleep 2)
                                       (swap! state update :running dec)
                                       %))
                   (s/stream->seq)
                   (sort))))
      (is (zero? (:running @state)))
      (is (<= (:max-seen @state) 3)))))

(deftest periodically-test
  (let [s (sut/periodically 1 2 (constantly true))]
    (is (nil? @(s/try-take! s 0))) ;; nothing available immediately
    (is @(s/try-take! s 3)) ;; wait 3 millis for initial timeout duration
    (is @(s/try-take! s 2)) ;; wait 2 millis for poll duration
    (s/close! s)))


(deftest parallely-test
  (testing "stream mode"
    (is (= [2 3 4 5 6 7]
           (->> (s/->source [1 2 3 4 5 6])
                (sut/parallelly 2 inc)
                (s/stream->seq)))))

  (testing "seq mode"
    (is (= [2 3 4 5 6]
           (sut/parallelly 2 inc [1 2 3 4 5]))))

  (testing "unbounded parallelism"
    (is (= [2 3 4 5]
           (sut/parallelly inc [1 2 3 4])))))
