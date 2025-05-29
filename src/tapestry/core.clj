(ns tapestry.core
  "Core namespace of Tapestry"
  (:import [java.util.concurrent Semaphore CompletableFuture Phaser TimeUnit TimeoutException
            Executors]
           [java.lang VirtualThread]
           [java.time Duration])
  (:require [manifold.stream :as s]
            [manifold.deferred :as d])
  (:refer-clojure :exclude [send]))

(def ^{:dynamic true
       :no-doc  true} *local-semaphore*
  "A semaphore used to coordinate max-parallelism"
  nil)

(def ^{:dynamic true :no-doc true} *local-timeout*
  "A timeout used to coordinate timeout delays"
  nil)

(def ^{:no-doc true} on-error
  "The function that will be called when an error is encountered.

  Called with the  signature of: `e msg`"
  println)

(defn set-stream-error-handler!
  "Set a function to be called when an error occurs in a tapestry
  returned stream.

  By default will println. Set to `nil` to do nothing

  Calls `(f err msg)`."
  [f]
  (alter-var-root #'on-error (constantly f)))

(set! *warn-on-reflection* true)

(def ^:private -static-executor
  (Executors/newVirtualThreadPerTaskExecutor))

(deftype ^{:no-doc true} Fiber
    [^VirtualThread virtualThread ^CompletableFuture future]
  clojure.lang.IDeref
  (deref [_]
    (try
      (.get future)
      (catch java.util.concurrent.ExecutionException e
        (throw (.getCause e)))))
  clojure.lang.IBlockingDeref
  (deref [_ time timeout-value]
    (try
      (.get future time TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        timeout-value)))
  clojure.lang.IPending
  (isRealized [_]
    (.isDone future))
  manifold.deferred.IDeferred
  (executor [_]
    -static-executor)
  (realized [_]
   (.isDone future))
  (onRealized [_ on-success on-error]
    (.handle ^CompletableFuture future
             (reify java.util.function.BiFunction
               (apply [_ res ex]
                 (when res
                   (on-success res))
                 (when ex
                   (on-error ex))))))
  (successValue [_ default]
    (try
      (.getNow future default)
      (catch Throwable _
        default)))
  (errorValue [_ default]
    (if-not (.isDone future)
      default
      (try
        (.getNow future nil)
        default
        (catch Throwable e
          e)))))

(defmethod print-method Fiber [^Fiber v ^java.io.Writer w]
  (let [^VirtualThread vt         (.virtualThread v)
        ^CompletableFuture future (.future v)
        is-alive?                 (.isAlive vt)
        [val err]                 (try
                    [(.getNow future nil) nil]
                    (catch Exception e
                      [nil e]))]
    (.write w "#tapestry/fiber {")
    (.write w (format ":is-alive %b" is-alive?))
    (when val
      (.write w " :val ")
      (print-method val w))
    (when err
      (.write w " :error ")
      (print-method err w))
    (.write w "}")))

(defn alive?
  "Return whether the provided `fiber` is alive"
  [^Fiber fiber]
  (let [^VirtualThread vt (.virtualThread fiber)]
    (.isAlive vt)))

(defn fiber-error
  "Return the error of the provided `fiber` if it has errored, otherwise return nil"
  [^Fiber fiber]
  (when-not (alive? fiber)
    (try
      (.getNow (.future fiber) nil)
      nil
      (catch Exception e
        e))))

(defn interrupt!
  "Interrupt the provided fiber, causing a `java.lang.InterruptedException` to be
  thrown in the `fiber`

  Return the provided `fiber`for chaining"
  [^Fiber fiber]
  (let [^VirtualThread vt (.virtualThread fiber)]
    (.interrupt vt))
  fiber)

(defn- -interrupt-on-timeout!
  "Private internal function to wire up a `handle` to shutdown the fiber
  on timeout.

  Returns the provided `fiber`"
  [^Fiber fiber]
  (.handle ^CompletableFuture (.future fiber)
           (reify java.util.function.BiFunction
             (apply [_ res ex]
               (when (alive? fiber)
                 (interrupt! fiber))
               (when ex
                 (throw ex))
               res)))
  fiber)

(defn timeout!
  "Set the provided `timeout` on the provided `fiber`, causing a
  `java.util.concurrent.TimeoutException` to be thrown on the deferred (or `default` to be returrned)
  and the `fiber` to be interrupted.

  Return the provied `fiber` for chaining.

  Accepts either a number in millis or a duration."
  ([^Fiber fiber timeout]
   (let [millis (if (pos-int? timeout) timeout (.toMillis ^Duration timeout))]
     (.orTimeout
       ^CompletableFuture (.future fiber)
       millis TimeUnit/MILLISECONDS)
     (-interrupt-on-timeout! fiber)))
  ([^Fiber fiber timeout default]
   (let [millis (if (pos-int? timeout) timeout (.toMillis ^Duration timeout))]
     (.completeOnTimeout
       ^CompletableFuture (.future fiber)
       default
       millis
       TimeUnit/MILLISECONDS)
     (-interrupt-on-timeout! fiber))))

(defmacro fiber
  "Execute body on a loom fiber, returning a deferred that will resolve when the fiber completes."
  [& body]
  `(let [cf#     (CompletableFuture.)
         thread# (Thread/startVirtualThread
                   (bound-fn []
                     (when *local-semaphore*
                       (.acquire ^Semaphore *local-semaphore*))
                     (try
                       (.complete cf# (do ~@body))
                       (catch Exception e#
                         (.completeExceptionally cf# e#)
                         (throw e#))
                       (finally
                         (when *local-semaphore*
                           (.release ^Semaphore *local-semaphore*))))))
         fiber#  (Fiber. thread# cf#)]
     (when *local-timeout*
       (timeout! fiber# *local-timeout*))
     (Fiber. thread# cf#)))

(defmacro with-max-parallelism
  "Executes the provided body with an executor that ensures that at most `n` fibers
  will run in parallel."
  [n & body]
  `(binding [*local-semaphore* (Semaphore. ~n)]
     ~@body))

(defmacro with-timeout
  "Executes all newly spawned fibers with the provided `timeout`.

  Accepts either a number (used as `millis`) or `java.lang.Duration` for
  `timeout`."
  [timeout & body]
  `(binding [*local-timeout* ~timeout]
     ~@body))

(defmacro fiber-loop
  "Execute a body inside a loop."
  [bindings & body]
  `(fiber (loop ~bindings ~@body)))

(defmacro seq->stream
  "Macro which runs an expression that returns a (presumably lazy) sequence and runs it in a fiber,
  returning a source stream of the results"
  [expr]
  `(let [result# (s/stream)]
     (fiber
       (try
         @(s/put-all! result# ~expr)
         (finally
           (s/close! result#))))
     result#))

(defmacro pfor
  "Macro which behaves identically to `clojure.core.for` but runs the body in parallell using
  fibers.

  Note that bindings in `:let` and `:when` will not be evaluated in parallel.

  Will force evaluation of the sequence (ie. this is no longer lazy)."
  [seq-exprs body-expr]
  `(->> (for ~seq-exprs
          (fiber
            ~body-expr))
        (doall)
        (map deref)
        (doall)))

(defn periodically
  "Behaves similarly to `manifold.stream/periodically` but relies on a loom
  fiber for time keeping. If no initial delay is specified runs immediately.

  Also automatically coerces durations into millisecond values."
  ([period f] (periodically period nil f))
  ([period initial-delay f]
   (let [->ms       #(long (cond
                             (number? %) %
                             (nil? %)    0
                             :else       (.toMillis ^Duration %)))
         initial-ms (->ms initial-delay)
         poll-ms    (->ms period)
         result     (s/stream)]
     (fiber
       (try
         (Thread/sleep ^long initial-ms)
         (loop []
           (when-not (s/closed? result)
             @(s/put! result (f))
             (Thread/sleep ^long poll-ms)
             (recur)))
         (catch Exception e
           (when on-error
             (on-error e "Error in periodically f"))
           (s/close! result))))
     result)))

(defn asyncly
  "Executes mapping function `f` over the provided stream `s`.

  Returns a new stream in which items will be emitted in any order after `f` finishes.

  Runs each item in a loom fiber.

  Optionally takes a number `n` which will be the maximum parallelism."
  ([f s]
   (let [result (s/stream)
         seq?   (seqable? s)
         s      (if seq?
                  (s/->source s)
                  s)
         phaser (Phaser.)]
     (s/consume
       #(fiber
          (try
            (.register phaser)
            @(s/put! result (f %))
            (catch Exception e
              (when on-error
                (on-error e "Exception in asyncly function")))
            (finally
              (.arriveAndDeregister phaser))))
       s)
     (s/on-drained s (bound-fn* #(fiber
                                   (when-not (.isTerminated phaser)
                                     (.awaitAdvance phaser 0))
                                   (s/close! result))))
     (s/on-closed result #(s/close! s))
     (if seq?
       (s/stream->seq result)
       result)))
  ([^long n f s]
   (let [result      (s/stream)
         seq?        (seqable? s)
         s           (if seq?
                       (s/->source s)
                       s)
         work-buffer (s/stream n)
         phaser      (Phaser. n)]
     (s/connect s work-buffer)
     (dotimes [_ n]
       (fiber-loop []
         (let [val @(s/take! work-buffer :closed)]
           (if (= :closed val)
             (.arriveAndDeregister phaser)
             (do (try
                   @(s/put! result (f val))
                   (catch Exception e
                     (when on-error
                       (on-error e "Error in asyncly callback"))))
                 (recur))))))
     (s/on-drained work-buffer #(fiber (.awaitAdvance phaser 0)
                                       (s/close! result)))
     (if seq?
       (s/stream->seq result)
       result))))

(defn parallelly
  "Maps `f` over the stream or seq `s` with up to `n` items occuring in parallel.

  If `n` is not specified, will use unbounded parallelism (or the max parallism set via the
  `with-max-parallelism` macro)."
  ([f s]
   (let [stream? (s/stream? s)]
     (cond->> s
       stream? s/stream->seq
       true    (mapv #(fiber (f %)))
       true    (map deref)
       stream? s/->source)))
  ([^long n f s]
   (let [seq? (seqable? s)]
     (cond->> s
       seq? (s/->source)
       true (s/map #(d/->deferred (fiber (f %))))
       true (s/buffer n)
       true (s/realize-each)
       seq? (s/stream->seq)))))

(defn send
  "A version of `send` that uses a loom fiber for the execution of the function `f`.

  See `clojure.core/send` for details"
  [a f & args]
  (apply send-via -static-executor a f args))
