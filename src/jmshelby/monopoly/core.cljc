(ns jmshelby.monopoly.core
  (:require [clojure.set :as set
             :refer [union subset? difference]]
            [jmshelby.monopoly.util :as util
             :refer [roll-dice append-tx
                     rcompare]]
            [jmshelby.monopoly.cards :as cards]
            [jmshelby.monopoly.trade :as trade]
            [jmshelby.monopoly.player :as player]
            [jmshelby.monopoly.definitions :as defs]
            [jmshelby.monopoly.players.dumb :as dumb-player]
            [jmshelby.monopoly.analysis :as analysis]))

;; Special function to core
(defn- move-to-cell
  "Given a game state, destination cell index, and reason/driver
  for moving... apply effects to move current player from their
  cell to the destination cell. Applies GO allowance if applicable,
  resulting transactions, and destination cell effects/options."
  [{:keys [board players
           functions]
    :as   game-state}
   new-cell-idx driver
   & {:keys [allowance? rent-adjustment]
      :or   {allowance? true
             rent-adjustment identity}}]
  (let [;; Get current player info
        player         (util/current-player game-state)
        player-id      (:id player)
        pidx           (:player-index player)
        player-cash    (:cash player)
        old-cell-idx   (:cell-residency player)
        new-cell       (get-in board [:cells new-cell-idx])
        ;; Check for allowance (from > to)
        allowance      (get-in board [:cells 0 :allowance])
        with-allowance (when (and allowance?
                                  (> old-cell-idx new-cell-idx))
                         (+ player-cash allowance))
        ;; Initial state update, things that have
        ;; to happen before the move effects
        new-state
        (cond-> game-state
          ;; Move player
          ;; TODO - the true here is not great...
          true
          (-> (assoc-in [:players pidx :cell-residency] new-cell-idx)
              (append-tx {:type        :move
                          :driver      driver
                          :player      player-id
                          :before-cell old-cell-idx
                          :after-cell  new-cell-idx}))
          ;; Check if we've passed/landed on go, for allowance payout
          with-allowance
          (-> (assoc-in [:players pidx :cash] with-allowance)
              (append-tx {:type   :payment
                          :from   :bank
                          :to     player-id
                          :amount allowance
                          :reason :allowance})))]
    ;; Apply cell effect(s)
    ;; TODO - yikes, getting messy, impl dispatch by cell type
    (cond
      ;; "Go to Jail" cell landing
      (= :go-to-jail (:type new-cell))
      (util/send-to-jail new-state player-id [:cell :go-to-jail])
      ;; Tax
      (util/tax-owed? new-state)
      (let [tax-owed (util/tax-owed? new-state)]
        ((functions :make-requisite-payment)
         new-state player-id :bank tax-owed
         #(-> %
              ;; Just take from player
              (append-tx {:type   :payment
                          :from   player-id
                          :to     :bank
                          :amount tax-owed
                          :reason :tax}))))
      ;; Rent
      (util/rent-owed? new-state)
      (let [;; Get rent details
            [debtee rent] (util/rent-owed? new-state)
            ;; Call for adjustments, *once*, could have side affects/randomness
            final-rent (rent-adjustment rent)]
        ((functions :make-requisite-payment)
         new-state player-id
         ;; The player owed/debtee (or bank)
         debtee
         ;; The total amount owed
         final-rent
         ;; Follow-up changes, transfer + tx
         (fn [gs] (-> gs
                      ;; Take from current player, give to owner
                      ;; TODO - wait, doesn't the make-requisite-payment fn do this for us?
                      (update-in [:players
                                  ;; Get the player index of owed player
                                  ;; TODO - this could probably be refactored
                                  (->> players
                                       (map-indexed vector)
                                       (filter #(= (:id (second %))
                                                   debtee))
                                       first first)
                                  :cash]
                                 + rent)
                      ;; And transaction
                      (append-tx (merge {:type   :payment
                                         :from   player-id
                                         :to     debtee
                                         :amount final-rent
                                         :reason :rent}
                                        (when (not= rent final-rent)
                                          {:rent/original rent
                                           :rent/adjustment (- final-rent rent)})))))))

;; Card Draw
      (= :card (:type new-cell))
      (cards/apply-card-draw new-state)
      ;; Property option/auction, if we're on a property...
      (and (= :property (:type new-cell))
           ;; ... and it's unowned
           (-> new-state
               util/owned-properties
               (get (:name new-cell))
               not))
      (util/apply-property-option new-state)
      ;; None of the above, just continue
      :else new-state)))

(defn- apply-dice-roll
  "Given a game state and new dice roll, advance game according to
  roll. 3rd consecutive roll goes to jail, otherwise, register dice
  roll + transaction, and invoke actual player move."
  [game-state new-roll]
  (let [move         (-> game-state :functions :move-to-cell)
        ;; Get current player info
        player       (util/current-player game-state)
        player-id    (:id player)
        ;; Save new roll + transaction
        new-state
        (-> game-state
            (update-in [:current-turn :dice-rolls] conj new-roll)
            (append-tx {:type   :roll
                        :player player-id
                        :roll   new-roll}))
        ;; Jail trigger based on dice roll,
        ;; 3rd consecutive dice roll, player goes to jail
        dice-jailed? (and (apply = new-roll)
                          (<= 2 (-> game-state :current-turn :dice-rolls count)))]
    ;; Check for "double dice" roll incarceration
    (if dice-jailed?
      ;; Not a regular move, special jail logic
      (util/send-to-jail new-state player-id [:roll :double 3])
      ;; Invoke actual cell move
      (let [;; Find next board position, looping back around if needed
            old-cell (:cell-residency player)
            new-cell (util/next-cell (:board game-state) (apply + new-roll) old-cell)]
        (move new-state new-cell :dice)))))

(defn advance-board
  "Given game state, advance the board, by
  invoking player logic and applying decisions."
  [{:keys [players current-turn]
    :as   game-state}]
  (let [;; Get current player function
        {player-id :id
         :keys     [cash status function]
         :as       player}
        (util/current-player game-state)]

    (cond

      ;; Check if game is already complete,
      ;; no op, same state.
      (= :complete (:status game-state))
      game-state

      ;; Check if it's time to end the game,
      ;; less than 2 active player left?
      (->> players
           (filter #(= :playing (:status %)))
           count
           (> 2))
      (assoc game-state :status :complete)

      ;; Basic bankrupt logic, before turn..
      ;; If the player is already marked as bankrupt,
      ;; just move to next player (bankruptcy handling should
      ;; have been done when they were marked bankrupt)
      (= :bankrupt status)
      (-> game-state
          ;; Move to next player
          util/apply-end-turn)

      ;; If they have cash, and it's not time to end the train
      ;; proceed with regular player turn
      ;; TODO - yikes, this is getting huge too ...
      :else
      (let [jail-spell (:jail-spell player)
            last-roll  (->> current-turn :dice-rolls last)
            can-roll?  (or (nil? last-roll)
                           (and (vector? last-roll)
                                (apply = last-roll)))
            can-build? (util/can-buy-house? game-state)
            actions    (->> (vector
                              ;; TODO - "Done" should not always be an available action,
                              ;;         - if they haven't rolled yet
                              ;;         - if they rolled a double last
                              ;;         - [others?]
                             :done
                             (if jail-spell
                                ;; Jail actions
                               [;; Attempt double roll
                                (when (nil? last-roll)
                                  :jail/roll)
                                 ;; Pay bail
                                (let [bail (->> game-state :board :cells
                                                (filter #(= :jail (:type %)))
                                                first :bail)]
                                   ;; TODO - Need to restrict this if they just landed in jail
                                  (when (>= cash bail)
                                    :jail/bail))
                                 ;; Bail with "get out of jail free" card
                                (when (util/has-bail-card? player)
                                  :jail/bail-card)]

                                ;; Regular dice rolls
                               (when can-roll? :roll))

                              ;; House building
                             (when can-build? :buy-house)

                              ;; House selling
                             (when (util/can-sell-any-house? game-state player)
                               :sell-house)

                              ;; Property mortgage/unmortgage
                             (when (util/can-mortgage-any-property? game-state player)
                               :mortgage-property)
                             (when (util/can-unmortgage-any-property? game-state player)
                               :unmortgage-property)

                              ;; Trade Proposals
                              ;; TODO - the function here can-propose? doesn't do anything yet,
                              ;;        so we just need to validate after the fact
                             (when (trade/can-propose? game-state player-id)
                               :trade-proposal))

                            flatten
                            (filter identity)
                            set)

            ;; Start right away by invoking players turn
            ;; method, to get next response/decision
            decision (function game-state player-id :take-turn {:actions-available actions})]

        ;; TODO - Detect if player is stuck in loop?
        ;; TODO - Player is taking too long?

        (case (:action decision)
          ;; Player done, end turn, advance to next player
          :done           (util/apply-end-turn game-state)
          ;; Roll Dice
          :roll           (-> game-state
                              ;; Do the roll and move
                              (apply-dice-roll (roll-dice 2)))
          ;; Buy house(s)
          :buy-house      (util/apply-house-purchase
                           game-state
                           (:property-name decision))
          ;; Proposing a trade
          ;; TODO - Call trade/validate-proposal from here first
          ;;        (but then what to do if invalid?)
          :trade-proposal (trade/apply-proposal
                           game-state
                            ;; Convenience, attach :from-player for them
                           (assoc decision :trade/from-player player-id))

          ;; Sell house(s)
          :sell-house (util/apply-house-sale
                       game-state player
                       (:property-name decision))

          ;; Mortgage/unmortgage properties
          :mortgage-property (util/apply-property-mortgage
                              game-state player
                              (:property-name decision))
          :unmortgage-property (util/apply-property-unmortgage
                                game-state player
                                (:property-name decision))

          ;; JAIL
          ;; TODO - looks like any jail action can be routed to this one fn
          :jail/roll      (util/apply-jail-spell game-state (:action decision))
          :jail/bail      (util/apply-jail-spell game-state (:action decision))
          :jail/bail-card (util/apply-jail-spell game-state (:action decision)))))))

;; ===============================

(defn init-game-state
  ;; Very early version of this function,
  ;; just creates n # of simple players
  [player-count]
  (let [;; Define initial player state
        players
        (->> (range 65 (+ player-count 65))
             (map char)
             (map str)
             (map (partial hash-map :id))
             ;; Add starting state values
             (map #(assoc %
                          :function dumb-player/decide
                          :status :playing
                          :cash 1500
                          :cell-residency 0 ;; All starting on "Go"
                          :cards #{}
                          :properties {}))
             vec)]
    ;; Define initial game state
    {:status       :playing
     :players      players
     :current-turn {:player     (-> players first :id)
                    :dice-rolls []}
     ;; Grab and preserve the default board/layout
     :board        defs/board
     ;; Shuffle all cards by deck
     :card-queue   (cards/cards->deck-queues (:cards defs/board))
     :transactions []
     :functions    {:move-to-cell           move-to-cell
                    :apply-dice-roll        apply-dice-roll
                    :make-requisite-payment player/make-requisite-payment}}))

(defn rand-game-state
  "Return a game state, with # of given players, as of the given, nth iteration"
  [players n]
  (->> (init-game-state players)
       (iterate advance-board)
       (take n)
       last))

(defn rand-game-end-state
  "Return a new, random, completed game state, with # of given players.
  If an exception occurs during game simulation, returns the last valid
  game state with exception details added under :exception key."
  ([players] (rand-game-end-state players 2000))
  ([players failsafe-thresh]
   (letfn [(safe-advance [state iteration]
             (try
               (let [next-state (advance-board state)]
                 {:state next-state :exception nil})
               (catch #?(:clj Exception :cljs :default) e
                 {:state state
                  :exception (merge {:message #?(:clj (.getMessage e) :cljs (.-message e))
                                     :type (str (type e))
                                     :stack-trace #?(:clj (mapv str (.getStackTrace e)) :cljs nil)
                                     :iteration iteration
                                     :last-transaction (last (:transactions state))
                                     :current-player (get-in state [:current-turn :player])
                                     :player-cash (->> state :players
                                                       (map #(vector (:id %) (select-keys % [:cash :status])))
                                                       (into {}))}
                                    ;; Include ex-info data if available
                                    #?(:clj (when (instance? clojure.lang.ExceptionInfo e)
                                              {:ex-data (ex-data e)})
                                       :cljs nil))})))]
     (loop [current-state (init-game-state players)
            iteration-count 0]
       (let [{:keys [state exception]} (safe-advance current-state iteration-count)]
         (cond
           ;; Exception occurred
           exception
           (assoc current-state :exception exception)

           ;; Game completed normally
           (= :complete (:status state))
           state

           ;; Failsafe - too many iterations
           (>= iteration-count failsafe-thresh)
           (assoc state :failsafe-stop true)

           ;; Continue game
           :else
           (recur state (inc iteration-count))))))))

(comment

  (->> defs/board
       ;; :properties
       )

  (let [state (rand-game-end-state 4)]
    [(:status state)
     (-> state :transactions count)])

  (def sim (rand-game-end-state 4 2000))

  sim

  (:transactions sim)

  (-> sim
      analysis/summarize-game
      analysis/print-game-summary)

  ;; Print detailed transaction log
  (analysis/print-transaction-log sim)

  ;; Find an auction for one player
  (def sim
    (time
     (loop [idx 0]
       (println "==============================================")
       (let [sim (rand-game-end-state 4 1500)
             has-it? (fn [txs]
                       (->> txs
                            (some (fn [tx]
                                    (and (= :auction-initiated (:type tx))
                                         (= 1 (count (:eligible-bidders tx))))))))]
         (if (has-it? (:transactions sim))
           (do
             (println "Found single player auction in: " idx "games")
             sim)
           (recur (inc idx)))))))

  ;; Find the first bankupt to bank tx
  (def sim
    (time
     (loop [idx 0]
       (println "==============================================")
       (let [sim (rand-game-end-state 4 1500)
             has-it? (fn [txs]
                       (->> txs
                            (some (fn [tx]
                                    (and (= :bankruptcy (:type tx))
                                         (= :bank (:to tx)))))))]
         (if (has-it? (:transactions sim))
           (do
             (println "Found bankrupt to bank in: " idx "games")
             sim)
           (recur (inc idx)))))))

;; Find the first exception
  (def sim
    (time
     (loop [idx 0]
       (println "==============================================")
       (let [sim (rand-game-end-state 4 1500)]
         (if (:exception sim)
           (do
             (println "Found exception in: " idx "games")
             sim)
           (recur (inc idx)))))))

  (let [players    (+ 2 (rand-int 5))
        iterations (+ 20 (rand-int 500))
        state      (rand-game-state players iterations)
        appended   (update state :players
                           (fn [players]
                             (map (fn [player]
                                    (assoc player :prop-sell-worth
                                           (util/player-property-sell-worth state (:id player))))
                                  players)))]
    [players iterations appended])

  (def sim
    (rand-game-end-state 4))

  ;; Cell landings stats
  (->> sim
       ;; :transactions
       ;; (filter #(= :move (:type %)))
       ;; (map (fn [tx]
       ;;        (as-> tx *
       ;;          (get-in sim [:board :cells (:after-cell *)])
       ;;          (assoc * :cell (:after-cell tx)))))
       ;; frequencies
       ;; (sort-by second)
       )

  (init-game-state 4)

  (->> (rand-game-state 4 10)
       ;; :transactions
       ;; (drop 100)
       ;; (filter #(= :payment (:type %)))
       ;; (remove #(= :bank (:from %)))
       )
;;
  )
