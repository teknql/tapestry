# Changelog

## 0.4.2

- Chore release to add a license to `pom.xml`

## 0.4.0

Release that mostly stabalizes the API (moving out of SNAPSHOT) and updates the
documentation to work with cljdoc

### Features

- Add `tapestry.core/send` for working with agents.

## 0.3.0-SNAPSHOT

This release introduces the ability to interrupt fibers as well as introspect
them. It also shores up the call signatures w/ the latest loom APIs.

It also removes manifold from the `fiber` macro taking us one step closer to
being able drop manifold.

### Breaking Changes

- *BREAKING*: Remove `tapestry.core/locking` - use `clojure.core/locking`
  instead. This was only needed a workaround until thread monitors made their
  way into loom, which they have.

- *BREAKING*: `fiber` no longer returns a manifold deferred. It now returns a
  custom type `tapestry.core.Fiber` which implements `clojure.lang.IDeref` and
  `manifold.deferred.IDeferred`. For the most part this should be a seamless
  change, but if you were explicitly relying on it being a deferred you will
  need to update your code.

### Features

- *interrupt!* - Add a function for interrupting fibers, allowing them to be
  terminated early.
- *timeout!* - Add a function for setting a timeout on fibers, which will cause
  them to be interrupted.
- *alive?* - Add a function for seeing whether a fiber is still alive.
- `tapestry.core.Fiber` type - Introduce a custom type for fibers to facilitate
  interrupts and timeouts. Also provides a custom `print-method` which makes for
  introspection.

### Fixes

- Update functions to handle the JDK-19 feature preview signatures.
- Fix an issue where using a stream w/ `parallelly` and no `n` (for bounding
  parallelism) would raise an error.
