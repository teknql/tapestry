(ns tapestry.experimental
  (:import [java.util.concurrent StructuredTaskScope$ShutdownOnSuccess]))


(defmacro alts
  "Execute the given `exprs` in parallel and return the result of the first one to return,
  shutting down all others with an interrupted exception."
  [& exprs]
  (let [scope (gensym "scope")]
    `(with-open [~scope (StructuredTaskScope$ShutdownOnSuccess.)]
       ~@(for [expr exprs]
           `(.fork ~scope (fn [] ~expr)))
       (.join ~scope)
       (.result ~scope #(throw %)))))
