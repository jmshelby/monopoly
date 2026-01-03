(ns jmshelby.farkle.simulation.cli
  (:require [jmshelby.farkle.simulation :as sim]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(def cli-options
  [["-g" "--games GAMES" "Number of games to simulate"
    :default 100
    :parse-fn #(Integer/parseInt %)
    :validate [#(> % 0) "Must be a positive number"]]
   ["-p" "--players PLAYERS" "Number of players per game"
    :default 4
    :parse-fn #(Integer/parseInt %)
    :validate [#(and (>= % 2) (<= % 8)) "Must be between 2 and 8"]]
   ["-s" "--safety SAFETY" "Safety threshold (max iterations per game)"
    :default 5000
    :parse-fn #(Integer/parseInt %)
    :validate [#(> % 0) "Must be a positive number"]]
   ["-h" "--help" "Show this help message"]])

(defn -main
  [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      errors
      (do
        (println "Errors:")
        (doseq [error errors]
          (println "  " error))
        (println summary)
        (System/exit 1))

      (:help options)
      (do
        (println "Farkle Simulation Runner")
        (println summary)
        (System/exit 0))

      :else
      (let [{:keys [games players safety]} options]
        (println (format "Running %d Farkle games with %d players each..." games players))
        (let [results (sim/run-simulation games players safety)]
          (sim/print-simulation-results results)
          (System/exit 0))))))
