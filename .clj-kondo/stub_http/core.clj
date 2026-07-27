(ns stub-http.core)

; Dummy implementation of with-routes! that lets clj-kondo know
; that `server` and `port` are defined by the with-routes! macro.
; `server` is modelled with a derefable `:routes` and a numeric `:port` so that
; `@(:routes server)` type-checks; newer clj-kondo errors on dereferencing nil.
(defmacro with-routes! [bindings-or-routes & more-args]
  (let [[bindings routes body] (if (vector? bindings-or-routes)
                            [bindings-or-routes (first more-args) (rest more-args)]
                            [[] bindings-or-routes more-args])]
    `(let ~bindings
       (let [~'server {:routes (atom []) :port 0}
             ~'port (:port ~'server)]
         ~routes
         ~@body))))
