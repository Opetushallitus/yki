(ns jeesql.core)

; Dummy implementation of require-sql to guide clj-kondo's analysis.
;
; Without additional guidance, given the below snippet:
; ```
; (ns example (:require [jeesql.core :refer [require-sql]])
; (require-sql ["yki/queries.sql" :as q])
; (q/some-sql-query ...)
; ```
;
; clj-kondo would report something like the following errors and warnings:
; ```
;   error: Unresolved symbol: q
;   warning: Unresolved namespace q. Are you missing a require?
; ```

(defmacro require-sql [args]
  `(require '[~@args]))
