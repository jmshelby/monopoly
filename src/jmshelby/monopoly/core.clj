(ns jmshelby.monopoly.core
  (:require [clojure.set :as set]
            [jmshelby.monopoly.util :as util
             :refer [roll-dice dissoc-in]]
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
                  :status     :?
                  }

   ;; The current *ordered* care queue to pull from.
   ;; At the beginning of the game these will be loaded at random,
   ;; when one queue is exhausted, it is randomly filled again.
   ;; TODO - Just the keyword name? Or is a map needed?
   :card-queue {:chance          []
                :community-chest []}

   ;; A list of all game move history, and it's details.
   ;; This is probably more of an enhanced feature...
   :transactions [
                  ;; Types:
                  ;; - Player rolls
                  ;;   - Player Current State? (or just ID?)
                  ;;   - Die numbers
                  ;;   - From spot -> to spot -> to redirected spot (like going to jail)
                  ;; - Player Draws Card
                  ;;   - Card type
                  ;;   - Card id
                  ;;   - Kept Card?
                  ;;   - Action? (or can/should that always be another transaction)
                  ;; - [Player Card Action?]
                  ;; - Player makes offer
                  ;;   - Player from/to
                  ;;   - Is counter offer?
                  ;;   - Status: accepted/rejected/countered
                  ;; - Player goes bankrupt
                  ;; etc ...

                  ;; Thoughts:
                  ;;  - This is a lot like datomic...
                  ;;  - Each item in this list could be every unique game state
                  ]
   })

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
                          :properties {}
                          :consecutive-doubles 0))
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
         :card-queue   (->> defs/board
                            :cards
                            (group-by :deck)
                            (map (fn [[deck cards]]
                                   ;; Multiply certain cards
                                   [deck (mapcat #(repeat (:count % 1) %) cards)]))
                            (map (fn [[deck cards]]
                                   [deck (shuffle cards)]))
                            (into {}))
         :transactions []}]
    ;; Just return this state
    initial-state))

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
        (update :transactions conj
                {:type       :bankruptcy
                 :player     player-id
                 :properties (:properties player)
                 :acquistion [:basic :bank]}))))

(defn send-to-jail
  "Given game state, move given player into an
  incarcerated jail state. Also provide the cause
  for why a player is being sent to jail."
  [{:keys [board players]
    :as   game-state}
   player-id cause]
  (let [{pidx     :player-index
         old-cell :cell-residency}
        (->> players
             (map-indexed (fn [idx p] (assoc p :player-index idx)))
             (filter #(= (:id %) player-id))
             first)
        new-cell (util/jail-cell-index board)]
    (-> game-state
        ;; Move player to jail spot
        (assoc-in [:players pidx :cell-residency]
                  new-cell)
        ;; Set new jail key on player,
        ;; to track jail workflow
        (assoc-in [:players pidx :jail-spell]
                  {:cause      cause
                   :dice-rolls []})
        ;; Transaction
        (update :transactions conj
                {:type        :move
                 :driver      :incarceration
                 :cause       cause
                 :player      player-id
                 :before-cell old-cell
                 :after-cel   new-cell}))))

(defn apply-end-turn
  "Given a game state, advance board, changing
  turns to the next active player in line."
  [game-state]
  (-> game-state
      ;; Remove dice rolls
      (assoc-in [:current-turn :dice-rolls] [])
      ;; Get/Set next player ID
      (assoc-in [:current-turn :player]
                (-> game-state util/next-player :id))))

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
        (update :transactions conj
                {:type     :purchase-house
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
          (update :transactions conj
                  {:type     :purchase
                   :player   (:id player)
                   :property (:name property)
                   :price    (:price property)}))

      ;; Apply auction workflow
      ;; TODO - need to implement this
      game-state)))

(defn apply-dice-roll
  "Given a game state and dice roll, advance board as
  if current player rolled dice. This function could
  advance the board forward by more than 1 transaction/move,
  if the move requires further actions from players,
  (like needing more money, bankrupcies/acquisitions, etc).
  Assumes:
  - player is not currently in jail"
  [{:keys [board players]
    :as   game-state}
   new-roll]

  ;; THOUGHT - implement as loop / trampoline?
  ;; THOUGHT - Can we produce an ordered transaction list, and the caller can apply it to game-state?

  ;; TEMP - Basic/messy impl to start ...
  (let [;; Get current player info
        player         (util/current-player game-state)
        player-id      (:id player)
        pidx           (:player-index player)
        player-cash    (:cash player)
        old-cell       (:cell-residency player)
        ;; Find next board position
        new-cell       (next-cell game-state (apply + new-roll) old-cell)
        ;; Jail trigger based on dice roll,
        ;; 3rd consecutive dice roll, player goes to jail
        dice-jailed?   (and (apply = new-roll)
                            (<= 2 (-> game-state :current-turn :dice-rolls count)))
        ;; Check for allowance
        ;; If the old cell index is GT the new,
        ;; then we've looped around, easy
        ;; ! - this assumes GO is on cell 0, possibly okay...
        allowance      (get-in board [:cells 0 :allowance])
        with-allowance (when (and (not dice-jailed?)
                                  (> old-cell new-cell))
                         (+ player-cash allowance))

        ;; Initial state update, things that have to happen
        new-state
        (cond-> game-state
          ;; Save current new roll
          ;; TODO - what's the condition here?
          true
          (update-in [:current-turn :dice-rolls] conj new-roll)
          ;; If they're not going to jail,
          ;; move player, looping back around if needed
          (not dice-jailed?)
          (assoc-in [:players pidx :cell-residency] new-cell)
          ;; Check if we've passed/landed on go, for allowance payout
          ;; TODO - could probably refactor this
          with-allowance
          (assoc-in [:players pidx :cash] with-allowance))

        ;; Check if any payments are needed
        rent-owed (util/rent-owed? new-state)
        tax-owed  (tax-owed? new-state)

        ;; Next state update, needed payments or jail
        ;; TODO - yikes, getting messy, impl dispatch by cell type
        new-state
        (cond-> new-state
          ;; Tax
          tax-owed
          (update-in [:players pidx :cash] - tax-owed)
          ;; Rent
          rent-owed
          (->
            ;; Take from current player
            (update-in [:players pidx :cash] - (second rent-owed))
            ;; Give to owner
            (update-in [:players
                        ;; Get the player index of owed player
                        ;; TODO - this could probably be refactored
                        (->> players
                             (map-indexed vector)
                             (filter #(= (:id (second %))
                                         (first rent-owed)))
                             first first)
                        :cash]
                       + (second rent-owed)))

          ;; "Go to Jail" cell landing
          (= :go-to-jail
             (get-in board [:cells new-cell :type]))
          ;; TODO - This adds it's own transaction ... but the
          ;;        below transactions will be out of order
          (send-to-jail player-id [:cell :go-to-jail])

          ;; "Go to Jail" dice roll, 3 consecutive doubles
          ;; TODO - Still need to test if this is working...
          dice-jailed?
          (send-to-jail player-id [:roll :double 3]))

        ;; Assemble transactions
        txactions (keep identity
                        [{:type   :roll
                          :player player-id
                          :roll   new-roll}
                         {:type        :move
                          :driver      :roll
                          :player      player-id
                          :before-cell old-cell
                          :after-cell  new-cell}
                         (when with-allowance
                           {:type   :payment
                            :from   :bank
                            :to     player-id
                            :amount allowance
                            :reason :allowance})
                         (when tax-owed
                           {:type   :payment
                            :from   player-id
                            :to     :bank
                            :amount tax-owed
                            :reason :tax})
                         (when rent-owed
                           {:type   :payment
                            :from   player-id
                            :to     (first rent-owed)
                            :amount (second rent-owed)
                            :reason :rent})])]

    ;; Add transactions, before returning
    ;; TODO - Had to comp with vec to keep it a vector ... can we make this look better?
    (update new-state :transactions (comp vec concat) (vec txactions)))

  ;; NOTE - The below can/should be categorized based on the cell "type",
  ;;        and then further categorized for "properties", based on their types
  ;; - [Perform new cell residency]
  ;;   - Landed on "Free"
  ;;     -> Return with new game state
  ;;   - Landed on "Jail"
  ;;     -> Return with new game state
  ;;       - (probably having a jail status of :visiting ?)
  ;;   - Landed on "Go to Jail"
  ;;     -> [Perform jail workflow]
  ;;   - Landed on "Go"
  ;;     -> Inc player money + allowance
  ;;   - Property unowned
  ;;     -> Return state (caller will invoke property offer workflow)
  ;;   - Landed on Type==tax
  ;;     * If insufficient funds, invoke forced negotiation workflow
  ;;     -> Pay tax
  ;;   - Property owned by player
  ;;     -> If passed "Go", inc player money + allowance
  ;;     -> Nothing needed, return new state
  ;;   - Property owned by other player
  ;;     -> If passed "Go", inc player money + allowance
  ;;     * If insufficient funds, invoke forced negotiation workflow
  ;;     -> Pay rent, transact cash to other player
  ;;   - Landed on Type==card
  ;;     -> Pop card from corresponding deck
  ;;     -> [Apply card rules to state]
  ;;       - (This can result in another cell move; cash transaction...)
  ;;       * If insufficient funds, invoke forced negotiation workflow

  )

(defn apply-jail-spell
  "Given a game state and player jail specific action,
  apply decision and all affects. This be as little as a
  double dice roll attempt and staying in jail, to getting
  out, moving spaces and landing on another which can cause
  other side affects."
  [game-state action]

  ;; ?TODO? - Should this validate that certain things can happen?
  ;;          like affording bail or having the bail card? or
  ;;          should the caller do that?

  (let [player    (util/current-player game-state)
        player-id (:player-id player)
        pidx      (:player-index player)
        bail      (->> game-state :board :cells
                       (filter #(= :jail (:type %)))
                       first :bail)]
    (case action
      ;; Attempt roll for doubles
      :jail/roll
      (let [new-roll (roll-dice 2)
            ;; Which attempt num to roll out of jail
            attempt  (-> player :jail-spell :dice-rolls count inc)]
        (cond
          ;; It's a double! Take out of jail,
          ;; and apply as a regular dice roll
          (apply = new-roll)
          (-> game-state
              (dissoc-in [:players pidx :jail-spell])
              ;; TODO - There is also a :roll transaction type,
              ;;        how should it work here?
              (update :transactions conj
                      {:type   :bail
                       :player player-id
                       :means  [:roll :double new-roll]})
              ;; TODO - Somehow need to signal that they don't get another
              ;;        roll, because a double thrown while in jail doesn't
              ;;        grant that priviledge
              (apply-dice-roll new-roll)
              ;; TODO - Should the apply-dice-roll fn just do this?
              apply-property-option)

          ;; Not a double, third attempt.
          ;; Force bail payment, and move
          (<= 3 attempt)
          (-> game-state
              (dissoc-in [:players pidx :jail-spell])
              (update-in [:players pidx :cash] - bail)
              ;; TODO - There is also a :roll transaction type,
              ;;        how should it work here?
              (update :transactions conj
                      {:type   :bail
                       :player player-id
                       :means  [:cash bail]})
              (apply-dice-roll new-roll)
              ;; TODO - Should the apply-dice-roll fn just do this?
              apply-property-option)

          ;; Not a double, register roll
          :else
          (-> game-state
              ;; To both current, and jail spell records
              (update-in [:current-turn :dice-rolls] conj new-roll)
              (update-in [:players pidx :jail-spell :dice-rolls] conj new-roll))))

      ;; If you can afford it, pay to get out of jail,
      ;; staying on the same cell
      :jail/bail
      ;; TODO - this is quite duplicated code..
      (-> game-state
          (dissoc-in [:players pidx :jail-spell])
          (update-in [:players pidx :cash] - bail)
          (update :transactions conj
                  {:type   :bail
                   :player player-id
                   :means  [:cash bail]}))

      ;; If you have the card, use it to get out of jail,
      ;; staying on the same cell
      ;; TODO
      :jail/bail-card game-state
      ;; :means [:card :chance-or-community-chest]
      )))

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
            apply-end-turn
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
          :done      (apply-end-turn game-state)
          ;; Roll Dice
          :roll      (-> game-state
                         ;; Do the roll and move
                         (apply-dice-roll (roll-dice 2))
                         ;; Check and give option to buy property
                         ;; TODO - should the apply-dice-roll fn just do this?
                         apply-property-option)
          ;; Buy house(s)
          :buy-house (apply-house-purchase
                       game-state
                       (:property-name decision))
          ;; TODO - Sell house(s)
          ;; TODO - Make offer
          ;; TODO - Mortgage/un-mortgage

          ;; JAIL
          ;; TODO - looks like any jail action can be routed to this one fn
          :jail/roll      (apply-jail-spell game-state (:action decision))
          :jail/bail      (apply-jail-spell game-state (:action decision))
          :jail/bail-card (apply-jail-spell game-state (:action decision))

          ;; TODO - detect if player is stuck in loop?
          ;; TODO - player is taking too long?
          ))

      )))

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

  (rand-game-end-state 4)

  (->> (rand-game-state 4 200)
       ;; :transactions
       ;; (filter #(= :payment (:type %)))
       ;; (remove #(= :bank (:from %)))
       )

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
