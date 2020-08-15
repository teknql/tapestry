# Tapestry

Next generation concurrency primitives for Clojure built on top of Project Loom


## About

[Project Loom](https://wiki.openjdk.java.net/display/loom/Main) is bringing first-class fibers
to the JVM! Tapestry seeks to bring ergonomic clojure APIs for working with Loom.

State: Early alpha. This was extracted out of a project that has been using it for about six months.
Things are shaping up nicely, but there may be substantial API changes in the future.
Right now manifold is used for streams and deferred representations, but I suspect that we may be
able to remove it entirely and instead use JVM completable futures and queues. Streams may just be
lazy sequences that are processed by dedicated fibers.


## Showcase

Full API documentation can be seen in Clojuredoc. Here is a demo of some of the basics.

#### Spawning a Fiber

```clojure
(require [tapestry.core :refer [fiber]])
;; Spawning a Fiber behaves very similarly to `future` in standard clojure, but
;; runs in a Loom Fiber and returns a manifold deferred.
@(fiber (+ 1 2 3 4))
;; => 10


;; Or, Like `core.async`'s `go-loop'

@(fiber-loop [i 0]
   (if (= i 5)
     (* 2 i)
     (do (Thread/sleep 100)
         (recur (inc i)))))
;; => 10, afte aprox 500ms of sleeping
```

#### Processing Sequences
```clojure
(require [tapestry.core :refer [parallely asyncly]])
(def urls
  ["https://google.com"
   "https://bing.com"
   "https://yahoo.com"])

;; We can also run a function over a sequence, spawning a fiber for each item.
(->> urls
     (parallelly clj-http/get))

;; Similalry, if we don't care about the order of items being maintained, and instead just want
;; to return results as quickly as possible

(doseq [resp (asyncly clj-http/get urls)]
  (println "Got Response!" (:status resp)))
```

#### Bounded Parallelism

```clojure
;; We can control max parallelism for fibers
(require [tapestry.core :refer [parallely]])
(with-max-parallelism 10
  (parallely clj-http/get urls))

;; Note that you can also use `with-max-parallism` within a fiber body
;; which will limit parallelism of all newly spawned fibers. Consider the following
;; in which we process up to 5 orders simultaneously, and each order can process up to 2
;; tasks in parallel.
(defn process-order!
  [order]
  (with-max-parallelism 2
    (let [internal-notification-success? (fiber (send-internal-notification! order))
          shipping-success?     (fiber (ship-order! order))
          receipt-success?      (fiber (send-receipt! order))]
      {:is-notified @internal-notification-success?
       :is-shipped  @shipping-success?
       :has-receipt @receipt-success?})))
(with-max-parallelism 5
  (parallely process-order! orders))
```

## Long Term Wish List

- [ ] Consider whether we can drop manifold. What do streams look like?
- [ ] Consider cljs support
- [ ] `(parallelize ...)` macro to automatically re-write call graphs

