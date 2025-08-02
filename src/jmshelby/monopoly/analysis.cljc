(ns jmshelby.monopoly.analysis
  (:require [jmshelby.monopoly.util :as util]
            [clojure.set :as set]
            #?(:clj [clojure.string :as string]
               :cljs [clojure.string :as string])))

(defn printf 
  "Cross-platform printf that works in both Clojure and ClojureScript"
  [fmt & args]
  #?(:clj (apply clojure.core/printf fmt args)
     :cljs (print (apply str args))))

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

        ;; Auction analysis
        auction-initiated (->> transactions (filter #(= :auction-initiated (:type %))))
        auction-completed (->> transactions (filter #(= :auction-completed (:type %))))
        auction-passed (->> transactions (filter #(= :auction-passed (:type %))))

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

     :auctions
     {:total-initiated (count auction-initiated)
      :total-completed (count auction-completed)
      :total-passed (count auction-passed)
      :completion-rate (if (> (count auction-initiated) 0)
                         (* 100.0 (/ (count auction-completed) (count auction-initiated)))
                         0.0)
      :passed-rate (if (> (count auction-initiated) 0)
                     (* 100.0 (/ (count auction-passed) (count auction-initiated)))
                     0.0)
      :initiated-transactions auction-initiated
      :completed-transactions auction-completed
      :passed-transactions auction-passed}

     :inconsistencies inconsistencies

     :winner
     (when (= :complete status)
       (->> active-players first :id))}))

(defn print-transaction-log
  "Print a human-readable transaction log with intelligent grouping of related transactions.
  Combines sequential transactions when they make logical sense (house purchases, multiple
  rolls, etc.) and includes transaction sequence numbers for easy reference."
  [{:keys [transactions board players] :as game-state}]
  (let [;; Helper functions for transaction formatting
        format-money (fn [amount] (format "$%d" amount))
        format-property (fn [prop-name]
                          (if prop-name
                            (clojure.string/replace (name prop-name) #"-" " ")
                            "unknown"))

        ;; Look up property group by name
        get-property-group (fn [prop-name]
                             (when prop-name
                               (->> board :properties
                                    (filter #(= prop-name (:name %)))
                                    first
                                    :group-name)))

        ;; Look up cell name by index
        get-cell-name (fn [cell-index]
                        (let [cell (get-in board [:cells cell-index])]
                          (case (:type cell)
                            :go "GO"
                            :jail "Jail"
                            :free "Free Parking"
                            :go-to-jail "Go to Jail"
                            :tax (str "Tax ($" (:cost cell) ")")
                            :card (str (clojure.string/replace (name (:name cell)) #"-" " "))
                            :property (format-property (:name cell))
                            (str "Cell " cell-index))))

        ;; Group transactions by type and sequence for intelligent combining
        group-transactions (fn [txs]
                             (loop [remaining txs
                                    grouped []
                                    current-group nil
                                    transaction-idx 0]
                               (if (empty? remaining)
                                ;; Add final group if exists
                                 (if current-group
                                   (conj grouped current-group)
                                   grouped)
                                 (let [tx (first remaining)
                                       tx-type (:type tx)
                                       tx-player (:player tx)

                                      ;; Check if this transaction can be grouped with current group
                                       can-group? (and current-group
                                                       (= tx-type (:type (first (:transactions current-group))))
                                                       (= tx-player (:player (first (:transactions current-group))))
                                                       (or
                                                      ;; Group house purchases for same player
                                                        (= tx-type :purchase-house)
                                                      ;; Group house sales for same player
                                                        (= tx-type :sell-house)
                                                      ;; Group consecutive payments of same type
                                                        (and (= tx-type :payment)
                                                             (= (:reason tx) (:reason (first (:transactions current-group)))))
                                                      ;; Group mortgage/unmortgage actions
                                                        (#{:mortgage-property :unmortgage-property} tx-type)))

                                      ;; Special case: combine roll + move transactions
                                       roll-move-combo? (and (= tx-type :move)
                                                             current-group
                                                             (= :roll (:type (first (:transactions current-group))))
                                                             (= tx-player (:player (first (:transactions current-group)))))]

                                   (if (or can-group? roll-move-combo?)
                                    ;; Add to current group
                                     (recur (rest remaining)
                                            grouped
                                            (update current-group :transactions conj tx)
                                            (inc transaction-idx))
                                    ;; Start new group
                                     (recur (rest remaining)
                                            (if current-group
                                              (conj grouped current-group)
                                              grouped)
                                            {:transactions [tx]
                                             :start-idx transaction-idx
                                             :type tx-type
                                             :player tx-player}
                                            (inc transaction-idx)))))))

        ;; Calculate total transactions to determine padding width
        total-txs (count transactions)
        pad-width (count (str total-txs))

        ;; Create ownership tracking atom and property group mapping
        ownership-tracker (atom {}) ; {player-id #{property-names}}
        existing-monopolies (atom #{}) ; #{[player-id group-name]}
        group-counts (->> board :properties
                          (filter #(= :street (:type %)))
                          (group-by :group-name)
                          (map (fn [[group-name props]]
                                 [group-name (set (map :name props))]))
                          (into {}))

        ;; Helper function to update ownership and detect NEW monopoly formation
        update-ownership-and-check-monopoly (fn [player-id property-name]
                                              (when property-name
                                                (let [prop-def (->> board :properties
                                                                    (filter #(= property-name (:name %)))
                                                                    first)
                                                      group-name (:group-name prop-def)]
                                                  (when (= :street (:type prop-def))
                                                    ;; Check if this player already had monopoly before this purchase
                                                    (let [had-monopoly? (@existing-monopolies [player-id group-name])]
                                                      ;; Update ownership
                                                      (swap! ownership-tracker update player-id (fnil conj #{}) property-name)
                                                      ;; Check for monopoly after update
                                                      (let [player-props (get @ownership-tracker player-id #{})
                                                            group-props (get group-counts group-name #{})]
                                                        (when (and (seq group-props)
                                                                   (set/subset? group-props player-props)
                                                                   (not had-monopoly?)) ; Only return if it's a NEW monopoly
                                                          ;; Mark this monopoly as existing for future checks
                                                          (swap! existing-monopolies conj [player-id group-name])
                                                          group-name)))))))

        ;; Helper function to check for NEW monopolies after property transfers
        check-monopolies-for-player (fn [player-id]
                                      (let [player-props (get @ownership-tracker player-id #{})]
                                        (->> group-counts
                                             (filter (fn [[group-name group-props]]
                                                       (and (set/subset? group-props player-props)
                                                            (not (@existing-monopolies [player-id group-name])))))
                                             (map first)
                                             (map (fn [group-name]
                                                  ;; Mark as existing for future checks
                                                    (swap! existing-monopolies conj [player-id group-name])
                                                    group-name)))))

        ;; Helper function to process property transfers from trades/bankruptcy
        transfer-properties (fn [from-player to-player property-names]
                              (when (seq property-names)
                                (swap! ownership-tracker update from-player (fnil set/difference #{}) (set property-names))
                                (swap! ownership-tracker update to-player (fnil set/union #{}) (set property-names))))

        ;; Helper function to add event indicators
        add-event-indicator (fn [text transaction-type first-tx]
                              (case transaction-type
                                :payment
                                (if (= :rent (:reason first-tx))
                                  (str "*" text "*")
                                  text)

                                :bankruptcy
                                (do
                                 ;; Handle property transfers from bankruptcy
                                  (let [from-player (:player first-tx)
                                        to-player (:to first-tx)
                                        properties (keys (:properties first-tx))]
                                    (when (not= :bank to-player)
                                      (transfer-properties from-player to-player properties)))
                                  (str "!!!" text "!!!"))

                                :purchase
                                (let [monopoly-group (update-ownership-and-check-monopoly (:player first-tx) (:property first-tx))]
                                  (if monopoly-group
                                    (str "*" text " - MONOPOLY FORMED (" (name monopoly-group) ")*")
                                    text))

                                :trade
                                (if (= :accept (:status first-tx))
                                 ;; Handle property transfers from trades and check for monopolies
                                  (let [asking-props (get-in first-tx [:asking :properties] #{})
                                        offering-props (get-in first-tx [:offering :properties] #{})
                                        from-player (:from first-tx)
                                        to-player (:to first-tx)]
                                   ;; Transfer properties
                                    (transfer-properties from-player to-player offering-props)
                                    (transfer-properties to-player from-player asking-props)
                                   ;; Check for new monopolies for both players
                                    (let [from-monopolies (check-monopolies-for-player from-player)
                                          to-monopolies (check-monopolies-for-player to-player)]
                                      (if (or (seq from-monopolies) (seq to-monopolies))
                                        (let [monopoly-parts (concat
                                                              (when (seq from-monopolies)
                                                                [(str from-player " gets " (clojure.string/join ", " (map name from-monopolies)))])
                                                              (when (seq to-monopolies)
                                                                [(str to-player " gets " (clojure.string/join ", " (map name to-monopolies)))]))]
                                          (str "*" text " - MONOPOLY FORMED ("
                                               (clojure.string/join "; " monopoly-parts) ")*"))
                                        text)))
                                  text)))

        ;; Format grouped transactions into readable strings
        format-group (fn [{:keys [transactions start-idx type player]}]
                       (let [tx-count (count transactions)
                             first-tx (first transactions)
                             last-tx (last transactions)
                             start-num (inc start-idx)
                            ;; Format start number with proper padding
                             tx-num (format (str "%" pad-width "d") start-num)]

                         (case type
                           :roll
                           (if (and (> tx-count 1) (= :move (:type (second transactions))))
                            ;; Combined roll + move
                             (let [roll-tx (first transactions)
                                   move-tx (second transactions)
                                   roll-result (:roll roll-tx)
                                   roll-total (apply + roll-result)
                                   to-cell (:after-cell move-tx)
                                   driver (:driver move-tx)
                                   is-double? (apply = roll-result)
                                   base-text (format "[%s] %s rolls %d and moves to %s"
                                                     tx-num player roll-total (get-cell-name to-cell))]
                               (if is-double?
                                 (str base-text " (rolled double)")
                                 base-text))
                            ;; Just a roll (shouldn't happen in normal flow but handle it)
                             (let [roll-result (:roll first-tx)
                                   is-double? (apply = roll-result)
                                   base-text (format "[%s] %s rolls %s (total: %d)"
                                                     tx-num player roll-result (apply + roll-result))]
                               (if is-double?
                                 (str base-text " (rolled double)")
                                 base-text)))

                           :move
                           (let [from-cell (:before-cell first-tx)
                                 to-cell (:after-cell first-tx)
                                 driver (:driver first-tx)]
                             (format "[%s] %s moves from %s to %s"
                                     tx-num player (get-cell-name from-cell) (get-cell-name to-cell)))

                           :purchase
                           (let [property (:property first-tx)
                                 price (:price first-tx)
                                 base-text (format "%s purchases %s for %s"
                                                   player (format-property property) (format-money price))]
                             (format "[%s] %s"
                                     tx-num (add-event-indicator base-text :purchase first-tx)))

                           :purchase-house
                           (if (= tx-count 1)
                             (let [property (:property first-tx)
                                   price (:price first-tx)]
                               (format "[%s] %s builds house on %s for %s"
                                       tx-num player (format-property property) (format-money price)))
                             (let [properties (map :property transactions)
                                   groups (group-by get-property-group properties)
                                   total-cost (apply + (map :price transactions))
                                   group-summary (clojure.string/join ", "
                                                                      (map (fn [[group props]]
                                                                             (format "%s (%d houses)"
                                                                                     (name (or group :unknown))
                                                                                     (count props)))
                                                                           groups))]
                               (format "[%s] %s builds %d houses on %s for %s"
                                       tx-num player tx-count group-summary (format-money total-cost))))

                           :sell-house
                           (if (= tx-count 1)
                             (let [property (:property first-tx)
                                   proceeds (:proceeds first-tx)]
                               (format "[%s] %s sells house on %s for %s"
                                       tx-num player (format-property property) (format-money proceeds)))
                             (let [properties (map :property transactions)
                                   groups (group-by get-property-group properties)
                                   total-proceeds (apply + (map :proceeds transactions))
                                   group-summary (clojure.string/join ", "
                                                                      (map (fn [[group props]]
                                                                             (format "%s (%d houses)"
                                                                                     (name (or group :unknown))
                                                                                     (count props)))
                                                                           groups))]
                               (format "[%s] %s sells %d houses on %s for %s"
                                       tx-num player tx-count group-summary (format-money total-proceeds))))

                           :payment
                           (let [from (:from first-tx)
                                 to (:to first-tx)
                                 reason (:reason first-tx)
                                 amount (:amount first-tx)
                                 base-text (cond
                                             (= reason :rent)
                                             (format "%s pays %s rent to %s"
                                                     from (format-money amount) to)

                                             (= reason :tax)
                                             (format "%s pays %s tax to bank"
                                                     from (format-money amount))

                                             (= reason :allowance)
                                             (format "%s collects %s passing GO"
                                                     to (format-money amount))

                                             :else
                                             (format "%s pays %s to %s (%s)"
                                                     from (format-money amount) to (name reason)))]
                             (format "[%s] %s"
                                     tx-num (add-event-indicator base-text :payment first-tx)))

                           :mortgage-property
                           (if (= tx-count 1)
                             (let [property (:property first-tx)
                                   proceeds (:proceeds first-tx)]
                               (format "[%s] %s mortgages %s for %s"
                                       tx-num player (format-property property) (format-money proceeds)))
                             (let [properties (map :property transactions)
                                   total-proceeds (apply + (map :proceeds transactions))]
                               (format "[%s] %s mortgages %d properties (%s) for %s"
                                       tx-num player tx-count
                                       (clojure.string/join ", " (map format-property properties))
                                       (format-money total-proceeds))))

                           :bail
                           (let [means (:means first-tx)]
                             (case (first means)
                               :roll (format "[%s] %s gets out of jail with double roll %s"
                                             tx-num player (second means))
                               :cash (format "[%s] %s pays %s bail to get out of jail"
                                             tx-num player (format-money (second means)))
                               :card (format "[%s] %s uses Get Out of Jail Free card"
                                             tx-num player)
                               (format "[%s] %s gets out of jail (%s)"
                                       tx-num player means)))

                           :bankruptcy
                           (let [to (:to first-tx)
                                 cash (:cash first-tx)
                                 properties (:properties first-tx)
                                 base-text (if (= :bank to)
                                             (format "%s goes bankrupt to bank (%s cash, %d properties)"
                                                     player (format-money cash) (count properties))
                                             (format "%s goes bankrupt to %s (%s cash, %d properties transferred)"
                                                     player to (format-money cash) (count properties)))]
                             (format "[%s] %s"
                                     tx-num (add-event-indicator base-text :bankruptcy first-tx)))

                           :trade
                           (let [status (:status first-tx)
                                 from (:from first-tx)
                                 to (:to first-tx)
                                 asking (:asking first-tx)
                                 offering (:offering first-tx)
                                 base-text (case status
                                             :proposal (format "%s proposes trade to %s"
                                                               from to)
                                             :accept (format "%s accepts trade from %s"
                                                             to from)
                                             :decline (format "%s declines trade from %s"
                                                              to from)
                                             (format "Trade between %s and %s (%s)"
                                                     from to (name status)))]
                            ;; Process trade for monopoly detection and add indicator
                             (format "[%s] %s" tx-num (add-event-indicator base-text :trade first-tx)))

                           :card-draw
                           (let [card (:card first-tx)
                                 card-text (if (vector? (:text card))
                                             (clojure.string/join " " (:text card))
                                             (:text card))]
                             (format "[%s] %s draws card: \"%s\""
                                     tx-num player card-text))

                           :auction-initiated
                           (let [property (:property first-tx)
                                 declined-by (:declined-by first-tx)
                                 bankrupted-by (:bankrupted-by first-tx)
                                 reason (:reason first-tx)
                                 starting-bid (:starting-bid first-tx)
                                 participant-count (:participant-count first-tx)
                                 cause-text (cond
                                              declined-by (format "declined by %s" declined-by)
                                              bankrupted-by (format "bankruptcy of %s" bankrupted-by)
                                              :else "unknown cause")]
                             (format "[%s] Auction started for %s (%s, starting bid: %s, %d participants)"
                                     tx-num (format-property property) cause-text
                                     (format-money starting-bid) participant-count))

                           :auction-completed
                           (let [property (:property first-tx)
                                 winner (:winner first-tx)
                                 winning-bid (:winning-bid first-tx)
                                 participants (:participants first-tx)]
                             (format "[%s] Auction completed: %s wins %s for %s (participants: %s)"
                                     tx-num winner (format-property property)
                                     (format-money winning-bid)
                                     (clojure.string/join ", " participants)))

                           :auction-passed
                           (let [property (:property first-tx)
                                 participants (:participants first-tx)]
                             (format "[%s] Auction passed: No bids for %s (participants: %s)"
                                     tx-num (format-property property)
                                     (clojure.string/join ", " participants)))

                          ;; Default format for unknown transaction types
                           (format "[%s] %s: %s (%s)"
                                   tx-num (or player "system") (name type) first-tx))))

        ;; Process all transactions
        grouped-txs (group-transactions transactions)]

    (println "=== TRANSACTION LOG ===")
    (printf "Total transactions: %d\n" (count transactions))
    (println)

    (doseq [group grouped-txs]
      (println (format-group group)))

    (println)

    ;; Final game outcome summary
    (let [active-players (->> players (filter #(= :playing (:status %))))
          bankrupt-players (->> players (filter #(= :bankrupt (:status %))))]
      (cond
        ;; Single winner
        (= 1 (count active-players))
        (let [winner (first active-players)
              net-worth (+ (:cash winner)
                           (util/player-property-sell-worth game-state (:id winner)))]
          (println (format "🏆 WINNER: Player %s with $%d cash ($%d net worth, %d properties)"
                           (:id winner)
                           (:cash winner)
                           net-worth
                           (count (:properties winner)))))

        ;; Multiple players still active
        (> (count active-players) 1)
        (do
          (println (format "🎮 GAME INCOMPLETE: %d players remaining" (count active-players)))
          (doseq [player active-players]
            (let [net-worth (+ (:cash player)
                               (util/player-property-sell-worth game-state (:id player)))]
              (println (format "   Player %s: $%d cash ($%d net worth, %d properties)"
                               (:id player)
                               (:cash player)
                               net-worth
                               (count (:properties player)))))))

        ;; No active players (shouldn't happen but handle it)
        :else
        (println "💥 NO ACTIVE PLAYERS - Game ended in mutual destruction!")))

    (println)
    (println "=== END TRANSACTION LOG ===")))

(defn print-game-summary
  "Print a human-readable summary of the game analysis from summarize-game."
  [summary]
  (let [{summary-data :summary :keys [transaction-breakdown player-outcomes bankruptcies monopolies inconsistencies winner]} summary
        {:keys [game-status total-turns total-transactions players economics]} summary-data
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

    ;; Auction Analysis
    (let [{:keys [total-initiated total-completed total-passed completion-rate passed-rate]} (:auctions summary)]
      (when (> total-initiated 0)
        (println "🏛️ AUCTION ANALYSIS")
        (printf "   Total Auctions Initiated: %d\n" total-initiated)
        (printf "   Auctions Completed: %d (%.1f%%)\n" total-completed completion-rate)
        (printf "   Auctions Passed: %d (%.1f%%)\n" total-passed passed-rate)
        (when (> total-completed 0)
          (let [completed-txs (:completed-transactions (:auctions summary))]
            (printf "   Average Winning Bid: $%d\n"
                    (int (/ (apply + (map :winning-bid completed-txs)) total-completed)))))
        (when (> total-passed 0)
          (let [passed-txs (:passed-transactions (:auctions summary))
                sample-passed (take 3 passed-txs)]
            (println "   Sample Passed Auctions:")
            (doseq [auction sample-passed]
              (printf "     %s (participants: %s)\n"
                      (clojure.string/replace (name (:property auction)) #"-" " ")
                      (clojure.string/join ", " (:participants auction))))))
        (println)))

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