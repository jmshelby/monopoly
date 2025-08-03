(ns jmshelby.monopoly.simulation
  "Main entry point for Monopoly simulation - delegates to CLI functionality"
  (:require [jmshelby.monopoly.simulation.cli :as cli]
            [jmshelby.monopoly.simulation.core]))

;; Re-export core simulation functions for backward compatibility
(def run-simulation jmshelby.monopoly.simulation.core/run-simulation)
(def analyze-game-outcome jmshelby.monopoly.simulation.core/analyze-game-outcome)
(def analyze-building-scarcity jmshelby.monopoly.simulation.core/analyze-building-scarcity)
(def calculate-statistics jmshelby.monopoly.simulation.core/calculate-statistics)

;; Re-export CLI functions
(def print-simulation-results cli/print-simulation-results)

;; Main entry point delegates to CLI
(defn -main [& args]
  (apply cli/-main args))
