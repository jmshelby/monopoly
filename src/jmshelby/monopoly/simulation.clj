(ns jmshelby.monopoly.simulation
  (:require [jmshelby.monopoly.core :as core]
            [jmshelby.monopoly.util :as util]
            [clojure.core.async :as async :refer [>! <! >!! <!! go go-loop chan close! pipeline thread]]))

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

(defn calculate-statistics
  "Calculate comprehensive statistics from analyzed game outcomes"
  [results num-games duration-ms]
  (let [;; Basic game completion stats
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
                              (sort-by first))]

    {:total-games num-games
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

     :incomplete-game-breakdown incomplete-games}))

(defn run-simulation
  "Run a large number of game simulations using core.async pipeline for memory efficiency.
   Returns simulation statistics."
  ([num-games] (run-simulation num-games 4 1500))
  ([num-games num-players safety-threshold]
   (let [start-time (System/currentTimeMillis)
         parallelism  (+ 2 (* 2 (.. Runtime getRuntime availableProcessors)))

         ;; Create channels
         input-ch (async/chan 10)    ; Buffer for game numbers
         output-ch (async/chan 200)   ; Buffer for results

         ;; Function that processes a single game
         process-game (fn [game-num]
                        (let [game-state (core/rand-game-end-state num-players safety-threshold)]
                          ;; Extract minimal stats and let GC clean up the full game state
                          (analyze-game-outcome game-state)))

         ;; Set up pipeline to process games in parallel with backpressure
         pipeline-result (async/pipeline parallelism output-ch (map process-game) input-ch)

         ;; Start feeding game numbers to input channel
         feeder (async/go
                  (doseq [game-num (range num-games)]
                    (async/>! input-ch game-num))
                  (async/close! input-ch))

         ;; Collect results from output channel
         collector (async/go-loop [results []]
                     (if-let [result (async/<! output-ch)]
                       (recur (conj results result))
                       results))

         ;; Wait for all processing to complete and collect results
         results (async/<!! collector)

         end-time (System/currentTimeMillis)
         duration-ms (- end-time start-time)]

     (calculate-statistics results num-games duration-ms))))

