(ns tapestry.experimental.scope-test
  (:require [tapestry.experimental.scope :as sut]
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
    (is (= 42 (sut/with-scope {} 42))))

  (testing "nil results are returned correctly"
    (is (nil? (sut/with-scope {}
                (let [f (tc/fiber nil)]
                  @f)))))

  (testing "false results are returned correctly"
    (is (false? (sut/with-scope {}
                  (let [f (tc/fiber false)]
                    @f))))))

(deftest with-scope-fiber-registration-test
  (testing "fibers are automatically registered with the scope"
    (let [fiber-count (atom 0)]
      (sut/with-scope {}
        (tc/fiber :a)
        (tc/fiber :b)
        (tc/fiber :c)
        (reset! fiber-count (count @(:fibers tc/*scope*))))
      (is (= 3 @fiber-count))))

  (testing "fibers outside a scope are not registered"
    (let [f (tc/fiber :no-scope)]
      (is (= :no-scope @f))
      (is (nil? tc/*scope*)))))

(deftest shutdown-on-failure-test
  (testing "error in one fiber interrupts siblings"
    (tc/set-stream-error-handler! (fn [& _]))
    (try
      (let [interrupted? (promise)
            started?     (promise)]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"boom"
              (sut/with-scope {:shutdown :on-failure}
                (let [a (tc/fiber
                          (try
                            (deliver started? true)
                            (Thread/sleep 10000)
                            (catch InterruptedException _
                              (deliver interrupted? true))))
                      _ @started?
                      b (tc/fiber
                          (throw (ex-info "boom" {})))]
                  [@a @b]))))
        (is (= true (deref interrupted? 1000 :timeout))
            "sibling fiber should have been interrupted"))
      (finally
        (tc/set-stream-error-handler! println))))

  (testing "error propagated at scope exit even if not deref'd"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"unobserved"
          (sut/with-scope {:shutdown :on-failure}
            (tc/fiber (throw (ex-info "unobserved" {})))
            (Thread/sleep 50)
            :body-result))))

  (testing "first error wins when multiple fibers fail"
    (tc/set-stream-error-handler! (fn [& _]))
    (try
      (let [started? (promise)]
        (try
          (sut/with-scope {:shutdown :on-failure}
            (tc/fiber
              (deliver started? true)
              (throw (ex-info "first" {})))
            @started?
            (tc/fiber
              (Thread/sleep 10)
              (throw (ex-info "second" {})))
            (Thread/sleep 100))
          (catch clojure.lang.ExceptionInfo e
            (is (= "first" (.getMessage e))))))
      (finally
        (tc/set-stream-error-handler! println))))

  (testing "successful scope with :on-failure returns body result"
    (is (= {:a 1 :b 2}
           (sut/with-scope {:shutdown :on-failure}
             (let [a (tc/fiber 1)
                   b (tc/fiber 2)]
               {:a @a :b @b}))))))

(deftest shutdown-on-success-test
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
        @started?
        (tc/fiber :fast-result))
      (is (= true (deref interrupted? 1000 :timeout))
          "slow sibling should have been interrupted")))

  (testing "multiple slow siblings are all interrupted"
    (let [interrupt-count (atom 0)
          all-started?    (java.util.concurrent.CountDownLatch. 3)]
      (sut/with-scope {:shutdown :on-success}
        (dotimes [_ 3]
          (tc/fiber
            (try
              (.countDown all-started?)
              (Thread/sleep 10000)
              (catch InterruptedException _
                (swap! interrupt-count inc)))))
        (.await all-started?)
        (tc/fiber :winner))
      (is (= 3 @interrupt-count)
          "all slow siblings should have been interrupted"))))

(deftest scope-timeout-test
  (testing "timeout applies to fibers spawned in the scope"
    (is (thrown? java.util.concurrent.TimeoutException
                (sut/with-scope {:timeout 50 :shutdown :on-failure}
                  (let [f (tc/fiber (Thread/sleep 10000))]
                    @f)))))

  (testing "timeout interrupts the fiber's thread"
    (let [interrupted? (promise)]
      (try
        (sut/with-scope {:timeout 50 :shutdown :on-failure}
          (let [f (tc/fiber
                    (try
                      (Thread/sleep 10000)
                      (catch InterruptedException _
                        (deliver interrupted? true))))]
            @f))
        (catch Exception _))
      (is (= true (deref interrupted? 1000 :timeout))
          "timed out fiber should have been interrupted"))))

(deftest scope-max-parallelism-test
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

(deftest scope-combined-options-test
  (testing "timeout + max-parallelism + shutdown together"
    (let [state        (atom {:running 0 :max-seen 0})
          update-state (fn [{:keys [running max-seen]}]
                         {:running  (inc running)
                          :max-seen (max (inc running) max-seen)})]
      (is (= (range 1 6)
             (sut/with-scope {:max-parallelism 2 :timeout 5000 :shutdown :on-failure}
               (->> (range 1 6)
                    (mapv (fn [x]
                            (tc/fiber
                              (swap! state update-state)
                              (Thread/sleep 2)
                              (swap! state update :running dec)
                              x)))
                    (mapv deref)))))
      (is (<= (:max-seen @state) 2))))

  (testing "shutdown + timeout: timeout triggers shutdown"
    (tc/set-stream-error-handler! (fn [& _]))
    (try
      (let [interrupted? (promise)]
        (try
          (sut/with-scope {:timeout 50 :shutdown :on-failure}
            (let [a (tc/fiber
                      (try
                        (Thread/sleep 10000)
                        (catch InterruptedException _
                          (deliver interrupted? true))))
                  b (tc/fiber (Thread/sleep 10000))]
              [@a @b]))
          (catch Exception _))
        (is (= true (deref interrupted? 1000 :timeout))
            "timeout should trigger shutdown and interrupt siblings"))
      (finally
        (tc/set-stream-error-handler! println)))))

(deftest scope-nested-test
  (testing "inner scope fibers don't leak to outer scope"
    (let [inner-fiber-count (atom 0)
          outer-fiber-count (atom 0)]
      (sut/with-scope {:shutdown :on-failure}
        (let [a (tc/fiber
                  (sut/with-scope {:shutdown :on-failure}
                    (let [x (tc/fiber 10)
                          y (tc/fiber 20)]
                      (reset! inner-fiber-count (count @(:fibers tc/*scope*)))
                      (+ @x @y))))
              b (tc/fiber 3)]
          (reset! outer-fiber-count (count @(:fibers tc/*scope*)))
          (is (= 33 (+ @a @b)))))
      (is (= 2 @outer-fiber-count) "outer scope should have 2 fibers (a and b)")
      (is (= 2 @inner-fiber-count) "inner scope should have 2 fibers (x and y)")))

  (testing "inner scope failure doesn't propagate to outer scope directly"
    (tc/set-stream-error-handler! (fn [& _]))
    (try
      (is (= :caught
             (sut/with-scope {:shutdown :on-failure}
               (let [a (tc/fiber
                         (try
                           (sut/with-scope {:shutdown :on-failure}
                             (tc/fiber (throw (ex-info "inner-boom" {})))
                             (Thread/sleep 50))
                           (catch Exception _
                             :caught)))]
                 @a))))
      (finally
        (tc/set-stream-error-handler! println)))))

(deftest scope-thread-termination-test
  (testing "scope guarantees threads are fully terminated on exit"
    (let [threads (atom [])]
      (sut/with-scope {}
        (dotimes [_ 5]
          (tc/fiber
            (swap! threads conj (Thread/currentThread))
            (Thread/sleep 5))))
      (is (every? #(not (.isAlive ^Thread %)) @threads)
          "all fiber threads should be terminated after scope exits")))

  (testing "threads terminated even after error shutdown"
    (tc/set-stream-error-handler! (fn [& _]))
    (try
      (let [threads (atom [])]
        (try
          (sut/with-scope {:shutdown :on-failure}
            (dotimes [_ 3]
              (tc/fiber
                (swap! threads conj (Thread/currentThread))
                (Thread/sleep 10000)))
            (Thread/sleep 20)
            (tc/fiber (throw (ex-info "die" {})))
            (Thread/sleep 100))
          (catch Exception _))
        (is (every? #(not (.isAlive ^Thread %)) @threads)
            "all fiber threads should be terminated even after error shutdown"))
      (finally
        (tc/set-stream-error-handler! println)))))

(deftest scope-no-shutdown-policy-test
  (testing "scope without shutdown policy still awaits fibers"
    (let [completed (atom 0)]
      (sut/with-scope {}
        (dotimes [_ 5]
          (tc/fiber
            (Thread/sleep 10)
            (swap! completed inc))))
      (is (= 5 @completed))))

  (testing "scope without shutdown policy ignores fiber errors"
    (tc/set-stream-error-handler! (fn [& _]))
    (try
      ;; No :shutdown policy — error in fiber is not propagated at scope exit
      ;; (but derefing the fiber would still throw)
      (is (= :ok
             (sut/with-scope {}
               (tc/fiber (throw (ex-info "ignored" {})))
               (Thread/sleep 20)
               :ok)))
      (finally
        (tc/set-stream-error-handler! println)))))

(deftest scope-body-exception-shuts-down-fibers-test
  (testing "exception in body (not from fiber) shuts down all fibers"
    (let [interrupted? (promise)
          started?     (promise)]
      (try
        (sut/with-scope {}
          (tc/fiber
            (try
              (deliver started? true)
              (Thread/sleep 10000)
              (catch InterruptedException _
                (deliver interrupted? true))))
          @started?
          (throw (ex-info "body-error" {})))
        (catch Exception _))
      (is (= true (deref interrupted? 1000 :timeout))
          "fibers should be interrupted when body throws"))))
