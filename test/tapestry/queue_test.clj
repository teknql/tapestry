(ns tapestry.queue-test
  (:require [tapestry.queue :as sut]
            [tapestry.core :refer [fiber alive?]]
            [clojure.test :refer [deftest testing is]]))

(deftest queue--sync-queue-test
  (let [q    (sut/queue)
        put* (fiber (sut/put! q 1))]
    (is (sut/queue? q))
    (Thread/sleep 10)
    (is (alive? put*))
    (is (= 1 (sut/take! q)))
    (is @put*)))

(deftest queue--array-queue-test
  (let [q (sut/queue 2)]
    (is (sut/try-put! q 1))
    (is (sut/try-put! q 2))
    (is (not (sut/try-put! q 3)))
    (is (= 1 (sut/try-take! q)))
    (is (= 2 (sut/try-take! q)))))

(deftest queue--linked-queue-test
  (let [q (sut/queue :unbounded)]
    (is (sut/try-put! q 1))
    (is (sut/try-put! q 2))
    (is (sut/try-put! q 3))
    (is (= 1 (sut/try-take! q)))
    (is (= 2 (sut/try-take! q)))
    (is (= 3 (sut/try-take! q)))))


(deftest queue--try-ops
  (testing "try-take!"
    (let [q (sut/queue)]
      (is (nil? (sut/try-take! q)))
      (is (false? (sut/try-take! q 1 false)))
      (sut/close! q)
      (is (nil? (sut/try-take! q 0 false)))))
  (testing "try-put!"
    (let [q (sut/queue)]
      (is (false? (sut/try-put! q :val)))
      (is (false? (sut/try-put! q :val 0)))
      (is (= :timeout (sut/try-put! q :val 0 :timeout)))
      (sut/close! q)
      (is (false? (sut/try-put! q :val 0 :timeout))))))

(deftest queue--close-during-take-test
  (testing "take! returns nil when queue is closed while waiting"
    (let [q      (sut/queue 2)
          result (fiber (sut/take! q))]
      (Thread/sleep 20)
      (is (alive? result))
      (sut/close! q)
      (is (nil? @result)))))

(deftest queue--close-drains-test
  (testing "close! allows remaining items to be drained"
    (let [q (sut/queue 4)]
      (sut/put! q :a)
      (sut/put! q :b)
      (sut/close! q)
      (is (= :a (sut/take! q)))
      (is (= :b (sut/take! q)))
      (is (nil? (sut/take! q))))))

(deftest queue--items-test
  (testing "items returns a point-in-time snapshot vector"
    (testing "empty queue"
      (is (= [] (sut/items (sut/queue 4))))
      (is (= [] (sut/items (sut/queue :unbounded))))
      (is (= [] (sut/items (sut/queue)))))
    (testing "bounded queue with items"
      (let [q (sut/queue 4)]
        (sut/put! q :a)
        (sut/put! q :b)
        (is (= [:a :b] (sut/items q)))))
    (testing "unbounded queue with items"
      (let [q (sut/queue :unbounded)]
        (sut/put! q 1)
        (sut/put! q 2)
        (sut/put! q 3)
        (is (= [1 2 3] (sut/items q)))))
    (testing "sync queue with in-flight parcel"
      (let [q     (sut/queue)
            _put  (fiber (sut/put! q :only))]
        (Thread/sleep 10)
        (is (= [:only] (sut/items q)))
        (is (= :only (sut/take! q)))
        (is (= [] (sut/items q)))))
    (testing "snapshot is decoupled from the live queue"
      (let [q    (sut/queue 4)
            _    (sut/put! q :a)
            snap (sut/items q)]
        (sut/put! q :b)
        (is (= [:a] snap))
        (is (= [:a :b] (sut/items q)))))))

(deftest queue--close-race-never-drops-items-test
  (testing "close! racing with take! never drops items"
    (dotimes [_ 5000]
      (let [q     (sut/queue :unbounded)
            out   (atom [])
            taker (fiber
                    (loop []
                      (when-some [item (sut/take! q)]
                        (swap! out conj item)
                        (recur))))]
        (sut/put! q :a)
        (sut/close! q)
        @taker
        (when-not (= [:a] @out)
          (throw (ex-info "Property violation" {:out @out})))))
    (is true)))
