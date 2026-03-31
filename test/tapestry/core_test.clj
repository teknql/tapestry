(ns tapestry.core-test
  (:require [tapestry.core :as sut]
            [clojure.test :refer [deftest testing is]]
            [manifold.stream :as s]
            [manifold.deferred :as d])
  (:import [java.util.concurrent TimeoutException]
           [java.lang InterruptedException]))

(defmacro with-global-error-handler
  "Macro to install `f` as the global error handler for the duration of the `body`.

  `f` is an arity 2 function that will be called with `thread` and `Throwable` when an
  error is encountered"
  [f & body]
  `(let [existing-handler# (Thread/getDefaultUncaughtExceptionHandler)]
     (try
       (Thread/setDefaultUncaughtExceptionHandler
         (reify Thread$UncaughtExceptionHandler
           (uncaughtException [_# thread# e#]
             (~f thread# e#))))
       ~@body
       (finally
         (Thread/setDefaultUncaughtExceptionHandler existing-handler#)))))

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

      (is (= 100 (:count @state)))
      (is (zero? (:running @state)))
      (is (<= (:max-seen @state) 10))))

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
      (is (<= 10 (:max-seen @state) 100)))))

(deftest asyncly-test
  (testing "unbounded concurrency"
    (is (=  [2 3 4]
            (->> (s/->source [1 2 3])
                 (sut/asyncly inc)
                 (s/stream->seq)
                 (sort)))))

  (testing "handling nil"
    (is (= '() (sut/asyncly inc nil))))
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
      (is (<= (:max-seen @state) 3))))

  (testing "unbounded - seq mode throws on error"
    (sut/set-stream-error-handler! (fn [& _]))
    (try
      (let [boom (ex-info "boom" {})]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"boom"
              ;; must force the lazy seq to realize the throw
              (doall (sut/asyncly (fn [x] (when (= x 2) (throw boom)) x)
                                  [1 2 3])))))
      (finally
        (sut/set-stream-error-handler! println))))

  (testing "unbounded - stream mode closes result stream on error (no throw)"
    (sut/set-stream-error-handler! (fn [& _]))
    (try
      (let [result (sut/asyncly #(throw (ex-info "oops" {}))
                                (s/->source [1 2 3]))]
        (s/stream->seq result) ;; drains cleanly, does not throw
        (Thread/sleep 20)
        (is (s/closed? result)))
      (finally
        (sut/set-stream-error-handler! println))))

  (testing "unbounded - stream mode closes source stream on error"
    (sut/set-stream-error-handler! (fn [& _]))
    (try
      (let [source (s/stream)
            _      (s/put! source 1)
            _      (s/put! source 2)
            result (sut/asyncly #(throw (ex-info "oops" {})) source)]
        (s/stream->seq result)
        (Thread/sleep 20)
        (is (s/closed? source)))
      (finally
        (sut/set-stream-error-handler! println))))

  (testing "bounded - seq mode throws on error"
    (sut/set-stream-error-handler! (fn [& _]))
    (try
      (let [boom (ex-info "bounded-boom" {})]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo #"bounded-boom"
              (doall (sut/asyncly 2
                                  (fn [x] (when (= x 2) (throw boom)) x)
                                  [1 2 3])))))
      (finally
        (sut/set-stream-error-handler! println))))

  (testing "bounded - error not lost when other workers produce nil results (race condition)"
    ;; This is the specific race: many workers return nil, one throws.
    ;; The [:error e] tuple can be swallowed by stream close before the consumer sees it.
    ;; The promise sentinel must catch it as a fallback.
    (sut/set-stream-error-handler! (fn [& _]))
    (try
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"boom"
            (doall (sut/asyncly 4
                                (fn [x] (when (= x 5) (throw (ex-info "boom" {}))) nil)
                                (range 100)))))
      (finally
        (sut/set-stream-error-handler! println))))

  (testing "bounded - stream mode closes result stream on error (no throw)"
    (sut/set-stream-error-handler! (fn [& _]))
    (try
      (let [result (sut/asyncly 2
                                #(throw (ex-info "oops" {}))
                                (s/->source [1 2 3]))]
        (s/stream->seq result)
        (Thread/sleep 20)
        (is (s/closed? result)))
      (finally
        (sut/set-stream-error-handler! println)))))

(deftest periodically-test
  (let [s (sut/periodically 3 5 (constantly true))]
    (is (nil? @(s/try-take! s 0))) ;; nothing available immediately
    (is @(s/try-take! s 10)) ;; Wait a bit
    (is (nil? @(s/try-take! s 0))) ;; Nothing should be available immediately
    (is @(s/try-take! s 5)) ;; wait 5 millis for poll duration
    (s/close! s)))


(deftest parallely-test
  (testing "stream mode"
    (is (= [2 3 4 5 6 7]
           (->> (s/->source [1 2 3 4 5 6])
                (sut/parallelly 2 inc)
                (s/stream->seq))))
    (is (= [2 3 4]
           (->> (s/->source [1 2 3])
                (sut/parallelly inc)
                (s/stream->seq)))))

  (testing "handles nil"
    (is (= '() (sut/parallelly inc nil))))

  (testing "seq mode"
    (is (= [2 3 4 5 6]
           (sut/parallelly 2 inc [1 2 3 4 5])))
    (is (= [2 3 4]
           (sut/parallelly inc [1 2 3]))))

  (testing "unbounded parallelism"
    (is (= [2 3 4 5]
           (sut/parallelly inc [1 2 3 4])))))

(deftest locking-test
  (testing "locking works"
    (let [resource (atom false)
          locked   (d/deferred)]
      (sut/fiber
        (locking resource
          (d/success! locked true)
          (Thread/sleep 10)
          (reset! resource true)))
      @locked
      (locking resource
        (is (true? @resource))))))

(deftest fiber-error-test
  (let [die?              (promise)
        err               (ex-info "Boom" {})
        err-handler-call* (atom nil)]
    (with-global-error-handler
      #(reset! err-handler-call* %&)
      (let [f (sut/fiber
                @die?
                (throw err))]
        (is (nil? (sut/fiber-error f)))
        (deliver die? true)
        (Thread/sleep 10) ;; Let the fiber die
        (is (some? (sut/fiber-error f)))
        (is (some? @err-handler-call*))))))

(deftest pfor-test
  (testing "works"
    (is (= '(1 2 3)
           (sut/pfor [x (range 3)] (inc x)))))
  (testing "is eager"
    (is (realized? (sut/pfor [x (range 3)] (inc x))))))

(deftest interrupt-test
  (let [f (sut/fiber (Thread/sleep 10000))]
    (sut/interrupt! f)
    (is (thrown? InterruptedException @f)))
  (let [f (sut/fiber (try
                       (Thread/sleep 10000)
                       (catch InterruptedException _
                         :handled)))]
    (sut/interrupt! f)
    (is (= :handled @f))))

(deftest alive?-test
  (let [f (sut/fiber (Thread/sleep 100))]
    (is (sut/alive? f))
    (sut/interrupt! f)
    (Thread/sleep 10) ;; Let the interrupt happen
    (is (not (sut/alive? f)))))

(deftest timeout!-test
  (testing "simple timeout"
    (let [f (sut/fiber (Thread/sleep 1000))]
      (sut/timeout! f 10)
      (is (thrown? TimeoutException @f))
      (Thread/sleep 10) ;; Let the interrupt be thrown
      (is (not (sut/alive? f)))))
  (testing "binding-based timeout"
    (let [f (sut/with-timeout 10
              (sut/fiber (Thread/sleep 1000)))]
      (is (thrown? TimeoutException @f))))

  (testing "binding and explicit defaults to explicit"
    (let [f (sut/with-timeout 100
              (sut/fiber (Thread/sleep 10000)))]
      (is (= :explicit
             @(sut/timeout! f 10 :explicit)))))

  (testing "default value"
    (let [f (sut/timeout! (sut/fiber (Thread/sleep 100))
                          10
                          :default)]
      (is (= :default @f))
      (Thread/sleep 10)
      (is (not (sut/alive? f))))))

(deftest IDeferred-test
  (testing "can be used with manifold.streams"
    (is (= [2 4 6]
           (->> (s/->source [1 2 3])
                (s/map #(sut/fiber (* % 2)))
                (s/realize-each)
                (s/stream->seq)))))
  (testing "can be used with manifold.deferred/chain'"
    (let [success? (atom false)]
      @(d/chain' (sut/fiber true) (partial reset! success?))
      (is @success?))))

(deftest send-test
  (let [a (agent 0)]
    (testing "without arguments"
      (sut/send a inc)
      (await a)
      (is (= 1 @a)))
    (testing "with argument"
      (sut/send a (constantly 0))
      (sut/send a + 2 3)
      (await a)
      (is (= 5 @a)))
    (testing "with multiple arguments"
      (sut/send a (constantly 0))
      (sut/send a + 1 2 3 4)
      (await a)
      (is (= 10 @a)))))
