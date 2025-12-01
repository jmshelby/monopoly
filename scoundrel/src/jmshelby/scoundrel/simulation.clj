(ns jmshelby.scoundrel.simulation
  (:require [jmshelby.scoundrel.core :as core]
            [jmshelby.scoundrel.players.random :as random]
            [jmshelby.scoundrel.players.greedy :as greedy]
            [jmshelby.scoundrel.players.smart :as smart]
            [clojure.core.async :as async]))

;; ============================================================================
;; Game Outcome Analysis
;; ============================================================================

(defn analyze-game-outcome
  "Analyze a single completed game and extract statistics from transactions"
  [game-state player-type]
  (let [transactions (:transactions game-state)
        outcome (:status game-state)
        final-health (:health game-state)
        turns-played (:turns-played game-state)

        ;; Damage analysis
        damage-txs (filter #(= :damage-taken (:type %)) transactions)
        total-damage (reduce + (map :damage damage-txs))
        damage-instances (count damage-txs)

        ;; Combat analysis
        monsters-defeated (count (filter #(= :monster-defeated (:type %)) transactions))

        ;; Weapon analysis
        weapon-txs (filter #(= :weapon-equipped (:type %)) transactions)
        weapons-equipped (count weapon-txs)
        weapon-replacements (count (filter :replaced-weapon weapon-txs))

        ;; Potion analysis
        healed-txs (filter #(= :healed (:type %)) transactions)
        total-healing (reduce + 0 (map :amount healed-txs))
        potions-used (count healed-txs)
        potions-wasted (count (filter #(= :potion-wasted (:type %)) transactions))

        ;; Room analysis
        rooms-skipped (count (filter #(= :room-skipped (:type %)) transactions))]

    {:outcome outcome
     :player-type player-type
     :final-health final-health
     :turns-played turns-played

     ;; Damage stats
     :total-damage total-damage
     :damage-instances damage-instances
     :avg-damage-per-hit (if (pos? damage-instances)
                           (double (/ total-damage damage-instances))
                           0.0)

     ;; Combat stats
     :monsters-defeated monsters-defeated
     :combat-efficiency (if (pos? (+ monsters-defeated damage-instances))
                          (double (/ monsters-defeated
                                     (+ monsters-defeated damage-instances)))
                          0.0)

     ;; Weapon stats
     :weapons-equipped weapons-equipped
     :weapon-replacements weapon-replacements

     ;; Potion stats
     :total-healing total-healing
     :potions-used potions-used
     :potions-wasted potions-wasted
     :potion-efficiency (if (pos? (+ potions-used potions-wasted))
                          (double (/ potions-used
                                     (+ potions-used potions-wasted)))
                          0.0)

     ;; Room stats
     :rooms-skipped rooms-skipped}))

;; ============================================================================
;; Parallel Simulation
;; ============================================================================

(defn run-simulation
  "Run a large number of game simulations using core.async pipeline.
  Returns a channel that yields individual game analysis results."
  ([num-games player-type] (run-simulation num-games player-type 100))
  ([num-games player-type max-turns]
   (let [parallelism (+ 2 (* 2 (.. Runtime getRuntime availableProcessors)))

         ;; Create channels
         input-ch (async/chan)
         output-ch (async/chan)

         ;; Create player based on type
         player (case player-type
                  :random (random/make-random-player)
                  :greedy (greedy/make-greedy-player)
                  :smart (smart/make-smart-player))

         ;; Function that processes a single game
         process-game (fn [_game-num]
                        (let [game-state (core/play-game player)]
                          (analyze-game-outcome game-state player-type)))]

     ;; Set up pipeline to process games in parallel with backpressure
     (async/pipeline parallelism output-ch (map process-game) input-ch)

     ;; Start feeding game numbers to input channel
     (async/go
       (doseq [game-num (range num-games)]
         (async/>! input-ch game-num))
       (async/close! input-ch))

     ;; Return the output channel
     output-ch)))

;; ============================================================================
;; Results Aggregation
;; ============================================================================

(defn aggregate-results
  "Collect results from channel and calculate aggregate statistics"
  [results]
  (let [num-games (count results)
        duration-ms 0 ; Placeholder, can add timing if needed

        ;; Group by outcome
        won-games (filter #(= :won (:outcome %)) results)
        lost-games (filter #(= :lost (:outcome %)) results)
        failed-games (filter #(= :failed (:outcome %)) results)

        ;; Helper functions
        safe-avg (fn [coll]
                   (if (seq coll)
                     (double (/ (reduce + coll) (count coll)))
                     0.0))
        safe-median (fn [coll]
                      (if (seq coll)
                        (let [sorted (sort coll)]
                          (nth sorted (quot (count coll) 2)))
                        0))

        ;; Turn statistics
        turn-counts (map :turns-played results)
        won-turn-counts (map :turns-played won-games)

        ;; Damage statistics
        damage-totals (map :total-damage results)
        won-damage-totals (map :total-damage won-games)

        ;; Combat statistics
        monsters-defeated-counts (map :monsters-defeated results)
        combat-efficiencies (map :combat-efficiency results)

        ;; Weapon statistics
        weapons-equipped-counts (map :weapons-equipped results)
        weapon-replacement-counts (map :weapon-replacements results)

        ;; Potion statistics
        healing-totals (map :total-healing results)
        potions-used-counts (map :potions-used results)
        potions-wasted-counts (map :potions-wasted results)
        potion-efficiencies (map :potion-efficiency results)

        ;; Room statistics
        rooms-skipped-counts (map :rooms-skipped results)]

    {:total-games num-games
     :duration-seconds (/ duration-ms 1000.0)

     ;; Outcome statistics
     :games-won (count won-games)
     :games-lost (count lost-games)
     :games-failed (count failed-games)
     :win-rate (* 100.0 (/ (count won-games) num-games))

     ;; Turn statistics
     :turn-stats {:min (if (seq turn-counts) (apply min turn-counts) 0)
                  :max (if (seq turn-counts) (apply max turn-counts) 0)
                  :avg (safe-avg turn-counts)
                  :median (safe-median turn-counts)}
     :won-turn-stats {:min (if (seq won-turn-counts) (apply min won-turn-counts) 0)
                      :max (if (seq won-turn-counts) (apply max won-turn-counts) 0)
                      :avg (safe-avg won-turn-counts)
                      :median (safe-median won-turn-counts)}

     ;; Damage statistics
     :damage-stats {:min (if (seq damage-totals) (apply min damage-totals) 0)
                    :max (if (seq damage-totals) (apply max damage-totals) 0)
                    :avg (safe-avg damage-totals)
                    :median (safe-median damage-totals)}
     :won-damage-stats {:avg (safe-avg won-damage-totals)}

     ;; Combat statistics
     :monsters-defeated-stats {:avg (safe-avg monsters-defeated-counts)}
     :combat-efficiency-stats {:avg (safe-avg combat-efficiencies)}

     ;; Weapon statistics
     :weapons-equipped-stats {:avg (safe-avg weapons-equipped-counts)}
     :weapon-replacement-stats {:avg (safe-avg weapon-replacement-counts)}

     ;; Potion statistics
     :healing-stats {:avg (safe-avg healing-totals)}
     :potions-used-stats {:avg (safe-avg potions-used-counts)}
     :potions-wasted-stats {:avg (safe-avg potions-wasted-counts)}
     :potion-efficiency-stats {:avg (safe-avg potion-efficiencies)}

     ;; Room statistics
     :rooms-skipped-stats {:avg (safe-avg rooms-skipped-counts)}}))

;; ============================================================================
;; Results Display
;; ============================================================================

(defn print-simulation-results
  "Print human-readable simulation results"
  [stats player-type]
  (println "\n=== Scoundrel Simulation Results ===")
  (println (format "Player Type: %s" (name player-type)))
  (println (format "Total Games: %d" (:total-games stats)))
  (println)

  (println "=== Game Outcomes ===")
  (println (format "Games Won: %d (%.1f%%)"
                   (:games-won stats)
                   (:win-rate stats)))
  (println (format "Games Lost: %d (%.1f%%)"
                   (:games-lost stats)
                   (* 100.0 (/ (:games-lost stats) (:total-games stats)))))
  (when (pos? (:games-failed stats))
    (println (format "Games Failed: %d (%.1f%%)"
                     (:games-failed stats)
                     (* 100.0 (/ (:games-failed stats) (:total-games stats))))))
  (println)

  (println "=== Turn Statistics ===")
  (let [ts (:turn-stats stats)]
    (println (format "All Games: min=%d, max=%d, avg=%.1f, median=%d"
                     (:min ts) (:max ts) (:avg ts) (:median ts))))
  (let [wts (:won-turn-stats stats)]
    (println (format "Won Games: min=%d, max=%d, avg=%.1f, median=%d"
                     (:min wts) (:max wts) (:avg wts) (:median wts))))
  (println)

  (println "=== Damage Statistics ===")
  (let [ds (:damage-stats stats)]
    (println (format "All Games: min=%d, max=%d, avg=%.1f, median=%d"
                     (:min ds) (:max ds) (:avg ds) (:median ds))))
  (println (format "Won Games: avg=%.1f" (get-in stats [:won-damage-stats :avg])))
  (println)

  (println "=== Combat Statistics ===")
  (println (format "Avg Monsters Defeated: %.1f" (get-in stats [:monsters-defeated-stats :avg])))
  (println (format "Avg Combat Efficiency: %.1f%%" (* 100.0 (get-in stats [:combat-efficiency-stats :avg]))))
  (println)

  (println "=== Weapon Statistics ===")
  (println (format "Avg Weapons Equipped: %.1f" (get-in stats [:weapons-equipped-stats :avg])))
  (println (format "Avg Weapon Replacements: %.1f" (get-in stats [:weapon-replacement-stats :avg])))
  (println)

  (println "=== Potion Statistics ===")
  (println (format "Avg Total Healing: %.1f" (get-in stats [:healing-stats :avg])))
  (println (format "Avg Potions Used: %.1f" (get-in stats [:potions-used-stats :avg])))
  (println (format "Avg Potions Wasted: %.1f" (get-in stats [:potions-wasted-stats :avg])))
  (println (format "Avg Potion Efficiency: %.1f%%" (* 100.0 (get-in stats [:potion-efficiency-stats :avg]))))
  (println)

  (println "=== Room Statistics ===")
  (println (format "Avg Rooms Skipped: %.1f" (get-in stats [:rooms-skipped-stats :avg])))
  (println))
