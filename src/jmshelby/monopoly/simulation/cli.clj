(ns jmshelby.monopoly.simulation.cli
  (:require [jmshelby.monopoly.simulation.core :as sim-core]
            [clojure.pprint :as pprint]
            [clojure.tools.cli :as cli]
            [clojure.string]))

(defn print-simulation-results
  "Print a human-readable summary of simulation results"
  [stats]
  (let [{:keys [total-games duration-seconds games-per-second
                games-with-winner games-without-winner winner-percentage
                failsafe-games exception-games exception-messages
                winner-distribution winner-percentages
                transaction-stats failsafe-transaction-stats incomplete-game-breakdown
                games-with-auctions auction-occurrence-rate total-auctions-initiated total-auctions-completed
                total-auctions-passed total-purchases avg-auctions-initiated-per-game avg-auctions-completed-per-game
                avg-auctions-passed-per-game auction-completion-rate auction-passed-rate auction-to-purchase-ratio
                property-declined-auction-occurrence-rate bankruptcy-auction-occurrence-rate
                property-declined-to-bankruptcy-auction-ratio total-property-declined-auctions total-bankruptcy-auctions
                auction-initiated-stats sample-auction-initiations sample-auction-completions sample-auction-passed
                games-with-building-shortages building-shortage-occurrence-rate total-house-shortage-transactions
                total-hotel-shortage-transactions total-critical-house-shortages total-last-house-purchases
                total-last-hotel-purchases total-shortage-periods avg-shortage-duration-per-game max-shortage-duration-overall]} stats]

    (println "\n=== MONOPOLY SIMULATION RESULTS ===")
    (println)

    ;; Performance Summary
    (println "🚀 PERFORMANCE")
    (printf "   Total Games: %d\n" total-games)
    (printf "   Duration: %.1f seconds\n" duration-seconds)
    (printf "   Speed: %.1f games/second\n" games-per-second)
    (println)

    ;; Game Completion Summary
    (println "🎯 GAME COMPLETION")
    (printf "   Games with Winner: %d (%.1f%%)\n" games-with-winner winner-percentage)
    (printf "   Games without Winner: %d (%.1f%%)\n"
            games-without-winner (- 100 winner-percentage))
    (when (> failsafe-games 0)
      (printf "   Failsafe Stops: %d (%.1f%%)\n"
              failsafe-games (* 100.0 (/ failsafe-games total-games))))
    (when (> exception-games 0)
      (printf "   Exception Games: %d (%.1f%%)\n"
              exception-games (* 100.0 (/ exception-games total-games))))
    (println)

    ;; Exception Details - only show if there are exceptions
    (when (and (> exception-games 0) (seq exception-messages))
      (println "💥 EXCEPTION DETAILS")
      (printf "   Games with Exceptions: %d (%.1f%%)\n"
              exception-games (* 100.0 (/ exception-games total-games)))
      (println "   Exception Messages:")
      (doseq [[message count] exception-messages]
        (let [percentage (* 100.0 (/ count exception-games))]
          (printf "     • %dx (%.1f%%) - %s\n" count percentage message)))
      (println))

    ;; Winner Distribution
    (when (seq winner-distribution)
      (println "🏆 WINNER DISTRIBUTION")
      (doseq [[winner-id count] winner-distribution]
        (let [percentage (* 100.0 (/ count games-with-winner))]
          (printf "   Player %s: %d wins (%.1f%%)\n" winner-id count percentage)))
      (println))

    ;; Transaction Statistics
    (when transaction-stats
      (println "📊 TRANSACTION STATISTICS (Winning Games)")
      (printf "   Minimum: %d transactions\n" (:min transaction-stats))
      (printf "   Maximum: %d transactions\n" (:max transaction-stats))
      (printf "   Average: %.1f transactions\n" (:avg transaction-stats))
      (printf "   Median: %d transactions\n" (:median transaction-stats))
      (println))

    ;; Auction Statistics
    (println "🏛️ AUCTION STATISTICS")
    (printf "   Games with Auctions: %d (%.2f%%)\n" games-with-auctions auction-occurrence-rate)
    (printf "   Total Auctions Initiated: %d\n" total-auctions-initiated)
    (printf "   Total Auctions Completed: %d\n" total-auctions-completed)
    (printf "   Total Auctions Passed: %d\n" total-auctions-passed)
    (printf "   Total Property Purchases: %d\n" total-purchases)
    (printf "   Auction Completion Rate: %.1f%%\n" auction-completion-rate)
    (printf "   Auction Passed Rate: %.1f%%\n" auction-passed-rate)
    (printf "   Average Auctions Initiated per Game: %.3f\n" avg-auctions-initiated-per-game)
    (printf "   Average Auctions Completed per Game: %.3f\n" avg-auctions-completed-per-game)
    (printf "   Average Auctions Passed per Game: %.3f\n" avg-auctions-passed-per-game)
    (printf "   Auction to Purchase Ratio: %.4f\n" auction-to-purchase-ratio)

    ;; Auction reason breakdown
    (printf "   Property Declined Auctions: %d (%.1f%% occurrence)\n"
            total-property-declined-auctions
            property-declined-auction-occurrence-rate)
    (printf "   Bankruptcy Auctions: %d (%.1f%% occurrence)\n"
            total-bankruptcy-auctions
            bankruptcy-auction-occurrence-rate)
    (when (> total-bankruptcy-auctions 0)
      (printf "   Property Declined to Bankruptcy Ratio: %.2f\n"
              property-declined-to-bankruptcy-auction-ratio))

    (when auction-initiated-stats
      (printf "   Auction Initiated Stats - Min: %d, Max: %d, Avg: %.1f, Median: %d\n"
              (:min auction-initiated-stats) (:max auction-initiated-stats)
              (:avg auction-initiated-stats) (:median auction-initiated-stats)))
    (when (seq sample-auction-completions)
      (println "   Sample Auction Completions:")
      (doseq [auction (take 3 sample-auction-completions)]
        (printf "     %s: Winner %s, Bid $%d, Participants: %s\n"
                (:property auction) (:winner auction) (:winning-bid auction)
                (clojure.string/join ", " (:participants auction)))))
    (when (= 0 total-auctions-initiated)
      (println "   🤔 No auctions initiated - all properties likely purchased before cash pressure"))
    (println)

    ;; Building Scarcity Statistics
    (println "🏠 BUILDING SCARCITY ANALYSIS")
    (printf "   Games with Building Shortages: %d (%.2f%%)\n"
            games-with-building-shortages building-shortage-occurrence-rate)
    (printf "   Total House Shortage Transactions: %d\n" total-house-shortage-transactions)
    (printf "   Total Hotel Shortage Transactions: %d\n" total-hotel-shortage-transactions)
    (printf "   Critical House Shortages (≤2 houses): %d\n" total-critical-house-shortages)
    (printf "   Last House Purchases (inventory exhausted): %d\n" total-last-house-purchases)
    (printf "   Last Hotel Purchases (inventory exhausted): %d\n" total-last-hotel-purchases)
    (printf "   Total Shortage Periods: %d\n" total-shortage-periods)
    (when avg-shortage-duration-per-game
      (printf "   Average Shortage Duration per Game: %.2f transactions\n" avg-shortage-duration-per-game))
    (when max-shortage-duration-overall
      (printf "   Maximum Shortage Duration: %d transactions\n" max-shortage-duration-overall))
    (when (= 0 games-with-building-shortages)
      (println "   📈 No building shortages observed - inventory constraints not reached"))
    (println)

    ;; Failsafe Transaction Statistics
    (when failsafe-transaction-stats
      (println "⏱️  FAILSAFE TRANSACTION STATISTICS (Incomplete Games)")
      (printf "   Minimum: %d transactions\n" (:min failsafe-transaction-stats))
      (printf "   Maximum: %d transactions\n" (:max failsafe-transaction-stats))
      (printf "   Average: %.1f transactions\n" (:avg failsafe-transaction-stats))
      (printf "   Median: %d transactions\n" (:median failsafe-transaction-stats))
      (println))

    ;; Incomplete Game Analysis
    (when (seq incomplete-game-breakdown)
      (println "❌ INCOMPLETE GAME BREAKDOWN")
      (doseq [[active-count game-count] incomplete-game-breakdown]
        (let [percentage (* 100.0 (/ game-count total-games))]
          (printf "   %d active players: %d games (%.1f%%)\n"
                  active-count game-count percentage)))
      (println))

    (println "=== END SIMULATION RESULTS ===")))

(defn run-and-print-simulation
  "Run simulation and print results with progress reporting"
  ([num-games] (run-and-print-simulation num-games 4 1500))
  ([num-games num-players safety-threshold]
   (println (format "Starting simulation of %d games with %d players each (safety: %d)..."
                    num-games num-players safety-threshold))
   (let [progress-reporter (fn [game-num]
                             (when (= 0 (mod game-num 100))
                               (println (format "Completed %d/%d games..." game-num num-games))))
         stats (time (sim-core/run-simulation num-games num-players safety-threshold))]
     (println (format "Simulation completed in %.1f seconds" (:duration-seconds stats)))
     (print-simulation-results stats)
     stats)))

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
  (System/exit status))

(defn -main
  "Run the simulation and print results"
  [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (let [{:keys [games players safety]} options]
        (printf "Configuration: %d games, %d players, safety threshold %d\n" games players safety)
        (println)
        (run-and-print-simulation games players safety)))))