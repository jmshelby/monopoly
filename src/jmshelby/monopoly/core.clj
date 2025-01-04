(ns jmshelby.monopoly.core
  (:require [clojure.set :as set]
            [jmshelby.monopoly.util :as util]
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
              :id                  "some-uuid"
              ;; Status, playing/bankrupt
              :status              :playing
              ;; Current amount of money on hand
              :cash                1
              ;; Special card collection, current set
              :cards               [:get-out-of-jail-free]
              ;; Which cell on the board are they currently in
              :cell-residency      0
              ;; Number of consecutive doubles, current count
              :consecutive-doubles 2
              ;; If on jail cell (haha), visiting or incarcerated
              ;; TODO - should we just track :incarcerated?
              :jail-status         :visiting-OR-incarcerated-OR-nil-for-none
              ;; The current set of owned "properties", and current state
              :properties          {:park-place {:status      :paid-OR-mortgaged
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
        {:players      players
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
        taken          (util/owned-properties game-state)
        ]
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
  "Given a game state, advance board as if current player rolled dice.
  This function could advance the board forward by more than 1 transaction/move,
  if the move requires further actions from players,
  (like needing more money, bankrupcies/acquisitions, etc)"
  [{:keys [board players]
    :as   game-state}]
  ;; Thought - implement as loop / trampoline?

  ;; TEMP - Simple logic to start...
  (let [;; Get current player info
        player         (util/current-player game-state)
        player-id      (:id player)
        pidx           (:player-index player)
        player-cash    (:cash player)
        old-cell       (:cell-residency player)
        ;; Start with a dice roll
        new-roll       (util/roll-dice 2)
        new-cell       (next-cell game-state (apply + new-roll) old-cell)
        ;; Check for allowance
        allowance      (get-in board [:cells 0 :allowance])
        with-allowance (when (> old-cell new-cell)
                         (+ player-cash allowance))
        ;; Update State
        new-state
        (cond-> game-state
          ;; Save current new roll
          ;; TODO - what's the condition here?
          true (update-in [:current-turn :dice-rolls] conj new-roll)
          ;; Move Player, looping back around if needed
          ;; TODO - conditional, only if this wasn't the 3 consecutive double
          true (assoc-in [:players pidx :cell-residency] new-cell)
          ;; Check if we've passed/landed on go, for allowance payout
          ;; TODO - could probably refactor this
          with-allowance
          (assoc-in [:players pidx :cash] with-allowance))]
    (let [;; Pay rent if needed
          rent-owed (util/rent-owed? new-state)
          tax-owed  (tax-owed? new-state)
          ;; TODO - yikes, getting messy, impl dispatch by cell type
          new-state (if-not rent-owed
                      ;; No rent, check for tax
                      (if tax-owed
                        ;; Peform tax transfer
                        (update-in new-state [:players pidx :cash] - tax-owed)
                        ;; Nothing else do
                        new-state)
                      ;; Perform rent transfer
                      (-> new-state
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
                                     + (second rent-owed))))
          ;; Add transactions
          txactions
          (keep identity
                [{:type       :roll
                  :player     player-id
                  :player-idx pidx
                  :roll       new-roll}
                 {:type        :move
                  :player      player-id
                  :player-idx  pidx
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
      (update new-state :transactions (comp vec concat) (vec txactions))))

  ;; - Validate that current player *can* roll

  ;; - Get random dice pair roll result
  ;;   - If double rolled, inc consecutive-doubles
  ;;     - If consecutive-double == 3...
  ;;       -> [Perform jail workflow]

  ;; - Increment cell residency + dice roll sum

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

(defn advance-board
  "Given game state, advance the board, by
  invoking player logic and applying decisions."
  [{:keys [players current-turn]
    :as   game-state}]

  (let [;; Get current player function
        {pidx      :player-index
         player-id :id
         :keys     [cash status function]}
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
      (or (= :bankrupt status) ;; probably don't need to check this? If I remove it, there's an endless loop
          (> 0 cash))
      (do
        (println "Need to bankrupt player:" player-id)
        (-> game-state
            ;; Move to next player FIRST
            ;; (we can get caught in a loop if we don't do this right)
            apply-end-turn
            ;; Then mark player with bankrupt status
            ;; TODO - transaction?
            (assoc-in [:players pidx :status] :bankrupt)))

      ;; If they have cash, and it's not time to end the train
      ;; proceed with regular player turn
      :else
      (let [_          (println "Player turn logic:" player-id
                                "players left: " (->> players
                                                      (filter #(= :playing (:status %)))
                                                      count) )
            ;; Basic/jumbled for now, long lets nested ifs...
            ;; TODO - need to add other actions soon, and this logic
            ;;        _could_ blow up, need another fn
            last-roll  (->> current-turn :dice-rolls last)
            can-roll?  (or (nil? last-roll)
                           (and (vector? last-roll)
                                (apply = last-roll)))
            can-build? (util/can-buy-house? game-state)
            actions    (->> (vector
                              ;; TODO - Need to force certain number of rolls before :done can be available
                              :done
                              ;; TODO - Need to take jail into account
                              (when can-roll? :roll)
                              ;; House building
                              (when can-build? :buy-house))
                            (filter identity)
                            set)
            ;; Start right away by invoking players turn
            ;; method, to get next response/decision
            decision   (function game-state :take-turn {:actions-available actions})

            ;; TODO - validation, derive possible player actions
            ;;        * if invalid response, log it, and replace with simple/dumb operation
            ;;          - roll/decline/end-turn, etc..

            ]


        ;; TODO - Different actions/logic when in jail?
        ;;         * maybe just after dice rolls, looking for doubles
        ;;         * When sending list of available actions, we can now offer get "out of jail"
        ;;           - for $50
        ;;           - for single get out of jail card

        (case (:action decision)
          ;; Player done, end turn, advance to next player
          :done      (apply-end-turn game-state)
          ;; Roll Dice
          :roll      (-> game-state
                         ;; Do the roll and move
                         apply-dice-roll
                         ;; Check and give option to buy property
                         apply-property-option)
          ;; Buy house(s)
          :buy-house (apply-house-purchase
                       game-state
                       (:property-name decision))
          ;; TODO - Sell house(s)
          ;; TODO - Make offer
          ;; TODO - Mortgage/un-mortgage

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
       ;; TODO - it would be nice to have some kind of fail safe/limit, in case of a endless loop bug
       (drop-while #(= :player (:status %)))
       first))

(comment

  (->> defs/board
       ;; :properties
       )

  (def sim (rand-game-state 4 1))


  (as-> sim *
    (iterate advance-board *)
    (nth * 201)
    ;; (:transactions *)
    (:players *)
    )

  (->> (rand-game-state 4 1000)
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
