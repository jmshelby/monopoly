(ns jmshelby.farkle.simulation
  (:require [jmshelby.farkle.core :as core]
            [jmshelby.farkle.util :as util]
            #?(:clj [clojure.core.async :as async])))

(defn analyze-game-outcome
  "Analyze a single game result and return outcome statistics"
  [game-state]
  (let [players (:players game-state)
        transactions (:transactions game-state)
        tx-count (count transactions)
        winner (util/winner game-state)

        ;; Farkle analysis
        farkle-txs (->> transactions (filter #(= :farkle (:type %))))
        farkle-count (count farkle-txs)

        ;; Scoring analysis
        bank-txs (->> transactions (filter #(= :bank (:type %))))
        bank-count (count bank-txs)
        bank-failed-txs (->> transactions (filter #(= :bank-failed (:type %))))

        ;; Roll analysis
        roll-txs (->> transactions (filter #(= :roll (:type %))))
        roll-count (count roll-txs)

        ;; Hot dice (all 6 dice scored)
        hot-dice-txs (->> transactions
                          (filter #(= :score-dice (:type %)))
                          (filter :hot-dice))
        hot-dice-count (count hot-dice-txs)]

    {:has-winner (boolean winner)
     :winner-id (when winner (:id winner))
     :winner-score (when winner (:total-score winner))
     :transaction-count tx-count
     :failed-to-complete (= :failsafe (:status game-state))
     :hit-failsafe (boolean (:failsafe-stop game-state))
     :had-exception (boolean (:exception game-state))
     :exception-message (when (:exception game-state)
                          (get-in game-state [:exception :message]))
     :iterations (or (:iterations game-state) 0)
     :farkle-count farkle-count
     :bank-count bank-count
     :bank-failed-count (count bank-failed-txs)
     :roll-count roll-count
     :hot-dice-count hot-dice-count
     :avg-score-per-player (when (seq players)
                             (double (/ (reduce + (map :total-score players))
                                        (count players))))}))

#?(:clj
   (defn run-simulation
     "Run multiple Farkle games in parallel and collect statistics"
     ([num-games]
      (run-simulation num-games 4 5000))
     ([num-games num-players]
      (run-simulation num-games num-players 5000))
     ([num-games num-players safety-threshold]
      (let [start-time (System/currentTimeMillis)
            ;; Create channels for coordination
            game-chan (async/chan num-games)
            result-chan (async/chan num-games)

            ;; Spawn worker processes
            workers (doall
                     (for [_ (range (min 4 num-games))]
                       (async/go-loop []
                         (when-let [game-num (async/<! game-chan)]
                           (let [game-result (core/rand-game-end-state num-players safety-threshold)
                                 analysis (analyze-game-outcome game-result)]
                             (async/>! result-chan analysis))
                           (recur)))))

            ;; Queue up all games
            _ (async/go
                (doseq [i (range num-games)]
                  (async/>! game-chan i))
                (async/close! game-chan))

            ;; Collect results
            results (loop [collected []
                           remaining num-games]
                      (if (zero? remaining)
                        collected
                        (let [result (async/<!! result-chan)]
                          (recur (conj collected result)
                                 (dec remaining)))))]

        (async/close! result-chan)

        ;; Aggregate statistics
        (let [end-time (System/currentTimeMillis)
              duration-ms (- end-time start-time)]
          {:num-games num-games
           :num-players num-players
           :duration-ms duration-ms
           :games-per-second (double (/ num-games (/ duration-ms 1000.0)))
           :completed-games (count (filter :has-winner results))
           :completion-rate (double (/ (count (filter :has-winner results)) num-games))
           :failsafe-games (count (filter :hit-failsafe results))
           :exception-games (count (filter :had-exception results))
           :avg-iterations (double (/ (reduce + (map :iteration results))
                                      (count results)))
           :avg-transactions (double (/ (reduce + (map :transaction-count results))
                                        (count results)))
           :total-farkles (reduce + (map :farkle-count results))
           :avg-farkles-per-game (double (/ (reduce + (map :farkle-count results))
                                            (count results)))
           :total-banks (reduce + (map :bank-count results))
           :total-hot-dice (reduce + (map :hot-dice-count results))
           :avg-winner-score (when-let [winner-scores (seq (keep :winner-score results))]
                               (double (/ (reduce + winner-scores)
                                          (count winner-scores))))
           :exception-details (when-let [exceptions (seq (filter :had-exception results))]
                                (frequencies (map :exception-message exceptions)))
           :results results})))))

#?(:cljs
   (defn run-simulation
     "Run multiple Farkle games sequentially in ClojureScript"
     ([num-games]
      (run-simulation num-games 4 5000))
     ([num-games num-players]
      (run-simulation num-games num-players 5000))
     ([num-games num-players safety-threshold]
      (let [start-time (.now js/Date)
            results (vec (for [_ (range num-games)]
                           (-> (core/rand-game-end-state num-players safety-threshold)
                               analyze-game-outcome)))
            end-time (.now js/Date)
            duration-ms (- end-time start-time)]

        {:num-games num-games
         :num-players num-players
         :duration-ms duration-ms
         :games-per-second (/ num-games (/ duration-ms 1000.0))
         :completed-games (count (filter :has-winner results))
         :completion-rate (/ (count (filter :has-winner results)) num-games)
         :failsafe-games (count (filter :hit-failsafe results))
         :exception-games (count (filter :had-exception results))
         :avg-iterations (/ (reduce + (map :iteration results))
                            (count results))
         :avg-transactions (/ (reduce + (map :transaction-count results))
                              (count results))
         :total-farkles (reduce + (map :farkle-count results))
         :avg-farkles-per-game (/ (reduce + (map :farkle-count results))
                                  (count results))
         :total-banks (reduce + (map :bank-count results))
         :total-hot-dice (reduce + (map :hot-dice-count results))
         :avg-winner-score (when-let [winner-scores (seq (keep :winner-score results))]
                             (/ (reduce + winner-scores)
                                (count winner-scores)))
         :exception-details (when-let [exceptions (seq (filter :had-exception results))]
                              (frequencies (map :exception-message exceptions)))
         :results results}))))

(defn print-simulation-results
  "Print formatted simulation results"
  [results]
  (println "\n========================================")
  (println "Farkle Simulation Results")
  (println "========================================")
  (println (format "Games: %d" (:num-games results)))
  (println (format "Players per game: %d" (:num-players results)))
  (println (format "Duration: %.2f seconds" (/ (:duration-ms results) 1000.0)))
  (println (format "Games/second: %.2f" (:games-per-second results)))
  (println)
  (println "Game Outcomes:")
  (println (format "  Completed: %d (%.1f%%)"
                   (:completed-games results)
                   (* 100 (:completion-rate results))))
  (println (format "  Failsafe triggered: %d" (:failsafe-games results)))
  (println (format "  Exceptions: %d" (:exception-games results)))
  (println)
  (println "Game Statistics:")
  (println (format "  Avg iterations: %.1f" (:avg-iterations results)))
  (println (format "  Avg transactions: %.1f" (:avg-transactions results)))
  (println (format "  Total farkles: %d" (:total-farkles results)))
  (println (format "  Avg farkles/game: %.2f" (:avg-farkles-per-game results)))
  (println (format "  Total banks: %d" (:total-banks results)))
  (println (format "  Total hot dice: %d" (:total-hot-dice results)))
  (when (:avg-winner-score results)
    (println (format "  Avg winner score: %.1f" (:avg-winner-score results))))
  (when (seq (:exception-details results))
    (println)
    (println "Exception Details:")
    (doseq [[msg count] (:exception-details results)]
      (println (format "  %s: %d" msg count))))
  (println "========================================\n"))
