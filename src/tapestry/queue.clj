(ns tapestry.queue
  (:import [java.util ArrayDeque Deque LinkedList]
           [java.util.concurrent CompletableFuture TimeUnit]
           [java.util.concurrent.locks ReentrantLock Condition]))

;; A `Parcel` wraps an item handed off through a synchronous (rendezvous)
;; queue. The putter parks until `delivered?` flips to true, set by the
;; taker that pulls the parcel from the deque. Identity (`instance? Parcel`)
;; distinguishes wrapped from raw items.
(deftype ^:private Parcel [item ^clojure.lang.Volatile delivered?])

(definterface ^:private IQueue
  (^Object  putValue     [item])
  (^Object  tryPutValue  [item ^long timeout-ms timeout-val])
  (^Object  takeValue    [])
  (^Object  tryTakeValue [^long timeout-ms timeout-val])
  (^boolean closeQueue   []))

(defn- -deadline-nanos ^long [^long timeout-ms]
  (+ (System/nanoTime) (.toNanos TimeUnit/MILLISECONDS timeout-ms)))

(deftype Queue [^ReentrantLock     lock
                ^Condition         not-empty
                ^Condition         not-full
                ^Condition         delivered   ;; sync mode only
                ^Deque             items
                ^long              capacity
                sync?
                ^CompletableFuture closed?*]
  IQueue
  (putValue [_ item]
    (.lockInterruptibly lock)
    (try
      (loop []
        (cond
          (.isDone closed?*)
          false

          (< (.size items) capacity)
          (if sync?
            (let [delivered? (volatile! false)
                  parcel     (Parcel. item delivered?)]
              (.addLast items parcel)
              (.signal not-empty)
              (loop []
                (cond
                  @delivered?        true
                  (.isDone closed?*) (do (.remove items parcel)
                                         (.signal not-full)
                                         false)
                  :else              (do (.await delivered)
                                         (recur)))))
            (do (.addLast items item)
                (.signal not-empty)
                true))

          :else
          (do (.await not-full)
              (recur))))
      (finally (.unlock lock))))

  (tryPutValue [_ item timeout-ms timeout-val]
    (let [deadline (-deadline-nanos timeout-ms)]
      (.lockInterruptibly lock)
      (try
        (loop []
          (cond
            (.isDone closed?*)
            false

            (< (.size items) capacity)
            (if sync?
              (let [delivered? (volatile! false)
                    parcel     (Parcel. item delivered?)]
                (.addLast items parcel)
                (.signal not-empty)
                (loop []
                  (cond
                    @delivered?
                    true

                    (.isDone closed?*)
                    (do (.remove items parcel)
                        (.signal not-full)
                        false)

                    :else
                    (let [remain (- deadline (System/nanoTime))]
                      (if (pos? remain)
                        (do (.awaitNanos delivered remain)
                            (recur))
                        (do (.remove items parcel)
                            (.signal not-full)
                            timeout-val))))))
              (do (.addLast items item)
                  (.signal not-empty)
                  true))

            :else
            (let [remain (- deadline (System/nanoTime))]
              (if (pos? remain)
                (do (.awaitNanos not-full remain)
                    (recur))
                timeout-val))))
        (finally (.unlock lock)))))

  (takeValue [_]
    (.lockInterruptibly lock)
    (try
      (loop []
        (cond
          ;; Items first, always — drains even when closed.
          (not (.isEmpty items))
          (let [head (.pollFirst items)]
            (.signal not-full)
            (if (instance? Parcel head)
              (let [^Parcel p head]
                (vreset! (.-delivered? p) true)
                (.signal delivered)
                (.-item p))
              head))

          (.isDone closed?*) nil

          :else
          (do (.await not-empty)
              (recur))))
      (finally (.unlock lock))))

  (tryTakeValue [_ timeout-ms timeout-val]
    (let [deadline (-deadline-nanos timeout-ms)]
      (.lockInterruptibly lock)
      (try
        (loop []
          (cond
            (not (.isEmpty items))
            (let [head (.pollFirst items)]
              (.signal not-full)
              (if (instance? Parcel head)
                (let [^Parcel p head]
                  (vreset! (.-delivered? p) true)
                  (.signal delivered)
                  (.-item p))
                head))

            (.isDone closed?*) nil

            :else
            (let [remain (- deadline (System/nanoTime))]
              (if (pos? remain)
                (do (.awaitNanos not-empty remain)
                    (recur))
                timeout-val))))
        (finally (.unlock lock)))))

  (closeQueue [_]
    (.lock lock)
    (try
      (when-not (.isDone closed?*)
        (.complete closed?* true)
        (.signalAll not-empty)
        (.signalAll not-full)
        (.signalAll delivered))
      (finally (.unlock lock)))
    true))

;; ---- Public API ----

(defn queue
  "Create a new queue with an optional `capacity`.

  If no capacity is specified it's a synchronous queue.

  Passing `:unbounded` will create an unbounded queue holding at most
  Long/MAX_VALUE items."
  ([] (queue nil))
  ([capacity]
   (let [lock       (ReentrantLock.)
         sync?      (nil? capacity)
         unbounded? (= :unbounded capacity)
         cap        (long (cond
                            sync?      1
                            unbounded? Long/MAX_VALUE
                            :else      capacity))
         ;; LinkedList for unbounded (no resize cost, no large array alloc);
         ;; ArrayDeque presized for bounded (cache-friendly, no per-node alloc).
         items      (if unbounded?
                      (LinkedList.)
                      (ArrayDeque. cap))]
     (Queue. lock
             (.newCondition lock)
             (.newCondition lock)
             (.newCondition lock)
             items
             cap
             sync?
             (CompletableFuture.)))))

(defn queue?
  "Return whether the provided `obj` is a queue"
  [obj]
  (instance? Queue obj))

(defn closed?
  "Return whether the provided queue is closed"
  [^Queue q]
  (.isDone ^CompletableFuture (.-closed?* q)))

(defn await-close
  "Block until `q` closes"
  [^Queue q]
  @(.-closed?* q))

(defn close!
  "Close the `q`.

  Subsequent puts return `false`. Pending and future takes drain remaining
  items, then return `nil`."
  [^Queue q]
  (.closeQueue q))

(defn put!
  "Place an `item` in the `q`, potentially blocking until space is available.

  Returns `true` if the item is queued, or `false` if the queue is closed."
  [^Queue q item]
  (.putValue q item))

(defn try-put!
  "Place an `item` in the `q`, waiting at most `timeout-ms`.

  Returns `true` if the queue accepts the `item`, `false` if it is closed
  (or closes before the item is accepted), or `timeout-val` if the timeout
  elapses. `timeout-val` defaults to `false`."
  ([^Queue q item]                        (.tryPutValue q item 0 false))
  ([^Queue q item timeout-ms]             (.tryPutValue q item timeout-ms false))
  ([^Queue q item timeout-ms timeout-val] (.tryPutValue q item timeout-ms timeout-val)))

(defn take!
  "Take from the `q`, blocking until an item is available.

  Returns `nil` if the queue is closed and drained."
  [^Queue q]
  (.takeValue q))

(defn try-take!
  "Try to take from the `q`, waiting at most `timeout-ms` milliseconds before
  returning `timeout-val` (default `nil`).

  Returns `nil` if the `q` is closed and drained."
  ([^Queue q]                        (.tryTakeValue q 0 nil))
  ([^Queue q timeout-ms]             (.tryTakeValue q timeout-ms nil))
  ([^Queue q timeout-ms timeout-val] (.tryTakeValue q timeout-ms timeout-val)))

;; ---- print-method ----

(defmethod print-method Queue [^Queue q ^java.io.Writer w]
  (let [lock  ^ReentrantLock     (.-lock q)
        items ^Deque             (.-items q)
        cf    ^CompletableFuture (.-closed?* q)
        sync? (.-sync? q)]
    (.lock lock)
    (try
      (let [snapshot (mapv (fn [x]
                             (if (instance? Parcel x)
                               (.-item ^Parcel x)
                               x))
                           (.toArray items))]
        (.write w
                (str "#queue"
                     {:capacity (if sync? :sync (.-capacity q))
                      :items    snapshot
                      :closed?  (.isDone cf)})))
      (finally (.unlock lock)))))
