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
