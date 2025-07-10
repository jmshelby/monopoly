(ns jmshelby.monopoly.simulation
  (:require [jmshelby.monopoly.core :as core]
            [clojure.pprint :as pprint]))

(defn analyze-game-outcome
  "Analyze a single game result and return outcome statistics"
  [game-state]
  (let [active-players (->> game-state :players (filter #(= :playing (:status %))))
        tx-count (count (:transactions game-state))
        winner-id (when (= 1 (count active-players)) (:id (first active-players)))]
    {:has-winner (= 1 (count active-players))
     :winner-id winner-id
     :transaction-count tx-count
     :active-player-count (count active-players)
     :failed-to-complete (= :playing (:status game-state))
     :hit-failsafe (boolean (:failsafe-stop game-state))
     :had-exception (boolean (:exception game-state))}))

(defn run-simulation
  "Run a large number of game simulations and collect statistics"
  [num-games]
  (println (format "Starting simulation of %d games with 4 players each..." num-games))
  (let [start-time (System/currentTimeMillis)
        batch-size 100  ; Process in smaller batches to reduce memory pressure
        
        ;; Run games in batches to control memory usage
        results (time
                  (doall
                    (mapcat (fn [batch-start]
                              (let [batch-end (min (+ batch-start batch-size) num-games)
                                    batch-range (range batch-start batch-end)]
                                (println (format "Processing batch %d-%d..." batch-start (dec batch-end)))
                                ;; Process batch in parallel, then immediately extract just the stats we need
                                (doall
                                  (pmap (fn [game-num]
                                          (let [game-state (doall (core/rand-game-end-state 4 2000))
                                                ;; Extract minimal stats and discard the full game state
                                                stats (analyze-game-outcome game-state)]
                                            ;; Force GC hint after processing each game
                                            (when (= 0 (mod game-num 50))
                                              (System/gc))
                                            stats))
                                        batch-range))))
                            (range 0 num-games batch-size))))
        
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
               
               :incomplete-game-breakdown incomplete-games}]
    
    (println (format "Simulation completed in %.1f seconds" (/ duration-ms 1000.0)))
    ;; Shutdown any remaining threads
    (shutdown-agents)
    stats))

(defn print-simulation-results
  "Print a human-readable summary of simulation results"
  [stats]
  (let [{:keys [total-games duration-seconds games-per-second
                games-with-winner games-without-winner winner-percentage
                failsafe-games exception-games
                winner-distribution winner-percentages
                transaction-stats incomplete-game-breakdown]} stats]
    
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
    
    ;; Incomplete Game Analysis
    (when (seq incomplete-game-breakdown)
      (println "❌ INCOMPLETE GAME BREAKDOWN")
      (doseq [[active-count game-count] incomplete-game-breakdown]
        (let [percentage (* 100.0 (/ game-count total-games))]
          (printf "   %d active players: %d games (%.1f%%)\n" 
                  active-count game-count percentage)))
      (println))
    
    (println "=== END SIMULATION RESULTS ===")))

(defn -main
  "Run the simulation and print results"
  [& args]
  (let [num-games (if (first args) 
                    (Integer/parseInt (first args)) 
                    100)]
    (-> (run-simulation num-games)
        print-simulation-results)))