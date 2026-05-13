(ns tapestry.experimental.scope
  "Experimental structured concurrency primitives for Tapestry.

  Provides `with-scope` for structured lifecycle management of fibers,
  unifying timeout, max-parallelism, and shutdown policies into a single
  scope construct."
  (:require [tapestry.core :as tc])
  (:import [java.util.concurrent Semaphore CompletableFuture]
           [java.util.function BiConsumer]
           [java.lang VirtualThread]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Structured scopes
;; ---------------------------------------------------------------------------

(defrecord ^:no-doc Scope [shutdown-policy fibers first-result first-error])

(defn ^:no-doc make-scope
  "Create a new scope with the given shutdown policy."
  [shutdown-policy]
  (->Scope shutdown-policy (atom []) (promise) (promise)))

(defn ^:no-doc register-fiber!
  "Register a fiber with the scope and wire up shutdown policy handlers."
  [^Scope scope fiber]
  (swap! (:fibers scope) conj fiber)
  ;; If the scope has already been shut down (an earlier fiber completed before
  ;; this one was registered), interrupt immediately.
  (case (:shutdown-policy scope)
    :on-success (when (realized? (:first-result scope))
                  (tc/interrupt! fiber))
    :on-failure (when (realized? (:first-error scope))
                  (tc/interrupt! fiber))
    nil)
  (let [^CompletableFuture cf (.future ^tapestry.core.Fiber fiber)]
    (.whenComplete cf
                   (reify BiConsumer
                     (accept [_ res ex]
                       (case (:shutdown-policy scope)
                         :on-failure
                         (when ex
                           (deliver (:first-error scope) ex)
                           ;; Interrupt all sibling fibers
                           (doseq [^tapestry.core.Fiber f @(:fibers scope)]
                             (when (and (not (identical? f fiber))
                                        (tc/alive? f))
                               (tc/interrupt! f))))

                         :on-success
                         (when-not ex
                           (deliver (:first-result scope) res)
                           ;; Interrupt all sibling fibers
                           (doseq [^tapestry.core.Fiber f @(:fibers scope)]
                             (when (and (not (identical? f fiber))
                                        (tc/alive? f))
                               (tc/interrupt! f))))

                         ;; No shutdown policy — do nothing
                         nil))))))

(defn ^:no-doc await-all!
  "Wait for all registered fibers to fully terminate (thread join, not just CF completion)."
  [^Scope scope]
  (doseq [^tapestry.core.Fiber fiber @(:fibers scope)]
    (let [^VirtualThread vt (.virtualThread fiber)]
      (.join vt))))

(defn ^:no-doc shutdown-all!
  "Interrupt all fibers that are still alive in the scope."
  [^Scope scope]
  (doseq [^tapestry.core.Fiber fiber @(:fibers scope)]
    (when (tc/alive? fiber)
      (tc/interrupt! fiber))))

(defn ^:no-doc throw-if-failed!
  "If the scope has a recorded error and the policy is :on-failure, throw it."
  [^Scope scope]
  (when (= :on-failure (:shutdown-policy scope))
    (let [err (:first-error scope)]
      (when (realized? err)
        (let [e @err]
          (throw (if (instance? Throwable e)
                   e
                   (ex-info "Scope fiber failed" {:error e}))))))))

(defmacro with-scope
  "Execute body within a structured scope that manages fiber lifecycles.

  Takes an options map with the following keys:
    :shutdown        - Shutdown policy: :on-failure or :on-success (optional)
    :timeout         - Timeout in millis or a Duration applied to all fibers (optional)
    :max-parallelism - Maximum number of fibers running in parallel (optional)

  All fibers created with `tapestry.core/fiber` within the body are automatically
  registered with the scope.

  On scope exit:
    - All registered fibers are awaited (thread join, not just CF completion)
    - If :shutdown is :on-failure and any fiber failed, the error is propagated
    - If :shutdown is :on-success, the first successful result is available

  Example:
    (with-scope {:timeout 5000 :shutdown :on-failure :max-parallelism 4}
      (let [a (fiber (do-a))
            b (fiber (do-b))]
        {:a @a :b @b}))"
  [opts & body]
  `(let [opts#      ~opts
         shutdown#  (:shutdown opts#)
         timeout#   (:timeout opts#)
         max-par#   (:max-parallelism opts#)
         scope#     (make-scope shutdown#)
         semaphore# (when max-par# (Semaphore. (int max-par#)))
         register#  (fn [fiber#] (register-fiber! scope# fiber#))]
     (binding [tc/*scope*           scope#
               tc/*scope-register!* register#
               tc/*local-semaphore* (or semaphore# tc/*local-semaphore*)
               tc/*local-timeout*   (or timeout# tc/*local-timeout*)]
       (try
         (let [result# (do ~@body)]
           (await-all! scope#)
           (throw-if-failed! scope#)
           result#)
         (catch Throwable t#
           (shutdown-all! scope#)
           (await-all! scope#)
           (throw t#))))))


