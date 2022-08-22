(ns tapestry.core
  "Core namespace of Tapestry"
  (:import [java.util.concurrent Executors ExecutorService AbstractExecutorService Semaphore
            Phaser])
  (:require [manifold.stream :as s]
            [manifold.deferred :as d])
  (:refer-clojure :exclude [locking]))


(def ^{:dynamic true
       :no-doc  true} *root-executor*
  "Static executor for loom"
  (Executors/newVirtualThreadPerTaskExecutor))

(def ^{:dynamic true
       :no-doc  true} *local-executor*
  "The current executor in the stack"
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

(defn max-parallelism-executor
  "Wraps the provided `executor` in an executor that ensures that a maximum of `n` tasks
  are running at a time"
  [executor n]
  (let [running (Semaphore. n)]
    (proxy [AbstractExecutorService ExecutorService] []
      (execute [runnable]
        (.acquire running)
        (.execute
          executor
          (reify Runnable
            (run [_] (try (.run runnable)
                          (finally
                            (.release running))))))))))

(defmacro with-root-executor
  "Rebinds the executor to the root executor, bypassing the `*local-executor*` if
  any exists."
  [& body]
  `(binding [*local-executor* nil]
     ~@body))

(defmacro fiber
  "Execute body on a loom fiber, returning a deferred that will resolve when the fiber completes."
  [& body]
  `(let [exec#  (or *local-executor* *root-executor*)
         frame# (with-root-executor
                  (clojure.lang.Var/cloneThreadBindingFrame))]

     (d/->deferred (.submit exec# ^Callable
                            (reify Callable
                              (call [_]
                                (clojure.lang.Var/resetThreadBindingFrame frame#)
                                ~@body))))))

(defmacro with-max-parallelism
  "Executes the provided body with an executor that ensures that at most `n` fibers
  will run in parallel."
  [n & body]
  `(binding [*local-executor* (max-parallelism-executor *root-executor* ~n)]
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
        (map deref)))

(defn periodically
  "Behaves similarly to `manifold.stream/periodically` but relies on a loom
  fiber for time keeping. If no initial delay is specified runs immediately.

  Also automatically coerces durations into millisecond values."
  ([period f] (periodically period nil f))
  ([period initial-delay f]
   (let [->ms       #(long (cond
                             (number? %) %
                             (nil? %)    0
                             :else       (.toMillis %)))
         initial-ms (->ms initial-delay)
         poll-ms    (->ms period)
         result     (s/stream)]
     (fiber
       (try
         (Thread/sleep initial-ms)
         (loop []
           (when-not (s/closed? result)
             @(s/put! result (f))
             (Thread/sleep poll-ms)
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
     (with-root-executor
       (s/on-drained s (bound-fn* #(fiber
                                     (when-not (.isTerminated phaser)
                                       (.awaitAdvance phaser 0))
                                     (s/close! result)))))
     (s/on-closed result #(s/close! s))
     (if seq?
       (s/stream->seq result)
       result)))
  ([n f s]
   (let [result      (s/stream)
         seq?        (seqable? s)
         s           (if seq?
                       (s/->source s)
                       s)
         work-buffer (s/stream n)
         phaser      (Phaser. n)]
     (s/connect s work-buffer)
     (with-root-executor
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
                                         (s/close! result))))
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
       true (mapv #(fiber (f %)))
       true (map deref)
       stream? s/->source)))
  ([n f s]
   (let [seq? (seqable? s)]
     (cond->> s
       seq? (s/->source)
       true (s/map #(fiber (f %)))
       true (s/buffer n)
       true (s/realize-each)
       seq? (s/stream->seq)))))
