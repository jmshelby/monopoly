(ns jmshelby.monopoly.simulation.cli
  (:require [jmshelby.monopoly.simulation.output :as output]
            [clojure.pprint :as pprint]
            [clojure.tools.cli :as cli]
            [clojure.string]))


(def cli-options
  [["-g" "--games GAMES" "Number of games to simulate"
    :default 100
    :parse-fn #(Integer/parseInt %)
    :validate [#(> % 0) "Must be a positive number"]]
   ["-p" "--players PLAYERS" "Number of players per game"
    :default 4
    :parse-fn #(Integer/parseInt %)
    :validate [#(and (>= % 2) (<= % 8)) "Must be between 2 and 8"]]
   ["-s" "--safety THRESHOLD" "Safety threshold for game termination"
    :default 1500
    :parse-fn #(Integer/parseInt %)
    :validate [#(> % 0) "Must be a positive number"]]
   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (->> ["Monopoly Game Simulation"
        ""
        "Usage: simulation [options]"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  simulation                           # 100 games, 4 players, safety 1500"
        "  simulation -g 1000                   # 1000 games with defaults"
        "  simulation -g 500 -p 3 -s 2000      # 500 games, 3 players, safety 2000"
        "  simulation --games 2000 --players 6  # 2000 games, 6 players"
        ""]
       (clojure.string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}

      ;; Handle backward compatibility with positional arguments
      (and (= 100 (:games options)) (seq arguments))
      (let [games-arg (first arguments)]
        (try
          (let [games (Integer/parseInt games-arg)]
            (if (> games 0)
              {:action :run-simulation
               :options (assoc options :games games)}
              {:exit-message "Number of games must be positive"}))
          (catch NumberFormatException _
            {:exit-message (str "Invalid number: " games-arg)})))

      :else ; failed custom validation => exit with usage summary
      {:action :run-simulation :options options})))

(defn exit [status msg]
  (println msg)
  #?(:clj (System/exit status)
     :cljs (js/process.exit status)))

(defn -main
  "Run the simulation and print results"
  [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (let [{:keys [games players safety]} options]
        (printf "Configuration: %d games, %d players, safety threshold %d\n" games players safety)
        (println)
        (output/run-and-print-simulation games players safety)))))