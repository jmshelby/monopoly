(ns jmshelby.monopoly.analysis
  (:require [jmshelby.monopoly.util :as util]))

(defn summarize-game
  "Analyze a game state and transactions to provide a summary of what happened.
  Includes basic statistics, player outcomes, and potential inconsistencies."
  [{:keys [status players transactions current-turn] :as game-state}]
  (let [;; Basic stats
        total-transactions (count transactions)
        tx-by-type (frequencies (map :type transactions))

        ;; Player analysis
        active-players (->> players (filter #(= :playing (:status %))))
        bankrupt-players (->> players (filter #(= :bankrupt (:status %))))

        ;; Financial analysis
        total-cash (->> players (map :cash) (apply +))
        total-properties (->> players (map :properties) (map count) (apply +))

        ;; Transaction analysis
        payments (->> transactions (filter #(= :payment (:type %))))
        bankruptcies (->> transactions (filter #(= :bankruptcy (:type %))))

        ;; Money flow analysis
        bank-payments (->> payments (filter #(or (= :bank (:from %)) (= :bank (:to %)))))
        player-to-bank (->> bank-payments (filter #(= :bank (:to %))) (map :amount) (apply + 0))
        bank-to-player (->> bank-payments (filter #(= :bank (:from %))) (map :amount) (apply + 0))

        ;; Monopoly analysis
        owned-props (util/owned-property-details game-state)
        monopolies-by-player (->> players
                                 (map (fn [player]
                                        [(:id player)
                                         (->> owned-props
                                              vals
                                              (filter #(= (:owner %) (:id player)))
                                              (filter :group-monopoly?)
                                              (map #(get-in % [:def :group-name]))
                                              distinct)]))
                                 (into {}))

        ;; Track monopoly formations through transactions
        ;; Note: This is a simplified approach - for perfect accuracy we'd need
        ;; to replay game state at each transaction to detect monopoly formations
        monopoly-formations (->> (concat
                                  ;; Track trades that might form monopolies
                                  (->> transactions
                                       (filter #(and (= :trade (:type %))
                                                     (= :accept (:status %))))
                                       (map-indexed (fn [idx tx]
                                                      ;; Extract property groups from trade
                                                      (let [asking-props (get-in tx [:asking :properties] #{})
                                                            offering-props (get-in tx [:offering :properties] #{})
                                                            all-trade-props (concat asking-props offering-props)
                                                            ;; Look up groups for traded properties
                                                            prop-groups (->> all-trade-props
                                                                           (map (fn [prop-name]
                                                                                  (->> game-state :board :properties
                                                                                       (filter #(= prop-name (:name %)))
                                                                                       first
                                                                                       :group-name)))
                                                                           (filter identity)
                                                                           distinct)
                                                            group-desc (if (seq prop-groups)
                                                                        (clojure.string/join ", " (map name prop-groups))
                                                                        "properties")]
                                                        {:transaction-number (+ idx 1)
                                                         :type :trade
                                                         :player (:to tx)
                                                         :groups prop-groups
                                                         :description (format "Trade with %s (%s)" (:from tx) group-desc)}))))
                                  ;; Track purchases that might complete monopolies
                                  (->> transactions
                                       (filter #(= :purchase (:type %)))
                                       (map-indexed (fn [idx tx]
                                                      ;; Look up property group from board definition
                                                      (let [prop-def (->> game-state :board :properties
                                                                         (filter #(= (:property tx) (:name %)))
                                                                         first)
                                                            group-name (:group-name prop-def)]
                                                        {:transaction-number (+ idx 1)
                                                         :type :purchase
                                                         :player (:player tx)
                                                         :property (:property tx)
                                                         :group group-name
                                                         :description (format "%s (%s)"
                                                                            (name (:property tx))
                                                                            (name (or group-name :unknown)))})))))
                                 (take 20))

        ;; Consistency checks
        inconsistencies
        (cond-> []
          ;; Check for bankrupt players with cash/properties
          (->> bankrupt-players
               (some #(or (> (:cash %) 0) (seq (:properties %)))))
          (conj "Bankrupt players still have cash or properties")

          ;; Check for negative cash
          (->> players (some #(< (:cash %) 0)))
          (conj "Players with negative cash found")

          ;; Check current turn player status
          (and (= :playing status)
               (->> players
                    (filter #(= (:id %) (:player current-turn)))
                    first
                    :status
                    (not= :playing)))
          (conj "Current turn player is not active")

          ;; Check if game should be complete
          (and (= :playing status) (<= (count active-players) 1))
          (conj "Game should be complete but status is still :playing"))]

    {:summary
     {:game-status status
      :total-turns (count (filter #(= :roll (:type %)) transactions))
      :total-transactions total-transactions
      :players {:total (count players)
                :active (count active-players)
                :bankrupt (count bankrupt-players)}
      :economics {:total-cash total-cash
                  :total-properties total-properties
                  :money-to-bank player-to-bank
                  :money-from-bank bank-to-player
                  :net-bank-flow (- player-to-bank bank-to-player)}}

     :transaction-breakdown tx-by-type

     :player-outcomes
     (->> players
          (map (fn [player]
                 {:id (:id player)
                  :status (:status player)
                  :final-cash (:cash player)
                  :properties-owned (count (:properties player))
                  :monopolies (get monopolies-by-player (:id player) [])
                  :monopoly-count (count (get monopolies-by-player (:id player) []))
                  :net-worth (when (= :playing (:status player))
                               (+ (:cash player)
                                  (util/player-property-sell-worth game-state (:id player))))}))
          (sort-by :status))

     :bankruptcies
     (->> bankruptcies
          (map #(select-keys % [:player :properties :acquisition])))

     :monopolies
     {:total-formed (->> monopolies-by-player vals (map count) (apply + 0))
      :by-player monopolies-by-player
      :formations monopoly-formations}

     :inconsistencies inconsistencies

     :winner
     (when (= :complete status)
       (->> active-players first :id))}))

(defn print-game-summary
  "Print a human-readable summary of the game analysis from summarize-game."
  [summary]
  (let [{:keys [summary transaction-breakdown player-outcomes bankruptcies monopolies inconsistencies winner]} summary
        {:keys [game-status total-turns total-transactions players economics]} summary
        ;; Monopoly earning potential (based on typical Monopoly rent values)
        monopoly-power {"brown" "$"           ; Mediterranean/Baltic - lowest
                        "light-blue" "$"      ; Oriental/Vermont/Connecticut - low
                        "purple" "$$"         ; St. Charles/States/Virginia - medium-low
                        "orange" "$$$"        ; St. James/Tennessee/New York - high traffic
                        "red" "$$$$"          ; Kentucky/Indiana/Illinois - very high
                        "yellow" "$$$"        ; Atlantic/Ventnor/Marvin Gardens - high
                        "green" "$$$$"        ; Pacific/N. Carolina/Pennsylvania - very high
                        "blue" "$$$$$"        ; Park Place/Boardwalk - highest
                        "railroad" "$$"       ; Railroads - steady income
                        "utility" "$"}]       ; Utilities - variable/low

    (println "=== MONOPOLY GAME SUMMARY ===")
    (println)

    ;; Game Overview
    (println "📊 GAME OVERVIEW")
    (printf "   Status: %s\n" (name game-status))
    (when winner
      (printf "   🏆 Winner: Player %s\n" winner))
    (printf "   Total Turns: %d\n" total-turns)
    (printf "   Total Transactions: %d\n" total-transactions)
    (println)

    ;; Player Summary
    (println "👥 PLAYERS")
    (printf "   Total: %d (Active: %d, Bankrupt: %d)\n"
            (:total players) (:active players) (:bankrupt players))
    (println)

    ;; Economics
    (println "💰 ECONOMICS")
    (printf "   Total Cash in Circulation: $%d\n" (:total-cash economics))
    (printf "   Properties Owned: %d\n" (:total-properties economics))
    (printf "   Money Paid to Bank: $%d\n" (:money-to-bank economics))
    (printf "   Money Received from Bank: $%d\n" (:money-from-bank economics))
    (printf "   Net Bank Flow: $%d %s\n"
            (Math/abs (:net-bank-flow economics))
            (if (pos? (:net-bank-flow economics)) "(to bank)" "(from bank)"))
    (println)

    ;; Transaction Breakdown
    (println "📝 TRANSACTION BREAKDOWN")
    (doseq [[tx-type count] (sort-by second > transaction-breakdown)]
      (printf "   %s: %d\n" (name tx-type) count))
    (println)

    ;; Player Outcomes
    (println "🎯 PLAYER OUTCOMES")
    (doseq [player player-outcomes]
      (printf "   Player %s (%s): $%d cash, %d properties"
              (:id player)
              (name (:status player))
              (:final-cash player)
              (:properties-owned player))
      (when (:net-worth player)
        (printf ", $%d net worth" (:net-worth player)))
      (when (seq (:monopolies player))
        (printf ", monopolies: %s"
                (clojure.string/join ", "
                                   (map #(str (name %) " " (get monopoly-power (name %) "$"))
                                        (:monopolies player)))))
      (println))
    (println)

    ;; Monopoly Analysis
    (println "🏠 MONOPOLY ANALYSIS")
    (printf "   Total Monopolies Formed: %d\n" (:total-formed monopolies))
    (if (> (:total-formed monopolies) 0)
      (do
        (println "   Distribution by Player:")
        (doseq [[player-id groups] (:by-player monopolies)]
          (when (seq groups)
            (printf "     Player %s: %s\n" player-id
                    (clojure.string/join ", "
                                       (map #(str (name %) " " (get monopoly-power (name %) "$"))
                                            groups)))))
        (when (seq (:formations monopolies))
          (println "   Monopoly Formation Timeline:")
          (doseq [formation (take 10 (:formations monopolies))]
            (let [display-text (cond
                                ;; Single group (purchase)
                                (:group formation)
                                (let [power-indicator (get monopoly-power (name (:group formation)) "$")]
                                  (format "%s %s %s"
                                          (:description formation)
                                          (name (:group formation))
                                          power-indicator))
                                ;; Multiple groups (trade)
                                (:groups formation)
                                (let [group-indicators (->> (:groups formation)
                                                           (map #(str (name %) " " (get monopoly-power (name %) "$")))
                                                           (clojure.string/join ", "))
                                      base-desc (first (clojure.string/split (:description formation) #" \("))
                                      trade-partner (second (re-find #"Trade with (\w+)" (:description formation)))]
                                  (format "Trade with %s (%s)" trade-partner group-indicators))
                                ;; Fallback
                                :else
                                (:description formation))]
              (printf "     Transaction #%d: Player %s - %s via %s\n"
                      (:transaction-number formation)
                      (:player formation)
                      display-text
                      (name (:type formation)))))))
      (println "   🚫 No monopolies formed - this explains the game stall!"))
    (println)

    ;; Bankruptcies
    (when (seq bankruptcies)
      (println "💸 BANKRUPTCIES")
      (doseq [bankruptcy bankruptcies]
        (printf "   Player %s went bankrupt (%d properties transferred)\n"
                (:player bankruptcy)
                (count (:properties bankruptcy))))
      (println))

    ;; Inconsistencies (if any)
    (when (seq inconsistencies)
      (println "⚠️  INCONSISTENCIES DETECTED")
      (doseq [issue inconsistencies]
        (printf "   • %s\n" issue))
      (println))

    ;; Game Health
    (if (empty? inconsistencies)
      (println "✅ Game state appears consistent")
      (printf "❌ %d consistency issues found\n" (count inconsistencies)))

    (println "=== END SUMMARY ===")))