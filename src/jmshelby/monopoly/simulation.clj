(ns jmshelby.monopoly.simulation
  (:require [jmshelby.monopoly.core :as core]
            [jmshelby.monopoly.util :as util]
            [clojure.pprint :as pprint]
            [clojure.core.async :as async :refer [>! <! >!! <!! go go-loop chan close! pipeline thread]]
            [clojure.tools.cli :as cli]))

(defn analyze-building-scarcity
  "Analyze building inventory scarcity patterns from game transactions"
  [transactions]
  (let [building-txs (->> transactions
                          (filter #(#{:purchase-house :sell-house} (:type %)))
                          (filter #(and (:houses-available %) (:hotels-available %))))
        
        ;; Track shortages
        house-shortages (->> building-txs
                             (filter #(< (:houses-available %) 5))
                             count)
        hotel-shortages (->> building-txs
                             (filter #(= (:hotels-available %) 0))
                             count)
        
        ;; Critical shortages (very low inventory)
        critical-house-shortages (->> building-txs
                                      (filter #(<= (:houses-available %) 2))
                                      count)
        
        ;; Last building purchases
        last-house-purchases (->> building-txs
                                  (filter #(and (= :purchase-house (:type %))
                                                (= (:houses-available %) 0)))
                                  count)
        last-hotel-purchases (->> building-txs
                                  (filter #(and (= :purchase-house (:type %))
                                                (= :hotel (:building-type %))
                                                (= (:hotels-available %) 0)))
                                  count)
        
        ;; Simplified shortage analysis - count shortage periods
        shortage-states (->> building-txs
                             (map-indexed (fn [idx tx]
                                            {:idx idx
                                             :houses-low? (< (:houses-available tx) 5)
                                             :hotels-gone? (= (:hotels-available tx) 0)}))
                             (map #(assoc % :has-shortage? (or (:houses-low? %) (:hotels-gone? %)))))
        
        ;; Simple shortage periods calculation
        shortage-transitions (->> shortage-states
                                  (partition 2 1)
                                  (filter (fn [[prev curr]]
                                            ;; Look for transitions into shortage
                                            (and (not (:has-shortage? prev))
                                                 (:has-shortage? curr))))
                                  count)
        
        max-shortage-duration (->> shortage-states
                                   (partition-by :has-shortage?)
                                   (filter #(:has-shortage? (first %)))
                                   (map count)
                                   (apply max 0))]
    
    {:building-transaction-count (count building-txs)
     :house-shortage-transactions house-shortages
     :hotel-shortage-transactions hotel-shortages
     :critical-house-shortage-transactions critical-house-shortages
     :last-house-purchases last-house-purchases
     :last-hotel-purchases last-hotel-purchases
     :total-shortage-periods shortage-transitions
     :max-shortage-duration max-shortage-duration
     :avg-shortage-duration (when (and (seq shortage-states) (> shortage-transitions 0))
                              (double (/ max-shortage-duration shortage-transitions)))
     :had-building-shortages (or (> house-shortages 0) (> hotel-shortages 0))}))

(defn analyze-game-outcome
  "Analyze a single game result and return outcome statistics"
  [game-state]
  (let [active-players (->> game-state :players (filter #(= :playing (:status %))))
        transactions (:transactions game-state)
        tx-count (count transactions)
        winner-id (when (= 1 (count active-players)) (:id (first active-players)))

        ;; Auction analysis
        auction-initiated-txs (->> transactions (filter #(= :auction-initiated (:type %))))
        auction-completed-txs (->> transactions (filter #(= :auction-completed (:type %))))
        auction-passed-txs (->> transactions (filter #(= :auction-passed (:type %))))

        ;; Auction reason breakdown
        property-declined-auctions (->> auction-initiated-txs (filter #(= :property-declined (:reason %))))
        bankruptcy-auctions (->> auction-initiated-txs (filter #(= :bankruptcy (:reason %))))
        purchase-txs (->> transactions (filter #(= :purchase (:type %))))
        
        ;; Building scarcity analysis
        building-analysis (analyze-building-scarcity transactions)]
    {:has-winner (= 1 (count active-players))
     :winner-id winner-id
     :transaction-count tx-count
     :active-player-count (count active-players)
     :failed-to-complete (= :playing (:status game-state))
     :hit-failsafe (boolean (:failsafe-stop game-state))
     :had-exception (boolean (:exception game-state))
     :exception-message (when (:exception game-state)
                          (get-in game-state [:exception :message]))

     ;; Auction statistics
     :auction-initiated-count (count auction-initiated-txs)
     :auction-completed-count (count auction-completed-txs)
     :auction-passed-count (count auction-passed-txs)
     :purchase-count (count purchase-txs)
     :has-auctions (> (count auction-initiated-txs) 0)
     :auction-initiated-transactions auction-initiated-txs
     :auction-completed-transactions auction-completed-txs
     :auction-passed-transactions auction-passed-txs

     ;; Auction reason breakdown
     :property-declined-auction-count (count property-declined-auctions)
     :bankruptcy-auction-count (count bankruptcy-auctions)
     :property-declined-auctions property-declined-auctions
     :bankruptcy-auctions bankruptcy-auctions
     
     ;; Building scarcity results
     :building-scarcity building-analysis}))

(defn run-simulation
  "Run a large number of game simulations using core.async pipeline for memory efficiency"
  ([num-games] (run-simulation num-games 4 1500))
  ([num-games num-players safety-threshold]
   (println (format "Starting simulation of %d games with %d players each (safety: %d)..."
                    num-games num-players safety-threshold))
   (let [start-time (System/currentTimeMillis)
         parallelism  (+ 2 (* 2 (.. Runtime getRuntime availableProcessors)))

         ;; Create channels
         input-ch (chan 10)    ; Buffer for game numbers 
         output-ch (chan 200)   ; Buffer for results

         ;; Function that processes a single game
         process-game (fn [game-num]
                        (when (= 0 (mod game-num 100))
                          (println (format "Completed %d/%d games..." game-num num-games)))
                        (let [game-state (core/rand-game-end-state num-players safety-threshold)]
                         ;; Extract minimal stats and let GC clean up the full game state
                          (analyze-game-outcome game-state)))

        ;; Set up pipeline to process games in parallel with backpressure
         pipeline-result (pipeline parallelism output-ch (map process-game) input-ch)

        ;; Start feeding game numbers to input channel
         feeder (go
                  (doseq [game-num (range num-games)]
                    (>! input-ch game-num))
                  (close! input-ch))

        ;; Collect results from output channel
         collector (go-loop [results []]
                     (if-let [result (<! output-ch)]
                       (recur (conj results result))
                       results))

        ;; Wait for all processing to complete and collect results
         results (time (<!! collector))

         end-time (System/currentTimeMillis)
         duration-ms (- end-time start-time)

        ;; Calculate statistics
         games-with-winner (->> results (filter :has-winner))
         games-without-winner (->> results (remove :has-winner))
         failsafe-games (->> results (filter :hit-failsafe))
         exception-games (->> results (filter :had-exception))

        ;; Exception message statistics
         exception-messages (->> exception-games
                                 (map :exception-message)
                                 (filter some?)
                                 frequencies
                                 (sort-by second >))

        ;; Winner statistics
         winner-stats (->> games-with-winner
                           (group-by :winner-id)
                           (map (fn [[winner-id games]]
                                  [winner-id (count games)]))
                           (sort-by second >))

        ;; Transaction count statistics for winning games
         winning-tx-counts (->> games-with-winner (map :transaction-count))

        ;; Transaction count statistics for failsafe games
         failsafe-tx-counts (->> failsafe-games (map :transaction-count))

        ;; Auction statistics
         games-with-auctions (->> results (filter :has-auctions))
         total-auctions-initiated (apply + (map :auction-initiated-count results))
         total-auctions-completed (apply + (map :auction-completed-count results))
         total-auctions-passed (apply + (map :auction-passed-count results))
         total-purchases (apply + (map :purchase-count results))
         auction-initiated-counts (->> results (map :auction-initiated-count))
         auction-completed-counts (->> results (map :auction-completed-count))
         auction-passed-counts (->> results (map :auction-passed-count))

         ;; Auction reason statistics  
         total-property-declined-auctions (apply + (map :property-declined-auction-count results))
         total-bankruptcy-auctions (apply + (map :bankruptcy-auction-count results))
         games-with-property-declined-auctions (->> results (filter #(> (:property-declined-auction-count %) 0)))
         games-with-bankruptcy-auctions (->> results (filter #(> (:bankruptcy-auction-count %) 0)))
         sample-auction-initiations (->> games-with-auctions
                                         (take 5)
                                         (mapcat :auction-initiated-transactions)
                                         (take 10))
         sample-auction-completions (->> games-with-auctions
                                         (take 5)
                                         (mapcat :auction-completed-transactions)
                                         (take 10))
         sample-auction-passed (->> games-with-auctions
                                    (take 5)
                                    (mapcat :auction-passed-transactions)
                                    (take 10))

         ;; Building scarcity analysis
         games-with-building-shortages (->> results 
                                             (filter #(get-in % [:building-scarcity :had-building-shortages])))
         building-shortage-occurrence-rate (* 100.0 (/ (count games-with-building-shortages) num-games))
         
         total-house-shortage-transactions (apply + (map #(get-in % [:building-scarcity :house-shortage-transactions] 0) results))
         total-hotel-shortage-transactions (apply + (map #(get-in % [:building-scarcity :hotel-shortage-transactions] 0) results))
         total-critical-house-shortages (apply + (map #(get-in % [:building-scarcity :critical-house-shortage-transactions] 0) results))
         total-last-house-purchases (apply + (map #(get-in % [:building-scarcity :last-house-purchases] 0) results))
         total-last-hotel-purchases (apply + (map #(get-in % [:building-scarcity :last-hotel-purchases] 0) results))
         total-shortage-periods (apply + (map #(get-in % [:building-scarcity :total-shortage-periods] 0) results))
         
         avg-shortage-durations (->> results
                                     (map #(get-in % [:building-scarcity :avg-shortage-duration]))
                                     (filter some?))
         max-shortage-durations (->> results
                                     (map #(get-in % [:building-scarcity :max-shortage-duration]))
                                     (filter some?))

        ;; Incomplete game analysis
         incomplete-games (->> games-without-winner
                               (group-by :active-player-count)
                               (map (fn [[player-count games]]
                                      [player-count (count games)]))
                               (sort-by first))

         stats {:total-games num-games
                :duration-seconds (/ duration-ms 1000.0)
                :games-per-second (/ num-games (/ duration-ms 1000.0))

                :games-with-winner (count games-with-winner)
                :games-without-winner (count games-without-winner)
                :winner-percentage (* 100.0 (/ (count games-with-winner) num-games))

                :failsafe-games (count failsafe-games)
                :exception-games (count exception-games)
                :exception-messages exception-messages

                :winner-distribution winner-stats
                :winner-percentages (->> winner-stats
                                         (map (fn [[winner-id count]]
                                                [winner-id (* 100.0 (/ count (count games-with-winner)))])))

                :transaction-stats (when (seq winning-tx-counts)
                                     {:min (apply min winning-tx-counts)
                                      :max (apply max winning-tx-counts)
                                      :avg (double (/ (apply + winning-tx-counts) (count winning-tx-counts)))
                                      :median (nth (sort winning-tx-counts)
                                                   (int (/ (count winning-tx-counts) 2)))})

                :failsafe-transaction-stats (when (seq failsafe-tx-counts)
                                              {:min (apply min failsafe-tx-counts)
                                               :max (apply max failsafe-tx-counts)
                                               :avg (double (/ (apply + failsafe-tx-counts) (count failsafe-tx-counts)))
                                               :median (nth (sort failsafe-tx-counts)
                                                            (int (/ (count failsafe-tx-counts) 2)))})

               ;; Auction statistics
                :games-with-auctions (count games-with-auctions)
                :auction-occurrence-rate (* 100.0 (/ (count games-with-auctions) num-games))
                :total-auctions-initiated total-auctions-initiated
                :total-auctions-completed total-auctions-completed
                :total-auctions-passed total-auctions-passed
                :total-purchases total-purchases
                :avg-auctions-initiated-per-game (double (/ total-auctions-initiated num-games))
                :avg-auctions-completed-per-game (double (/ total-auctions-completed num-games))
                :avg-auctions-passed-per-game (double (/ total-auctions-passed num-games))
                :auction-completion-rate (if (> total-auctions-initiated 0)
                                           (* 100.0 (/ total-auctions-completed total-auctions-initiated))
                                           0.0)
                :auction-passed-rate (if (> total-auctions-initiated 0)
                                       (* 100.0 (/ total-auctions-passed total-auctions-initiated))
                                       0.0)
                :auction-to-purchase-ratio (if (> total-purchases 0)
                                             (double (/ total-auctions-initiated total-purchases))
                                             0.0)

                ;; Auction reason breakdown
                :property-declined-auction-occurrence-rate (* 100.0 (/ (count games-with-property-declined-auctions) num-games))
                :bankruptcy-auction-occurrence-rate (* 100.0 (/ (count games-with-bankruptcy-auctions) num-games))
                :property-declined-to-bankruptcy-auction-ratio (if (> total-bankruptcy-auctions 0)
                                                                 (double (/ total-property-declined-auctions total-bankruptcy-auctions))
                                                                 0.0)
                :total-property-declined-auctions total-property-declined-auctions
                :total-bankruptcy-auctions total-bankruptcy-auctions

                :auction-initiated-stats (when (seq auction-initiated-counts)
                                           {:min (apply min auction-initiated-counts)
                                            :max (apply max auction-initiated-counts)
                                            :avg (double (/ (apply + auction-initiated-counts) (count auction-initiated-counts)))
                                            :median (nth (sort auction-initiated-counts)
                                                         (int (/ (count auction-initiated-counts) 2)))})
                :sample-auction-initiations sample-auction-initiations
                :sample-auction-completions sample-auction-completions
                :sample-auction-passed sample-auction-passed

                ;; Building scarcity statistics
                :games-with-building-shortages (count games-with-building-shortages)
                :building-shortage-occurrence-rate building-shortage-occurrence-rate
                :total-house-shortage-transactions total-house-shortage-transactions
                :total-hotel-shortage-transactions total-hotel-shortage-transactions
                :total-critical-house-shortages total-critical-house-shortages
                :total-last-house-purchases total-last-house-purchases
                :total-last-hotel-purchases total-last-hotel-purchases
                :total-shortage-periods total-shortage-periods
                :avg-shortage-duration-per-game (when (seq avg-shortage-durations)
                                                   (double (/ (apply + avg-shortage-durations)
                                                              (count avg-shortage-durations))))
                :max-shortage-duration-overall (when (seq max-shortage-durations)
                                                 (apply max max-shortage-durations))

                :incomplete-game-breakdown incomplete-games}]

     (println (format "Simulation completed in %.1f seconds" (/ duration-ms 1000.0)))
     stats)))

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
        (-> (run-simulation games players safety)
            print-simulation-results)))))
