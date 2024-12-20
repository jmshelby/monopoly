(ns jmshelby.monopoly.core
  (:require [jmshelby.monopoly.definitions :as defs]))

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
              :properties          #{{:name        :park-place
                                      :status      :paid-OR-mortgaged
                                      :house-count 0}}}]

   ;; Separately, track what is going on with the current "turn".
   ;; At any given type there is always a single player who's turn it is,
   ;; but other things can be happening at the same time.
   :current-turn {:player      "player uuid"
                  ;; If/when the current player rolls their dice
                  :rolled-dice []
                  ;; Some sort of state around the comm status with the player
                  ;; TODO - need to figure out what this looks like
                  ;; TODO - it could be a :phase thing, but maybe this tracks forced negotiation state/status
                  :status      :?
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

(defn dumb-player-decision
  [_game-state method actions-available]
  {:action
   ;; TODO - multimethod
   (case method
     ;; Dumb, always decline these actions
     :acquisition    :decline
     :auction-bid    :decline
     :offer-proposal :decline
     ;; Dumb, roll if we can, end turn if we can't
     :take-turn      (if (:roll actions-available)
                       :roll :done))})

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
                          :properties #{}
                          :consecutive-doubles 0))
             vec)
        ;; Define initial game state
        initial-state
        {:players      players
         :current-turn {:player (-> players first :id)}
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
      ;; Remove dice
      (dissoc-in [:current-turn :rolled-dice])
      ;; Get/Set next player ID
      (assoc-in [:current-turn :player] (-> game-state next-player :id))))

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
        player      (current-player game-state)
        player-id   (:id player)
        pidx        (:player-index player)
        player-cash (:cash player)
        old-cell    (:cell-residency player)
        ;; Start with a dice roll
        new-roll    (roll-dice 2)
        new-cell    (next-cell game-state (apply + new-roll) old-cell)
        ;; Check for allowance
        allowance   (when (> old-cell new-cell)
                      (+ player-cash
                         (get-in board [:cells 0 :allowance])))
        ;; Update State
        new-state
        (cond-> game-state
          ;; Save current new roll
          ;; TODO - what's the condition here?
          true (assoc-in [:current-turn :rolled-dice] new-roll)
          ;; Move Player, looping back around if needed
          ;; TODO - conditional, only if this wasn't the 3 consecutive double
          true (assoc-in [:players pidx :cell-residency] new-cell)
          ;; Check if we've passed/landed on go, for allowance payout
          ;; TODO - could probably refactor this
          allowance
          (assoc-in [:players pidx :cash] allowance))]
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
                 (when allowance
                   {:type   :payment
                    :from   :bank
                    :to     player-id
                    :amount allowance})])]
      ;; Add transactions, before returning
      (update new-state :transactions concat txactions)))

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
        actions            (set (vector :done (when-not (:rolled-dice current-turn)
                                                :roll)))
        ;; Start right away by invoking players turn
        ;; method, to get next response/decision
        decision           (function game-state :take-turn actions)

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
      :roll (apply-dice-roll game-state)
      ;; TODO - Make offer
      ;; TODO - Mortgage/un-mortgage
      ;; TODO - By/Sell houses
      ;; Player done, end turn
      :done (apply-end-turn game-state)
      ;; TODO - detect if player is stuck in loop?
      ;; TODO - player is taking too long?
      )))

(comment

  (as-> (init-game-state 4) *
    (iterate advance-board *)
    (take 500 *)
    (last *)
    ;; (:transactions *)
    ;; (group-by :player *)
    ;; (get * "A")
    ;; (filter #(= :move (:type %)) *)
    ;; (filter #(= :payment (:type %)) *)
    ;; (map :after-cell *)
    ;; (map #(get (:cells defs/board) %) *)
    )

  (next-cell {:board defs/board}
             30 12)

  (->> defs/board

       )

  (keep identity [1 nil 2 3 4 nil])

  )

  ;;
