(ns jmshelby.monopoly.simulation.output
  (:refer-clojure :exclude [printf])
  (:require [jmshelby.monopoly.util.format :refer [printf]]
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

