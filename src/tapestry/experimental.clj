(ns tapestry.experimental
  "Experimental utilities for Tapestry."
  (:require [tapestry.core :as tc]
            [tapestry.experimental.scope :as scope]))

(defmacro with-scope
  "Execute body within a structured scope that manages fiber lifecycles.

  See `tapestry.experimental.scope/with-scope` for full documentation."
  [opts & body]
  `(scope/with-scope ~opts ~@body))

(defmacro alts
  "Execute the given `exprs` in parallel and return the result of the first one to
  succeed, interrupting all others.

  Example:
    (alts
      (fetch-from-primary)
      (fetch-from-replica)
      (fetch-from-cache))

  Optionally takes an options map as the first argument:
    (alts {:timeout 3000}
      (fetch-from-primary)
      (fetch-from-replica))"
  [& args]
  (let [[opts exprs] (if (map? (first args))
                       [(first args) (rest args)]
                       [{} args])]
    `(let [scope-opts# (merge {:shutdown :on-success} ~opts)]
       (scope/with-scope scope-opts#
         ~@(map (fn [expr] `(tc/fiber ~expr)) exprs)
         (let [scope# tc/*scope*]
           @(:first-result scope#))))))
