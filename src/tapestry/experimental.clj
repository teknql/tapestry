(ns tapestry.experimental
  (:import [java.util.concurrent StructuredTaskScope StructuredTaskScope$Joiner]))

(defmacro alts
  "Execute the given `exprs` in parallel and return the result of the first one to return,
  shutting down all others with an interrupted exception."
  [& exprs]
  (let [scope (gensym "scope")]
    `(with-open [~scope (StructuredTaskScope/open (StructuredTaskScope$Joiner/anySuccessfulResultOrThrow))]
       ~@(for [expr exprs]
           `(.fork ~scope ^java.util.concurrent.Callable (fn [] ~expr)))
       (.join ~scope))))
