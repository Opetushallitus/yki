(ns jeesql.core)

(defmacro require-sql [args]
  `(require '[~@args]))
