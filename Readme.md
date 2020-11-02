# Tapestry

[![Build Status](https://img.shields.io/github/workflow/status/teknql/tapestry/CI.svg)](https://github.com/teknql/tapestry/actions)
[![Clojars Project](https://img.shields.io/clojars/v/teknql/tapestry.svg)](https://clojars.org/teknql/tapestry)

Next generation concurrency primitives for Clojure built on top of Project Loom


## About

[Project Loom](https://wiki.openjdk.java.net/display/loom/Main) is bringing first-class fibers
to the JVM! Tapestry seeks to bring ergonomic clojure APIs for working with Loom.

### What are Fibers and Why do I care?

Fibers behave similarly to OS level threads, but are much lighter weight to spawn, allowing
potentially millions of them to exist.

Clojure already has the wonderful [core.async](https://github.com/clojure/core.async)
and [manifold](https://github.com/aleph-io/manifold) libraries but writing maximally performant code
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

Tapestry is in early alpha. It was extracted out of a project that has been using it for about six
months. Things are shaping up nicely, but there may be substantial API changes in the future.
Right now manifold is used for streams and deferred representations, but I suspect that we may be
able to remove it entirely and instead use JVM completable futures and queues. Streams may just be
lazy sequences that are processed by dedicated fibers.


## Installation

Add to your deps.edn:

```
teknql/tapestry {:mvn/version "0.0.1-SNAPSHOT"}
```


### Installing Loom

You will need to be running a loom preview build for this to work.

You can download the latest version from the [Loom Site](https://jdk.java.net/loom/).

On linux, installing it looks something like this:

```
tar -xvzf openjdk-16-loom+4-56_linux-x64_bin.tar.gz
sudo mv jdk-16/ /usr/lib/jvm/java-16-openjdk-loom-preview
cd /usr/lib/jvm
sudo rm default default-runtime
sudo ln -s java-16-openjdk-loom-preview $PWD/default
sudo ln -s java-16-openjdk-loom-preview $PWD/default-runtime
```

## Showcase

Full API documentation can be seen in the `tapestry.core` ns itself. Right now we can't build
the documentation using clj-doc. You can track the issue [here](https://github.com/cljdoc/cljdoc/issues/275)

Here is a demo of some of the basics.

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
;; => 10, after aprox 500ms of sleeping
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

(parallely 3 clj-http/get urls)
```


#### Manifold Support

```clojure
(require [manifold.stream :as s]
         [tick.api.alpha :as t]
         [tapestry.core :refer [periodically parallely asyncly]])

;; tapestry.core/periodically behaves very similar to manfold's built in periodically,
;; but runs each task in a fiber. You can terminate it by closing the stream.
(let [count     (atom 0)
        generator (periodically (t/new-duration 1 :seconds) #(swap! count inc))]
    (->> generator
         (s/consume #(println "Count is now:" %)))
    (Thread/sleep 5000)
    (s/close! generator))

;; Also, `parallely` and `asyncly` both suppport manifold streams, allowing you to describe parallel
;; execution pipelines
(->> (s/stream)
     (paralelly 5 some-operation)
     (asyncly 5 some-other-operation)
     (s/consume #(println "Got Result" %)))
```

## Advisories

### avoid `clojure.core/locking`

Clojure's built in `locking` uses java's native monitors to handle locking. This can prevent a loom
fiber from being able to yield - thereby blocking the underlying native thread and potentially lead
to starvation under enough contention. To help with this, tapestry provides its own
`tapestry.core/locking` macro which is implemented on top of a static atom.

## CLJ Kondo Config

Add the following to your `.clj-kondo/config.edn`

```clojure
{:lint-as {tapestry.core/fiber-loop        clojure.core/loop}}
```

## Long Term Wish List

- [ ] Consider whether we can drop manifold. What do streams look like?
- [ ] Consider cljs support
- [ ] `(parallelize ...)` macro to automatically re-write call graphs

