# Tapestry

[![Build Status](https://github.com/teknql/tapestry/actions/workflows/ci.yml/badge.svg)](https://github.com/teknql/tapestry/actions)
[![Clojars Project](https://img.shields.io/clojars/v/teknql/tapestry.svg?include_prereleases)](https://clojars.org/teknql/tapestry)
[![cljdoc badge](https://cljdoc.org/badge/teknql/tapestry)](https://cljdoc.org/d/teknql/tapestry)

Next generation concurrency primitives for Clojure built on top of Project Loom


## About

[Project Loom](https://wiki.openjdk.java.net/display/loom/Main) is bringing first-class fibers
to the JVM! Tapestry seeks to bring ergonomic clojure APIs for working with Loom.

### What are Fibers and Why do I care?

Fibers behave similarly to OS level threads, but are much lighter weight to spawn, allowing
potentially millions of them to exist.

Clojure already has the wonderful [core.async](https://github.com/clojure/core.async)
and [manifold](https://github.com/clj-commons/manifold) libraries but writing maximally performant code
in either requires the abstraction (channels, or promises) to leak all over your code
(as you return channels or promise chains) to avoid blocking. Furthermore you frequently have to
think about which executor will handle the blocking code.

Loom moves handling parking to the JVM runtime level making it possible for "blocking" code to be
executed in a highly parallel fashion without the developer having to explicitly opt into the
behavior. This is similar to how Golang and Haskell achieve their highly performant parallelism.

Some great further reading on the topic:

  - [What color is your function](https://journal.stuffwithstuff.com/2015/02/01/what-color-is-your-function/) - A mental exploration of
  the leaking of the abstraction.
  - [Async Rust Book Async Intro](https://rust-lang.github.io/async-book/01_getting_started/02_why_async.html) - A
  good explaination of why we want async. The rest of this book is fantastic in terms of
  understanding how async execution works under the hood. In `manifold` and `core.async`
  the JVM executor is mostly analogous to the rust's concept of the Executor. In a language like
  Rust, without a runtime, being explicit and "colorizing functions" makes sense, but with a
  run-time we can do better.
  - [Project Loom Wiki](https://wiki.openjdk.java.net/display/loom/Main#Main-Continuations) - Internal design notes of Loom.

### Project State

Tapestry is still pre-1.0. As the APIs in loom have stabilized so too has tapestry.

Tapestry is being used in production for several of Teknql's projects and has
more or less replaced both `clojure.core/future` and `manifold.deferred/future`.

It is the ambition of the project to eventually drop manifold entirely, at which
point it will likely hit 1.0.

## Installation

Add to your deps.edn:

```
teknql/tapestry {:mvn/version "0.4.0"}
```

## Showcase

Here is a demo of some of the basics.

#### Spawning a Fiber

```clojure
(require '[tapestry.core :refer [fiber fiber-loop]])

;; Spawning a Fiber behaves very similarly to `future` in standard clojure, but
;; runs in a Loom Fiber and returns a tapestry.core.Fiber which implements IDeref.
@(fiber (+ 1 2 3 4))
;; => 10


;; Or, Like `core.async`'s `go-loop'

@(fiber-loop [i 0]
   (if (= i 5)
     (* 2 i)
     (do (Thread/sleep 100)
         (recur (inc i)))))
;; => 10, after aprox 500ms of sleeping
```

#### Interrupting and introspecting a Fiber
```clojure
(require '[tapestry.core :refer [fiber interrupt! alive?]]')

(let [f (fiber (Thread/sleep 10000))]
  (alive? f) ;; true
  (interrupt! f)
  (alive? f) ;; false
  @f ;; Raises java.lang.InterruptedException))
```

#### Timeouts

Tapestry supports setting timeouts on fibers which will cause them to be
interrupted (with a `java.lang.InterruptedException`) when the timeout is hit.

```clojure
(require '[tapestry.core :refer [fiber timeout! alive?]]')

(let [f (fiber (Thread/sleep 10000))]
  (timeout! f 100)
  (alive? f) ;; true
  (Thread/sleep 200)
  (alive? f) ;; false
  @f ;; Raises java.util.concurrent.TimeoutException))
```

You can also specify a default value

```clojure
(require '[tapestry.core :refer [fiber timeout! alive?]]')

(let [f (fiber (Thread/sleep 10000))]
  (timeout! f 100 :default)
  @f ;; => :default))
```


You can use dynamic bindings to set a timeout on a bunch of fibers. Note that
each fiber will have a timeout that starts from when the fiber was spawned.

```clojure
(require '[tapestry.core :refer [fiber alive? with-timeout]]')

(with-timeout 100 ;; Accepts a duration or number of millis
  (let [f (fiber (Thread/sleep 10000))]
    @f ;; raises java.util.concurrent.TimeoutException
    ))
```

#### Processing Sequences
```clojure
(require '[tapestry.core :refer [parallelly asyncly pfor]]
         '[clj-http.client :as clj-http])

(def urls
  ["https://google.com"
   "https://bing.com"
   "https://yahoo.com"])

;; We can also run a function over a sequence, spawning a fiber for each item.
(->> urls
     (parallelly clj-http/get))

;; We can using the built in `pfor` macro to evaluate a `for` expression in parallel. Note that unlike
;; clojure.core/for, this is not lazy.
(pfor [url urls]
  (clj-http/get url))

;; Similalry, if we don't care about the order of items being maintained, and instead just want
;; to return results as quickly as possible

(doseq [resp (asyncly clj-http/get urls)]
  (println "Got Response!" (:status resp)))
```

#### Bounded Parallelism

```clojure
;; We can control max parallelism for fibers
(require '[tapestry.core :refer [parallelly fiber]])

;; Note that you can also use `with-max-parallelism` within a fiber body
;; which will limit parallelism of all newly spawned fibers. Consider the following
;; in which we process up to 3 orders simultaneously, and each order can process up to 2
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
(with-max-parallelism 3
  (let [order-a-summary (process-order! order-a)
        order-b-summary (process-order! order-b)
        order-c-summary (process-order! order-c)
        order-d-summary (process-order! order-d)]
    {:a @order-a-summary
     :b @order-b-summary
     :c @order-c-summary
     :d @order-d-summary})


;; You can also bound the parallelism of sequence processing functions by specifying
;; an optional bound:

(asyncly 3 clj-http/get urls)

(parallelly 3 clj-http/get urls)
```


#### Manifold Support

```clojure
(require '[manifold.stream :as s]
         '[tick.alpha.api :as t]
         '[tapestry.core :refer [periodically parallelly asyncly]])

;; tapestry.core/periodically behaves very similar to manfold's built in periodically,
;; but runs each task in a fiber. You can terminate it by closing the stream.
(let [count     (atom 0)
        generator (periodically (t/new-duration 1 :seconds) #(swap! count inc))]
    (->> generator
         (s/consume #(println "Count is now:" %)))
    (Thread/sleep 5000)
    (s/close! generator))

;; Also, `parallelly` and `asyncly` both suppport manifold streams, allowing you to describe parallel
;; execution pipelines
(->> (s/stream)
     (paralelly 5 some-operation)
     (asyncly 5 some-other-operation)
     (s/consume #(println "Got Result" %)))
```

#### Working with Agents

``` clojure
(let [counter (agent 0)]
  (tapestry.core/send counter inc)
  (await counter)
  @a)
  ;; => 1
```

## Advisories

None at the moment

## CLJ Kondo Config

Add the following to your `.clj-kondo/config.edn`

```clojure
{:lint-as {tapestry.core/fiber-loop clojure.core/loop
           tapestry.core/pfor       clojure.core/for}}
```

## Long Term Wish List

- [ ] Consider whether we can drop manifold. What do streams look like?
- [ ] Implement structured concurrency using either java built-in APIs
      (currently gated) or see if clojure affords us a nicer API.
- [ ] Consider implement linking ala erlang
- [ ] Consider implementing an OTP-like interface
- [ ] Consider cljs support
- [ ] `(parallelize ...)` macro to automatically re-write call graphs

