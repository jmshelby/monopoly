(ns jmshelby.scoundrel.simulation.cli
  (:require [jmshelby.scoundrel.simulation :as sim]
            [clojure.core.async :as async]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(def cli-options
  [["-g" "--games NUM" "Number of games to simulate"
    :default 5000
    :parse-fn #(Integer/parseInt %)
    :validate [#(> % 0) "Must be a positive number"]]
   ["-p" "--player TYPE" "Player type (random, greedy, or smart)"
    :default :random
    :parse-fn #(keyword (clojure.string/replace % #"^:" ""))
    :validate [#(#{:random :greedy :smart} %) "Must be random, greedy, or smart"]]
   ["-t" "--max-turns NUM" "Maximum turns per game"
    :default 100
    :parse-fn #(Integer/parseInt %)
    :validate [#(> % 0) "Must be a positive number"]]
   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (->> ["Scoundrel Solitaire Game Simulator"
        ""
        "Usage: clojure -M:sim [options]"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  clojure -M:sim                          # Run 5000 games with random player"
        "  clojure -M:sim -g 1000                  # Run 1000 games"
        "  clojure -M:sim -p greedy -g 10000       # Run 10000 games with greedy player"
        "  clojure -M:sim -p smart -g 10000        # Run 10000 games with smart player"
        "  clojure -M:sim -p :smart -g 10000       # Also works with colon prefix"
        ""]
       (clojure.string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (do (println (usage summary))
          (System/exit 0))

      errors
      (do (println (error-msg errors))
          (println)
          (println (usage summary))
          (System/exit 1))

      :else
      (let [num-games (:games options)
            player-type (:player options)
            max-turns (:max-turns options)]
        (println (format "\nRunning %d games with %s player (max %d turns per game)..."
                         num-games (name player-type) max-turns))
        (println "This may take a moment...\n")

        (let [start-time (System/currentTimeMillis)
              output-ch (sim/run-simulation num-games player-type max-turns)

              ;; Collect all results
              results (loop [acc []]
                        (if-let [result (async/<!! output-ch)]
                          (recur (conj acc result))
                          acc))

              end-time (System/currentTimeMillis)
              duration-ms (- end-time start-time)

              ;; Aggregate statistics
              stats (assoc (sim/aggregate-results results)
                          :duration-seconds (/ duration-ms 1000.0)
                          :games-per-second (/ num-games (/ duration-ms 1000.0)))]

          ;; Print results
          (sim/print-simulation-results stats player-type)

          (println "=== Performance ===")
          (println (format "Duration: %.2f seconds" (:duration-seconds stats)))
          (println (format "Games per second: %.1f" (:games-per-second stats)))
          (println)

          (System/exit 0))))))
