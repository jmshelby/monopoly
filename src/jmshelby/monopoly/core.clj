(ns jmshelby.monopoly.core
  (:require [clojure.set :as set]
            [jmshelby.monopoly.util :as util
             :refer [roll-dice dissoc-in append-tx]]
            [jmshelby.monopoly.cards :as cards]
            [jmshelby.monopoly.player :as player]
            [jmshelby.monopoly.definitions :as defs]))

;; TODO - need to determine where and how many "seeds" to store

;; Game state, schema
(def example-state
  {;; Static board definition for the game
   :board "[See definitions NS]"

   ;; Game Status - playing | complete
   :status :playing

   ;; The list of players, in their game play order,
   ;; and their current state in the game.
   ;; When a game starts, players will be randomly sorted
   :players [{;; Probably some auto-generated one
              :id             "some-uuid"
              ;; Status, playing/bankrupt
              :status         :playing
              ;; Current amount of money on hand
              :cash           1
              ;; Special card collection, current set
              :cards          [:get-out-of-jail-free]
              ;; Which cell on the board are they currently in
              :cell-residency 0
              ;; If on jail cell (haha), and incarcerated,
              ;; track stats on stay
              :jail-spell     {:cause      "[polymorphic] How did they end up in jail"
                               ;; While in jail, the dice roll attempts
                               ;; made to get a double, one for each
                               ;; turn only 3 max are allowed
                               :dice-rolls []}
              ;; The current set of owned "properties", and current state
              :properties     {:park-place {:status      :paid-OR-mortgaged
                                            :house-count 0}}}]

   ;; Separately, track what is going on with the current "turn".
   ;; At any given type there is always a single player who's turn it is,
   ;; but other things can be happening at the same time.
   :current-turn {:player     "player uuid"
                  ;; All the dice rolls from the current turn player,
                  ;; multiple because doubles get another roll
                  :dice-rolls []
                  ;; Some sort of state around the comm status with the player
                  ;; TODO - need to figure out what this looks like
                  ;; TODO - it could be a :phase thing, but maybe this tracks forced negotiation state/status
                  :status     :?}

   ;; The current *ordered* care queue to pull from.
   ;; At the beginning of the game these will be loaded at random,
   ;; when one queue is exhausted, it is randomly filled again.
   ;; TODO - Just the keyword name? Or is a map needed?
   :card-queue {:chance          []
                :community-chest []}

   ;; A list of all game move history, and it's details.
   ;; This is probably more of an enhanced feature...
   ;; Thoughts:
   ;;  - This is a lot like datomic...
   ;;  - Each item in this list could be every unique game state
   :transactions []})

(defn- next-cell
  "Given a game-state, dice sum, and current cell idx, return
  the next cell idx after moving that number of cells"
  [game-state n idx]
  ;; Modulo after adding dice sum to current spot
  (mod (+ n idx)
       (-> game-state :board :cells count)))

(defn tax-owed?
  "Given a game-state, when a tax is owed by the
  current player, return the amount. Returns nil
  if no tax is due."
  [{:keys [board]
    :as   game-state}]
  (let [{:keys [cell-residency]}
        (util/current-player game-state)
        ;; Get the definition of the current cell *if* it's a property
        current-cell (get-in board [:cells cell-residency])]
    ;; Only return if tax is actually owed
    (when (= :tax (:type current-cell))
      (:cost current-cell))))

(defn- simple-bankupt-player
  "Apply simple bankruptcy logic to player in game state.
  Remove houses from properties, remove properties, making
  them available back to bank and to other players.
  Finally mark player status as bankrupt."
  [{:keys [players]
    :as   game-state}
   player-id]
  (let [player
        (->> players
             (map-indexed (fn [idx p] (assoc p :player-index idx)))
             (filter #(= (:id %) player-id))
             first)
        pidx (:player-index player)]
    (-> game-state
        ;; Remove all properties, this is like giving back
        ;; to the bank, freeing up for others to buy
        (assoc-in [:players pidx :properties] {})
        ;; Final marker
        (assoc-in [:players pidx :status] :bankrupt)
        ;; Transaction, just a single one for this basic->bank style
        (append-tx {:type       :bankruptcy
                    :player     player-id
                    :properties (:properties player)
                    :acquistion [:basic :bank]}))))

(defn apply-house-purchase
  "Given a game state and property, apply purchase of a single house
  for current player on given property. Validates and throws if house
  purchase is not allowed by game rules."
  [game-state property-name]
  (let [{player-id :id
         pidx      :player-index}
        (util/current-player game-state)
        ;; Get property definition
        property (->> game-state :board :properties
                      (filter #(= :street (:type %)))
                      (filter #(= property-name (:name %)))
                      first)]

    ;; Validation
    ;; TODO - Should this be done by the caller?
    (when-not (util/can-buy-house? game-state property-name)
      (throw (ex-info "Player decision not allowed"
                      {:action   :buy-house
                       :player   player-id
                       :property property-name
                       ;; TODO - could be: prop not purchased; no
                       ;;        monopoly; house even distribution
                       ;;        violation; cash; etc ...
                       :reason   :unspecified})))

    ;; Apply the purchase
    (-> game-state
        ;; Inc house count in player's owned collection
        (update-in [:players pidx :properties
                    property-name :house-count]
                   inc)
        ;; Subtract money
        (update-in [:players pidx :cash]
                   - (:house-price property))
        ;; Track transaction
        (append-tx {:type     :purchase-house
                    :player   player-id
                    :property property-name
                    :price    (:house-price property)}))))

(defn apply-property-option
  "Given a game state, check if current player is able to buy the property
  they are currently on, if so, invoke player decision logic to determine
  if they want to buy the property. Apply game state changes for either a
  property purchase, or the result of an invoked auction workflow."
  [{:keys [board]
    :as   game-state}]
  (let [;; Get player details
        {:keys [cash function
                player-index
                cell-residency]
         :as   player} (util/current-player game-state)
        current-cell   (get-in board [:cells cell-residency])
        ;; Get the definition of the current cell *if* it's a property
        property       (and (-> current-cell :type (= :property))
                            (->> board :properties
                                 (filter #(= (:name %) (:name current-cell)))
                                 first))
        taken          (util/owned-properties game-state)]
    ;; Either process initial property purchase, or auction off
    (if (and
          ;; We're on an actual property
          property
          ;; It's unowned
          (not (taken (:name property)))
          ;; Player has enough money
          (> cash (:price property))
          ;; Player wants to buy it...
          ;; [invoke player for option decision]
          (= :buy (:action (function game-state :property-option {:property property}))))

      ;; Apply the purchase
      (-> game-state
          ;; Add to player's owned collection
          (update-in [:players player-index :properties]
                     assoc (:name property) {:status      :paid
                                             :house-count 0})
          ;; Subtract money
          (update-in [:players player-index :cash]
                     - (:price property))
          ;; Track transaction
          (append-tx {:type     :purchase
                      :player   (:id player)
                      :property (:name property)
                      :price    (:price property)}))

      ;; Apply auction workflow
      ;; TODO - need to implement this
      ;; TODO - need also add another condition that it's unowned
      game-state)))


;; ==============

;; Special function to core
(defn- move-to-cell
  ;; ||=============================================================
  ;; || Allowance logic - calculate
  ;; || Allowance logic - inc cash + transaction
  ;; || Update cell residency
  ;; || [Invoke cell effect(s)]
  ;; ||  - "Go to Jail" spot
  ;; ||  - Tax
  ;; ||  - Rent
  ;; ||  - Card Draw
  ;; ||  - Property Purchase Option
  ;; ||=============================================================
  [{:keys [board players]
    :as   game-state}
   new-cell driver]
  (let [;; Get current player info
        player         (util/current-player game-state)
        player-id      (:id player)
        pidx           (:player-index player)
        player-cash    (:cash player)
        old-cell       (:cell-residency player)
        ;; Check for allowance
        ;; If the old cell index is GT the new,
        ;; then we've looped around, easy
        ;; ! - this assumes GO is on cell 0, possibly okay...
        allowance      (get-in board [:cells 0 :allowance])
        with-allowance (when (> old-cell new-cell)
                         (+ player-cash allowance))
        ;; Initial state update, things that have
        ;; to happen before the move effects
        new-state
        (cond-> game-state
          ;; Move player
          ;; TODO - the true here is not great...
          true
          (-> (assoc-in [:players pidx :cell-residency] new-cell)
              (append-tx {:type        :move
                          :driver      driver
                          :player      player-id
                          :before-cell old-cell
                          :after-cell  new-cell}))
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
      (= :go-to-jail
         (get-in board [:cells new-cell :type]))
      (util/send-to-jail new-state player-id [:cell :go-to-jail])
      ;; Tax
      (tax-owed? new-state)
      (let [tax-owed (tax-owed? new-state)]
        (-> new-state
            (update-in [:players pidx :cash] - tax-owed)
            ;; Just take from player
            (append-tx {:type   :payment
                        :from   player-id
                        :to     :bank
                        :amount tax-owed
                        :reason :tax})))
      ;; Rent
      (util/rent-owed? new-state)
      (let [rent-owed (util/rent-owed? new-state)]
        (-> new-state
            (update-in [:players pidx :cash] - (second rent-owed))
            ;; Take from current player, give to owner
            (update-in [:players
                        ;; Get the player index of owed player
                        ;; TODO - this could probably be refactored
                        (->> players
                             (map-indexed vector)
                             (filter #(= (:id (second %))
                                         (first rent-owed)))
                             first first)
                        :cash]
                       + (second rent-owed))
            (append-tx {:type   :payment
                        :from   player-id
                        :to     (first rent-owed)
                        :amount (second rent-owed)
                        :reason :rent})))
      ;; Card Draw
      (let [cell-def (get-in board [:cells new-cell])]
        (= :card (:type cell-def)))
      (cards/apply-card-draw new-state)
      ;; None of the above, player option
      ;; or auction off property
      :else (apply-property-option new-state))))

(defn- apply-dice-roll
  ;; ||=============================================================
  ;; || Check for dice-jailed? and also invoke incareration state???
  ;; || update the dice roll record on current-turn
  ;; || transaction for dice roll
  ;; ||=============================================================
  [game-state new-roll]
  (let [;; Get current player info
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
            new-cell (next-cell game-state (apply + new-roll) old-cell)]
        ;; TODO - Once we start putting this "move to cell" logic in the game state,
        ;;        we should probably invoke that one
        (move-to-cell new-state new-cell :dice)))))

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

      ;; Check if game is already complete
      (= :complete (:status game-state))
      (do (println "Game complete, can't advance further")
          game-state)

      ;; Check if it's time to end the game,
      ;; only 1 active player left?
      ;; TODO - is it possible for 0 to be left?
      (->> players
           (filter #(= :playing (:status %)))
           count
           (= 1))
      (do (println "Only one active player left, marking game as complete")
          (assoc game-state :status :complete))

      ;; !!! Just in case !!! (TEMP)
      ;; Check if it's time to end the game,
      ;; no active player left?
      ;; This shouldn't happen, but let's log if it does
      (->> players
           (filter #(= :playing (:status %)))
           count
           (= 0))
      (do (println "!!Zero active players left, this shouldn't happen!!")
          (assoc game-state :status :complete))

      ;; Basic bankrupt logic, before turn..
      ;; If the player is out of money,
      ;; take them out of rotation (bankrupt)
      ;; and move on to next player
      (or (= :bankrupt status) ;; probably don't need to check this?
          (> 0 cash))
      (do
        (println "Need to bankrupt player:" player-id)
        (-> game-state
            ;; Move to next player FIRST
            ;; (we can get caught in a loop if we don't do this right)
            util/apply-end-turn
            ;; Then process bankruptcy workflow
            (simple-bankupt-player player-id)))

      ;; If they have cash, and it's not time to end the train
      ;; proceed with regular player turn
      :else
      (let [;; Basic/jumbled for now, long lets nested ifs...
            ;; TODO - need to add other actions soon, and this logic
            ;;        _could_ blow up, need another fn
            jail-spell (:jail-spell player)
            last-roll  (->> current-turn :dice-rolls last)
            can-roll?  (or (nil? last-roll)
                           (and (vector? last-roll)
                                (apply = last-roll)))
            can-build? (util/can-buy-house? game-state)
            actions    (->> (vector
                              ;; TODO - Need to force certain number of rolls before :done can be available
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
                                 ;; TODO - Get out of jail card
                                 ;; (when (has-jail-card? player)
                                 ;;   :jail/bail-card)
                                 ]

                                ;; Regular dice rolls
                                (when can-roll? :roll))

                              ;; House building
                              (when can-build? :buy-house))
                            flatten
                            (filter identity)
                            set)

            ;; Start right away by invoking players turn
            ;; method, to get next response/decision
            decision (function game-state :take-turn {:actions-available actions})]

        (case (:action decision)
          ;; Player done, end turn, advance to next player
          :done      (util/apply-end-turn game-state)
          ;; Roll Dice
          :roll      (-> game-state
                         ;; Do the roll and move
                         (apply-dice-roll (roll-dice 2)))
          ;; Buy house(s)
          :buy-house (apply-house-purchase
                       game-state
                       (:property-name decision))
          ;; TODO - Sell house(s)
          ;; TODO - Make offer
          ;; TODO - Mortgage/un-mortgage

          ;; JAIL
          ;; TODO - looks like any jail action can be routed to this one fn
          :jail/roll      (util/apply-jail-spell game-state (:action decision))
          :jail/bail      (util/apply-jail-spell game-state (:action decision))
          :jail/bail-card (util/apply-jail-spell game-state (:action decision))

          ;; TODO - detect if player is stuck in loop?
          ;; TODO - player is taking too long?
          )))))

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
                          :function player/dumb-player-decision
                          :status :playing
                          :cash 1500
                          :cell-residency 0 ;; All starting on "Go"
                          :cards []
                          :properties {}))
             vec)
        ;; Define initial game state
        initial-state
        {:status       :playing
         :players      players
         :current-turn {:player     (-> players first :id)
                        :dice-rolls []}
         ;; Grab and preserve the default board/layout
         :board        defs/board
         ;; Shuffle all cards by deck
         :card-queue   (cards/cards->deck-queues (:cards defs/board))
         :transactions []
         :functions    {:move-to-cell    move-to-cell
                        :apply-dice-roll apply-dice-roll}}]
    ;; Just return this state
    initial-state))

(defn rand-game-state
  "Return a game state, with # of given players, as of the given, nth iteration"
  [players n]
  (->> (init-game-state players)
       (iterate advance-board)
       (take n)
       last))

(defn rand-game-end-state
  "Return a new, random, completed game state, with # of given players"
  [players]
  (->> (init-game-state players)
       (iterate advance-board)
       ;; Skip past all iterations until game is done, and there is a winner
       ;; OR, failsafe, the transactions have gone beyond X, most likely endless game
       (drop-while
         (fn [{:keys [status transactions]}]
           (and (= :playing status)
                ;; Some arbitrary limit
                (> 2000 (count transactions)))))
       first))

(comment

  (->> defs/board
       ;; :properties
       )

  (let [state (rand-game-end-state 4)]
    [(:status state)
     (-> state :transactions count)])


  (def sim (rand-game-state 4 700))

  sim

  (def sim
    (rand-game-end-state 4))

  ;; Cell landings stats
  (->> sim
       :transactions
       (filter #(= :move (:type %)))
       (map (fn [tx]
              (as-> tx *
                (get-in sim [:board :cells (:after-cell *)])
                (assoc * :cell (:after-cell tx)))))
       frequencies
       (sort-by second))

  (->> (rand-game-state 4 300)
       ;; :transactions
       ;; (drop 100)
       ;; (filter #(= :payment (:type %)))
       ;; (remove #(= :bank (:from %)))
       )


  (println "--------------------------------------------------------")

  sim


  (as-> (rand-game-state 4 500) *

    ;; Player property count
    ;; (:players *)
    ;; (map (fn [p]
    ;;        [(:id p) (count (:properties p))]
    ;;        ) *)

    ;; Player cash
    (:players *)
    (map #(select-keys % [:id :cash]) *)

    )


  )

  ;;
