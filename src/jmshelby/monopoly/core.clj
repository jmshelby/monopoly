(ns jmshelby.monopoly.core
  (:require [clojure.set :as set
             :refer [union subset? difference]]
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
              :cards          #{:get-out-of-jail-free}
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

;; TODO - This will most likely turn into the "bankrupt by the bank" flow..
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

(defn- validate-proposal-side
  "Given a player state, and resources for one
  side of a proposal, validate that the player
  has the required resources, and is able to
  perform a trade with them."
  [player resources]
  (->> resources
       (map (fn [[type val]]
              (case type
                ;; Make sure the player has enough cash
                :cash  (<= val (player :cash))
                ;; Make sure the cards are owned
                :cards (subset? val (player :cards))
                ;; Make sure the property names are in
                ;; the player's non-built on props
                :properties
                (->> player :properties
                     (filter (fn [[_ state]]
                               (= 0 (:house-count state))))
                     keys set
                     (subset? val)))))
       ;; TODO - this can probably just find some false?
       (every? true?)))

(comment

  (validate-proposal-side
    ;; Player
    {:cash 99}
    ;; Resources
    {:cash 100}
    )


  (validate-proposal-side
    ;; Player
    {:cash  99
     :cards #{{:deck            :chance
               :card/effect     :retain
               :card.retain/use :bail}}}
    ;; Resources
    {:cards #{{:deck            :chance
               :card/effect     :retain
               :card.retain/use :bail}}
     }
    )


  (validate-proposal-side
    ;; Player
    {:cash       99
     :properties {:lacey-lane   {:status      :mortgaged
                                 :house-count 0}
                  :cool-place   {:status      :paid
                                 :house-count 0}
                  :sweet-street {:status      :paid
                                 :house-count 0}

                  }
     }
    ;; Resources
    {:properties #{:cool-place :sweet-street}}
    )
  )

;; (defn- abate-player-resources
;;   [player resources])

;; (defn- augment-player-resources
;;   [player resources]

;;   )

(defn exchange-properties
  [game-state from-pidx to-pidx prop-names]
  (let [;; Get player maps
        to-player   (get-in game-state [:players to-pidx])
        from-player (get-in game-state [:players from-pidx])
        ;; Get 'from' player property states, only
        ;; needed to preserve mortgaged status
        prop-states (select-keys (:properties from-player) prop-names)]
    (-> game-state
        ;; Remove props from the 'from' player
        (update-in [:players from-pidx :properties]
                   ;; Just a dissoc that takes a set
                   (partial apply dissoc) prop-names)
        ;; Add props + existing state to the 'to' player
        (update-in [:players to-player :properties]
                   ;; Just a conj of existing prop states
                   ;; into player's own property state map
                   conj prop-states))))

(defn- apply-trade
  "Given a game state and a proposal, exchange the resources in the accepted proposal."
  [game-state proposal]
  (let [{pidx-proposer
         :player-index} (util/player-by-id game-state (:trade/from-player proposal))
        {pidx-acceptor
         :player-index} (util/player-by-id game-state (:trade/to-player proposal))
        ;; Pull out both sides of the proposal and ensure
        ;; all resource keys are available with empty values
        stub-defaults   (fn [p] (merge p {:cash 0 :cards #{} :properties #{}}))
        asking          (stub-defaults (:trade/asking proposal))
        offering        (stub-defaults (:trade/offering proposal))]

    ;; With both sides of the proposal having empty defaults,
    ;; we can blindly apply each resource in every direction
    ;; (without checking which direction each resource is
    ;; actually going in this particular trade)
    (-> game-state
        ;; Cash: acceptor -> proposer
        (update-in [:players pidx-proposer :cash] + (:cash asking))
        (update-in [:players pidx-acceptor :cash] - (:cash asking))
        ;; Cash: proposer -> acceptor
        (update-in [:players pidx-acceptor :cash] + (:cash offering))
        (update-in [:players pidx-proposer :cash] - (:cash offering))
        ;; Cards: acceptor -> proposer
        (update-in [:players pidx-proposer :cards] union (:cards asking))
        (update-in [:players pidx-acceptor :cards] difference (:cards asking))
        ;; Cards: proposer -> acceptor
        (update-in [:players pidx-acceptor :cards] union (:cards offering))
        (update-in [:players pidx-proposer :cards] difference (:cards offering))
        ;; Properties: acceptor -> proposer
        (exchange-properties pidx-acceptor pidx-proposer (:properties asking))
        ;; Properties: proposer -> acceptor
        (exchange-properties pidx-proposer pidx-acceptor (:properties offering)))))

(defn- append-tx-trade
  [game-state status proposal]
  (append-tx game-state
             {:type     :trade
              ;; TODO - or :trade/status?
              ;; TODO - or "stage"??
              :status   status
              :to       (:trade/to-player proposal)
              :from     (:trade/from-player proposal)
              :asking   (:trade/asking proposal)
              :offering (:trade/offering proposal)}))

(defn- apply-trade-proposal
  [game-state proposal]

  (let [asking      (:trade/asking proposal)
        offering    (:trade/offering proposal)
        to-player   (->> game-state
                         :players
                         (filter #(= (:id %)
                                     (:trade/to-player proposal)))
                         first)
        from-player (util/current-player game-state)]

    ;; Validation
    (when-not
        (and
          ;; The current player is offering
          (= (:id from-player)
             (:trade/from-player proposal))
          ;; Offerred player has resources
          (validate-proposal-side to-player asking)
          ;; Offering player has resources
          (validate-proposal-side from-player offering))
      ;; TODO - If this is invalid, we might want to return such instead of an exception
      (throw (ex-info "Invalid trade proposal" {})))

    (let [;; Log initial proposal
          game-state   (append-tx-trade game-state :proposal proposal)
          ;; Dispatch trade-proposal to other player's decision logic
          to-player-fn (:function to-player)
          decision     (to-player-fn game-state :trade-proposal proposal)]

      ;; TODO - implement "counter proposal" logic
      ;; TODO - somehow need to prevent endless proposal loops from happening

      (case (:action decision)
        ;; Turned down, log last status/stage
        :decline
        (append-tx-trade game-state :decline proposal)
        ;; Accepted
        :accept
        (-> game-state
            ;; Perform transaction of resources
            (apply-trade proposal)
            ;; Log last status/stage
            (append-tx-trade :accept proposal))))))

;; Special function to core
(defn- move-to-cell
  "Given a game state, destination cell index, and reason/driver
  for moving... apply effects to move current player from their
  cell to the destination cell. Applies GO allowance if applicable,
  resulting transactions, and destination cell effects/options."
  [{:keys [board players]
    :as   game-state}
   new-cell driver
   & {:keys [allowance?] :or {allowance? true}}]
  (let [;; Get current player info
        player         (util/current-player game-state)
        player-id      (:id player)
        pidx           (:player-index player)
        player-cash    (:cash player)
        old-cell       (:cell-residency player)
        ;; Check for allowance (from > to)
        allowance      (get-in board [:cells 0 :allowance])
        with-allowance (when (and allowance?
                                  (> old-cell new-cell))
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
      (util/tax-owed? new-state)
      (let [tax-owed (util/tax-owed? new-state)]
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
      :else (util/apply-property-option new-state))))

(defn- apply-dice-roll
  "Given a game state and new dice roll, advance game according to
  roll. 3rd consecutive roll goes to jail, otherwise, register dice
  roll + transaction, and invoke actual player move."
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
            new-cell (util/next-cell (:board game-state) (apply + new-roll) old-cell)]
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
      ;; TODO - will this ever happen? (should it?)
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
      (assoc game-state :status :complete)

      ;; !!! Just in case !!! (TEMP)
      ;; Check if it's time to end the game,
      ;; no active player left?
      ;; This shouldn't happen, but let's log if it does
      ;; TODO - will this ever happen? (should it?)
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
      (-> game-state
          ;; Move to next player FIRST
          ;; (we can get caught in a loop if we don't do this right)
          util/apply-end-turn
          ;; Then process bankruptcy workflow
          (simple-bankupt-player player-id))

      ;; If they have cash, and it's not time to end the train
      ;; proceed with regular player turn
      ;; TODO - yikes, this is getting huge too ...
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
                                 ;; Bail with "get out of jail free" card
                                 (when (util/has-bail-card? player)
                                   :jail/bail-card)]

                                ;; Regular dice rolls
                                (when can-roll? :roll))

                              ;; House building
                              (when can-build? :buy-house)

                              ;; Trade Proposals
                              ;; TODO - Later we'll need to determine if this is an option for the user ...
                              ;;        For now we'll just denied trades that are not possible
                              ;; - If you own some property, with no houses
                              ;; - If someone else owns some property, with no houses
                              ;; OR
                              ;; - If you have a card
                              ;; OR
                              ;; ??
                              :trade-proposal)
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
          :buy-house (util/apply-house-purchase
                       game-state
                       (:property-name decision))

          ;; TODO - Sell house(s)
          ;; TODO - Mortgage/un-mortgage

          ;; Proposing a trade
          ;; :trade-proposal
          ;; ;; Keys
          ;; [:trade/to-player
          ;;  :trade/asking
          ;;  :trade/offering]
          ;; ;; Asking/offering keys
          ;; [:cash int :cards set :properties set]
          ;; TODO - add :from-player to proposal map

          ;; dispatch -> apply-trade-proposal


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
                          :cards #{}
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
  ([players] (rand-game-end-state players 2000))
  ([players failsafe-thresh]
   (->> (init-game-state players)
        (iterate advance-board)
        ;; Skip past all iterations until game is done, and there is a winner
        ;; OR, failsafe, the transactions have gone beyond X, most likely endless game
        (drop-while
          (fn [{:keys [status transactions]}]
            (and (= :playing status)
                 ;; Some arbitrary limit
                 (> failsafe-thresh (count transactions)))))
        first)))

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

  (difference #{'a 'b 'c 'd} #{'c 'z})

  (union #{'a 'b 'c 'd} #{'z 'y})

  (conj {:one 1 :three 3} {:five 5 :nine 9})
  ;; =>

  (select-keys {:one 1, :three 3, :five 5, :nine 9}
               #{:three :nine})


  )

  ;;
