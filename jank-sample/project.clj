; Jank uses Leiningen for project management — same tool as the Clojure ecosystem.
; The format is identical to a Clojure project.clj with a jank plugin added.
;
; Clojure equivalent would just omit the :plugins entry and use .clj files.
(defproject jank-sample "0.1.0-SNAPSHOT"
  :description "A simple jank sample project"
  :url "https://github.com/example/jank-sample"
  :license {:name "MIT"}

  ; Same Maven-compatible dependency format as Clojure.
  ; Clojure libraries on Clojars generally don't work in jank (they compile to
  ; JVM bytecode), but the tooling and format are familiar.
  :dependencies []

  ; The jank Leiningen plugin compiles .jank files instead of .clj files.
  ; In a Clojure project there's no plugin needed — the compiler is built in.
  :plugins [[lein-jank "0.1.0-alpha"]]

  ; Entry point — same :main convention as Clojure
  :main jank-sample.core

  ; Source paths — same convention, just .jank files live here instead of .clj
  :source-paths ["src"])
