(ns tapestry.queue
  (:require [tapestry.experimental :refer [alts]])
  (:import [java.util.concurrent
            CompletableFuture
            SynchronousQueue ArrayBlockingQueue BlockingQueue LinkedBlockingQueue
            TimeUnit]))

(deftype Queue [^BlockingQueue q ^CompletableFuture closed?*])

(defmethod print-method Queue [^Queue q ^java.io.Writer w]
  (let [bq ^BlockingQueue (.-q q)]
    (.write w
            (str "#queue"
                 {:capacity (+ (.remainingCapacity bq)
                               (.size bq))
                  :items    (into [] bq)
                  :closed?  (.isDone ^CompletableFuture (.-closed?* q))}))))

(defn queue
  "Create a new queue with an optional `capacity`.

  If no capacity is specified it's a synchrounous queue.

  Passing `:unbounded` will create an unbounded queue holding at most Integer/MAX_VALUE items.."
  ([] (queue nil))
  ([capacity]
   (let [q (case capacity
             nil        (SynchronousQueue. true)
             :unbounded (LinkedBlockingQueue.)
             (ArrayBlockingQueue. capacity true))]
     (Queue. q (CompletableFuture.)))))

(defn queue?
  "Return whether the provided `obj` is a queue"
  [obj]
  (instance? Queue obj))

(defn closed?
  "Return whether the provided queue is closed"
  [^Queue q]
  (.isDone (.-closed?* q)))

(defn await-close
  "Block until `q` closes"
  [^Queue q]
  @(.-closed?* q))

(defn put!
  "Place an `item` in the `q`, potentially blocking until space is available.

  Return `true` if the item is queues, or `false` if it is closed"
  ([^Queue q item]
   (if (closed? q)
     false
     (alts
       (do (await-close q)
           false)
       (do (.put ^BlockingQueue (.-q q) item)
           true)))))

(defn try-put!
  "Place an `item` in the `q`, waiting at most `timeout-ms`.

  Return `true` if the queue successfully takes the `item`, or `false`` if it is closed / closes
  before the item is accepted.

  Takes an optional `timeout-val` to return in the case of timeout"
  ([^Queue q item]
   (try-put! q item 0 false))
  ([^Queue q item timeout-ms]
   (try-put! q item timeout-ms false))
  ([^Queue q item timeout-ms timeout-val]
   (if (closed? q)
     false
     (alts
       (do (await-close q)
           false)
       (or (.offer ^BlockingQueue (.-q q) item timeout-ms TimeUnit/MILLISECONDS)
           timeout-val)))))

(defn take!
  "Take from the `q`, blocking until an item is available. Returns `nil` if the queue is closed."
  [^Queue q]
  (if-some [item (.poll ^BlockingQueue (.-q q) 0 TimeUnit/NANOSECONDS)]
    item
    (alts
      (do (await-close q)
          nil)
      (.take ^BlockingQueue (.-q q)))))

(defn try-take!
  "Try to take from the `q`, waiting at most `timeout-ms` milliseconds before returning
  `timeout-val`.

  Returns `nil` if the `q` is closed."
  ([q] (try-take! q 0 nil))
  ([q timeout-ms] (try-take! q timeout-ms nil))
  ([^Queue q timeout-ms timeout-val]
   (if-some [x (.poll ^BlockingQueue (.-q q) 0 TimeUnit/MILLISECONDS)]
     x
     (if (closed? q)
       nil
       (alts
         (do (await-close q)
             nil)
         (if-some [x (.poll ^BlockingQueue (.-q q) timeout-ms TimeUnit/MILLISECONDS)]
           x
           timeout-val))))))

(defn close!
  "Close the `q`.

  Takes will return items until the `q` has been drained."
  [^Queue q]
  (.complete ^CompletableFuture (.-closed?* q) true)
  true)
