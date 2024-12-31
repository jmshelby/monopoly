(ns jmshelby.monopoly.core
  (:require [clojure.set :as set]
            [jmshelby.monopoly.definitions :as defs]))

;; TODO - need to determine where and how many "seeds" to store

;; Game state, schema
(def example-state
  {;; Static board definition for the game
   :board "[See definitions NS]"

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

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn- roll-dice
  "Return a random dice roll of n # of dice"
  [n]
  (vec (repeatedly n #(inc (rand-int 6)))))

(defn- next-cell
  "Given a game-state, dice sum, and current cell idx, return
  the next cell idx after moving that number of cells"
  [game-state n idx]
  ;; Modulo after adding dice sum to current spot
  (mod (+ n idx)
       (-> game-state :board :cells count)))

;; TODO - for consistency, this should just return the player map
(defn- next-player
  "Given a game-state, return the player ID of next player in line"
  [{:keys [players
           current-turn]}]
  (->> players
       ;; Attach ordinal
       (map-indexed (fn [idx p] (assoc p :player-index idx)))
       ;; Only active, non-mortgaged, players
       (filter #(= (:status %) :playing))
       ;; Round Robin
       cycle
       ;; Find current player
       (drop-while #(not= (:id %) (:player current-turn)))
       ;; Return next player ID
       fnext))

(defn current-player
  "Given a game-state, return the current player. Includes the index of the player"
  [{:keys [players
           current-turn]}]
  (->> players
       ;; Attach ordinal
       (map-indexed (fn [idx p] (assoc p :player-index idx)))
       (filter #(= (:id %) (:player current-turn)))
       first))

(defn owned-properties
  "Given a game-state, return the set of owned property ids/names"
  [game-state]
  (->> game-state :players
       (mapcat :properties)
       (map :key) set))

(defn owned-property-details
  "Given a game-state, return the set of owned property
  details as a map of prop ID -> owned state with attached
  owner ID and property details/definition."
  [{:keys [board]
    :as   game-state}]
  (->> game-state :players
       (mapcat (fn [player]
                 (map (fn [[prop-name owner-state]]
                        [prop-name
                         (assoc owner-state
                                :owner (:id player)
                                :def (->> board :properties
                                          (filter #(= prop-name (:name %)))
                                          first))])
                      (:properties player))))
       (into {})))

(defn dumb-player-decision
  [_game-state method params]
  {:action
   ;; TODO - multimethod
   (case method
     ;; Dumb, always decline these actions
     :acquisition     :decline
     :auction-bid     :decline
     :offer-proposal  :decline
     ;; Dumb, always buy a property if we can
     :property-option :buy
     ;; Dumb, roll if we can, end turn if we can't
     ;; TODO - If we can buy the current property, do it
     :take-turn       (if (:roll (:actions-available params))
                        :roll :done))}
  )

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
                          :function dumb-player-decision
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
      (assoc-in [:current-turn :player] (-> game-state next-player :id))))

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
         :as   player}
        (current-player game-state)
        current-cell (get-in board [:cells cell-residency])
        ;; Get the definition of the current cell *if* it's a property
        property     (and (-> current-cell :type (= :property))
                          (->> board :properties
                               (filter #(= (:name %) (:name current-cell)))
                               first))
        taken        (owned-properties game-state)]
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
  [{:keys [board players
           current-turn]
    :as   game-state}]
  ;; Thought - implement as loop / trampoline?

  ;; TEMP - Simple logic to start...
  (let [;; Get current player info
        player         (current-player game-state)
        player-id      (:id player)
        pidx           (:player-index player)
        player-cash    (:cash player)
        old-cell       (:cell-residency player)
        ;; Start with a dice roll
        new-roll       (roll-dice 2)
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
    (let [txactions
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
                    :amount allowance})])]
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
  [{:keys [current-turn]
    :as   game-state}]

  (let [;; Get current player function
        {:keys [function]} (current-player game-state)
        ;; Simple for now, just roll and done actions available
        ;; TODO - need to add other actions soon
        last-roll          (->> current-turn :dice-rolls last)
        can-roll?          (or (nil? last-roll)
                               (and (vector? last-roll)
                                    (apply = last-roll)))
        actions            (set (vector :done (when can-roll? :roll)))
        ;; Start right away by invoking players turn
        ;; method, to get next response/decision
        decision           (function game-state :take-turn {:actions-available actions})

        ;; TODO - validation, derive possible player actions
        ;;        * if invalid response, log it, and replace with simple/dumb operation
        ;;          - roll/decline/end-turn, etc..

        ]

    ;; TODO - Different actions/logic when in jail?
    ;;         * maybe just after dice rolls, looking for doubles
    ;;         * When sending list of available actions, we can now offer get "out of jail"
    ;;           - for $50
    ;;           - for single get out of jail card

    (case (-> decision :action)
      ;; Roll Dice
      :roll (-> game-state
                ;; Do the roll and move
                apply-dice-roll
                ;; Check and give option to buy property
                apply-property-option)
      ;; TODO - Make offer
      ;; TODO - Mortgage/un-mortgage
      ;; TODO - By/Sell houses
      ;; Player done, end turn, advance to next player
      :done (apply-end-turn game-state)
      ;; TODO - detect if player is stuck in loop?
      ;; TODO - player is taking too long?
      )

    ))

(comment

  (as-> (init-game-state 4) *
    (iterate advance-board *)
    (take 500 *)
    (last *)

    ;; Player property count
    ;; (:players *)
    ;; (map (fn [p]
    ;;        [(:id p) (count (:properties p))]
    ;;        ) *)

    )


  (next-cell {:board defs/board}
             30 12)

  (->> defs/board

       )

  (keep identity [1 nil 2 3 4 nil])


  (let [state
        {:players [{:properties {:one {} :three {}}}
                   {:properties {}}
                   {:properties {:five {}}}
                   {:properties {}}
                   {:properties {:nine {}}}
                   ]}]
    (->> state :players
         (mapcat :properties)
         (map key) set
         )
    )


  )

  ;;
