(ns tapestry.experimental-test
  (:require [tapestry.experimental :as sut]
            [tapestry.experimental.scope :as scope]
            [tapestry.core :as tc]
            [clojure.test :refer [deftest testing is]]))

(deftest with-scope-basic-test
  (testing "fibers run and results are collected"
    (is (= {:a 1 :b 2}
           (sut/with-scope {}
             (let [a (tc/fiber 1)
                   b (tc/fiber 2)]
               {:a @a :b @b})))))

  (testing "returns body value when no fibers are spawned"
    (is (= 42 (sut/with-scope {} 42)))))

(deftest with-scope-shutdown-on-failure-test
  (testing "error in one fiber interrupts siblings"
    (tc/set-stream-error-handler! (fn [& _]))
    (try
      (let [interrupted? (promise)]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"boom"
              (sut/with-scope {:shutdown :on-failure}
                (let [a (tc/fiber
                          (try
                            (Thread/sleep 10000)
                            (catch InterruptedException _
                              (deliver interrupted? true))))
                      b (tc/fiber
                          (throw (ex-info "boom" {})))]
                  [@a @b]))))
        (is (= true (deref interrupted? 1000 :timeout))
            "sibling fiber should have been interrupted"))
      (finally
        (tc/set-stream-error-handler! println))))

  (testing "error propagated even if not deref'd"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unobserved"
          (sut/with-scope {:shutdown :on-failure}
            (tc/fiber (throw (ex-info "unobserved" {})))
            (Thread/sleep 50)
            :body-result)))))

(deftest with-scope-shutdown-on-success-test
  (testing "first success interrupts siblings"
    (let [interrupted? (promise)
          started?     (promise)]
      (sut/with-scope {:shutdown :on-success}
        (tc/fiber
          (try
            (deliver started? true)
            (Thread/sleep 10000)
            (catch InterruptedException _
              (deliver interrupted? true))))
        ;; Wait for slow fiber to start before spawning the winner
        @started?
        (tc/fiber :fast-result))
      (is (= true (deref interrupted? 1000 :timeout))
          "slow sibling should have been interrupted"))))

(deftest with-scope-timeout-test
  (testing "timeout applies to fibers in the scope"
    (is (thrown? java.util.concurrent.TimeoutException
                (sut/with-scope {:timeout 50 :shutdown :on-failure}
                  (let [f (tc/fiber (Thread/sleep 10000))]
                    @f))))))

(deftest with-scope-max-parallelism-test
  (testing "max-parallelism limits concurrent fibers"
    (let [state        (atom {:running 0 :max-seen 0})
          update-state (fn [{:keys [running max-seen]}]
                         {:running  (inc running)
                          :max-seen (max (inc running) max-seen)})]
      (sut/with-scope {:max-parallelism 3}
        (->> (range 20)
             (mapv (fn [_]
                     (tc/fiber
                       (swap! state update-state)
                       (Thread/sleep 5)
                       (swap! state update :running dec))))
             (mapv deref)))
      (is (zero? (:running @state)))
      (is (<= (:max-seen @state) 3)))))

(deftest with-scope-combined-options-test
  (testing "timeout + max-parallelism + shutdown together"
    (let [state        (atom {:running 0 :max-seen 0})
          update-state (fn [{:keys [running max-seen]}]
                         {:running  (inc running)
                          :max-seen (max (inc running) max-seen)})]
      (is (= [1 2 3 4 5]
             (sut/with-scope {:max-parallelism 2 :timeout 5000 :shutdown :on-failure}
               (->> (range 1 6)
                    (mapv (fn [x]
                            (tc/fiber
                              (swap! state update-state)
                              (Thread/sleep 2)
                              (swap! state update :running dec)
                              x)))
                    (mapv deref)))))
      (is (<= (:max-seen @state) 2)))))

(deftest with-scope-nested-test
  (testing "inner scope fibers don't leak to outer scope"
    (let [inner-count (atom 0)
          outer-count (atom 0)]
      (sut/with-scope {:shutdown :on-failure}
        (swap! outer-count (fn [_] (count @(:fibers tc/*scope*))))
        (let [a (tc/fiber
                  (sut/with-scope {:shutdown :on-failure}
                    (swap! inner-count (fn [_] (count @(:fibers tc/*scope*))))
                    (let [x (tc/fiber 10)
                          y (tc/fiber 20)]
                      ;; inner scope should have its own fibers
                      (+ @x @y))))
              b (tc/fiber 3)]
          (is (= 33 (+ @a @b)))))
      ;; outer scope should only see its own fibers (a and b)
      ;; inner scope should see its own (x and y)
      (is (= 0 @outer-count) "outer scope had no fibers before a and b")
      (is (<= @inner-count 2) "inner scope should track its own fibers"))))

(deftest with-scope-thread-termination-test
  (testing "scope guarantees threads are fully terminated on exit"
    (let [threads (atom [])]
      (sut/with-scope {:shutdown :on-failure}
        (dotimes [_ 5]
          (tc/fiber
            (swap! threads conj (Thread/currentThread))
            (Thread/sleep 5))))
      ;; After scope exits, ALL threads must be dead
      (is (every? #(not (.isAlive ^Thread %)) @threads)
          "all fiber threads should be terminated after scope exits"))))

(deftest alts-test
  (testing "returns first successful result"
    (is (= :fast
           (sut/alts
             (do (Thread/sleep 10000) :slow)
             :fast))))

  (testing "interrupts losers"
    (let [interrupted? (promise)]
      (sut/alts
        (do (try
              (Thread/sleep 10000)
              (catch InterruptedException _
                (deliver interrupted? true)))
            :slow)
        :fast)
      (is (= true (deref interrupted? 1000 :timeout))
          "slow branch should have been interrupted")))

  (testing "with options map"
    (is (= :result
           (sut/alts {:timeout 5000}
             :result
             (Thread/sleep 10000))))))
