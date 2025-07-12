(ns jmshelby.monopoly.simulation
  (:require [jmshelby.monopoly.core :as core]
            [jmshelby.monopoly.util :as util]
            [clojure.pprint :as pprint]
            [clojure.core.async :as async :refer [>! <! >!! <!! go go-loop chan close! pipeline thread]]))

(defn analyze-game-outcome
  "Analyze a single game result and return outcome statistics"
  [game-state]
  (let [active-players (->> game-state :players (filter #(= :playing (:status %))))
        transactions (:transactions game-state)
        tx-count (count transactions)
        winner-id (when (= 1 (count active-players)) (:id (first active-players)))
        
        ;; Auction analysis
        auction-txs (->> transactions (filter #(= :auction (:type %))))
        purchase-txs (->> transactions (filter #(= :purchase (:type %))))]
    {:has-winner (= 1 (count active-players))
     :winner-id winner-id
     :transaction-count tx-count
     :active-player-count (count active-players)
     :failed-to-complete (= :playing (:status game-state))
     :hit-failsafe (boolean (:failsafe-stop game-state))
     :had-exception (boolean (:exception game-state))
     
     ;; Auction statistics
     :auction-count (count auction-txs)
     :purchase-count (count purchase-txs)
     :has-auctions (> (count auction-txs) 0)
     :auction-transactions auction-txs}))

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
        total-auctions (apply + (map :auction-count results))
        total-purchases (apply + (map :purchase-count results))
        auction-counts (->> results (map :auction-count))
        sample-auctions (->> games-with-auctions
                           (take 5)
                           (mapcat :auction-transactions)
                           (take 10))

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
               :total-auctions total-auctions
               :total-purchases total-purchases
               :avg-auctions-per-game (double (/ total-auctions num-games))
               :auction-to-purchase-ratio (if (> total-purchases 0)
                                          (double (/ total-auctions total-purchases))
                                          0.0)
               :auction-stats (when (seq auction-counts)
                               {:min (apply min auction-counts)
                                :max (apply max auction-counts)
                                :avg (double (/ (apply + auction-counts) (count auction-counts)))
                                :median (nth (sort auction-counts)
                                           (int (/ (count auction-counts) 2)))})
               :sample-auctions sample-auctions

               :incomplete-game-breakdown incomplete-games}]

    (println (format "Simulation completed in %.1f seconds" (/ duration-ms 1000.0)))
    stats)))

(defn print-simulation-results
  "Print a human-readable summary of simulation results"
  [stats]
  (let [{:keys [total-games duration-seconds games-per-second
                games-with-winner games-without-winner winner-percentage
                failsafe-games exception-games
                winner-distribution winner-percentages
                transaction-stats failsafe-transaction-stats incomplete-game-breakdown
                games-with-auctions auction-occurrence-rate total-auctions total-purchases
                avg-auctions-per-game auction-to-purchase-ratio auction-stats sample-auctions]} stats]

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
    (printf "   Total Auctions: %d\n" total-auctions)
    (printf "   Total Property Purchases: %d\n" total-purchases)
    (printf "   Average Auctions per Game: %.3f\n" avg-auctions-per-game)
    (printf "   Auction to Purchase Ratio: %.4f\n" auction-to-purchase-ratio)
    (when auction-stats
      (printf "   Auction Count Stats - Min: %d, Max: %d, Avg: %.1f, Median: %d\n"
              (:min auction-stats) (:max auction-stats) (:avg auction-stats) (:median auction-stats)))
    (when (seq sample-auctions)
      (println "   Sample Auctions:")
      (doseq [auction (take 5 sample-auctions)]
        (printf "     %s: Winner %s, Bid $%d, Participants: %s\n"
                (:property auction) (:winner auction) (:winning-bid auction)
                (clojure.string/join ", " (:participants auction)))))
    (when (= 0 total-auctions)
      (println "   🤔 No auctions occurred - all properties likely purchased before cash pressure"))
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

(defn print-usage
  "Print usage information"
  []
  (println "Monopoly Game Simulation")
  (println "Usage: simulation [options]")
  (println "")
  (println "Options:")
  (println "  -g, --games GAMES        Number of games to simulate (default: 100)")
  (println "  -p, --players PLAYERS    Number of players per game (default: 4)")
  (println "  -s, --safety THRESHOLD   Safety threshold for game termination (default: 1500)")
  (println "  -h, --help               Show this help message")
  (println "")
  (println "Examples:")
  (println "  simulation                           # 100 games, 4 players, safety 1500")
  (println "  simulation -g 1000                   # 1000 games with defaults")
  (println "  simulation -g 500 -p 3 -s 2000      # 500 games, 3 players, safety 2000")
  (println "  simulation --games 2000 --players 6  # 2000 games, 6 players")
  (println ""))

(defn parse-int-safely
  "Parse integer safely, returning [success? value-or-error]"
  [s]
  (try
    [true (Integer/parseInt s)]
    (catch NumberFormatException _
      [false (format "Invalid number: %s" s)])))

(defn parse-args
  "Parse command line arguments and return options map"
  [args]
  (loop [args args
         options {:games 100 :players 4 :safety 1500}]
    (if (empty? args)
      options
      (let [arg (first args)
            remaining (rest args)]
        (case arg
          ("-h" "--help")
          (assoc options :help true)
          
          ("-g" "--games")
          (if (empty? remaining)
            (assoc options :error "Missing value for --games")
            (let [value (first remaining)
                  [success? result] (parse-int-safely value)]
              (if success?
                (recur (rest remaining)
                       (assoc options :games result))
                (assoc options :error (format "Invalid number for --games: %s" value)))))
          
          ("-p" "--players")
          (if (empty? remaining)
            (assoc options :error "Missing value for --players")
            (let [value (first remaining)
                  [success? result] (parse-int-safely value)]
              (if success?
                (if (and (>= result 2) (<= result 8))
                  (recur (rest remaining)
                         (assoc options :players result))
                  (assoc options :error (format "Players must be between 2 and 8, got: %d" result)))
                (assoc options :error (format "Invalid number for --players: %s" value)))))
          
          ("-s" "--safety")
          (if (empty? remaining)
            (assoc options :error "Missing value for --safety")
            (let [value (first remaining)
                  [success? result] (parse-int-safely value)]
              (if success?
                (if (> result 0)
                  (recur (rest remaining)
                         (assoc options :safety result))
                  (assoc options :error (format "Safety threshold must be positive, got: %d" result)))
                (assoc options :error (format "Invalid number for --safety: %s" value)))))
          
          ;; Handle positional arguments (for backward compatibility)
          (if (re-matches #"\d+" arg)
            (let [[success? result] (parse-int-safely arg)]
              (if success?
                (recur remaining
                       (assoc options :games result))
                (assoc options :error result)))
            (assoc options :error (format "Unknown option: %s" arg))))))))

(defn -main
  "Run the simulation and print results"
  [& args]
  (let [options (parse-args args)]
    (cond
      (:help options)
      (print-usage)
      
      (:error options)
      (do
        (println (format "Error: %s" (:error options)))
        (println)
        (print-usage)
        (System/exit 1))
      
      :else
      (let [{:keys [games players safety]} options]
        (printf "Configuration: %d games, %d players, safety threshold %d\n" games players safety)
        (println)
        (-> (run-simulation games players safety)
            print-simulation-results)))))
