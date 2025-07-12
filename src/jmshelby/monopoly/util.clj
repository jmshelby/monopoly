(ns jmshelby.monopoly.util
  (:require [clojure.set :as set]
            [clojure.core.memoize :as memo]))

;; ======= General =============================

(defn half
  "Just a convenience/readable usage"
  [n] (/ n 2))

(defn rcompare
  "Just compare in reverse"
  [a b]
  (compare b a))

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

(defn sum
  "More concise way to sum dice roll"
  [nums]
  (apply + nums))

(defn roll-dice
  "Return a random dice roll of n # of dice"
  [n]
  (vec (repeatedly n #(inc (rand-int 6)))))


;; ======= Definition Derivations ==============

(defn *street-group-counts
  "Given a board definition, return a map of
  'street' property groups -> count.
  Useful for determining how many of each
  street type property is required in order
  to have a monopoly."
  [board]
  (->> board
       :properties
       (filter #(= :street (:type %)))
       (group-by :group-name)
       (map (fn [[k coll]] [k (count coll)]))
       (into {})))

(def street-group-counts
  (memoize *street-group-counts))

(defn jail-cell-index
  "Given a board definition, return the ordinal
  index on the board of the \"jail\" type cell."
  [board]
  (->> board :cells
       (map-indexed (fn [idx c]
                      (assoc c :cell-index idx)))
       (filter #(= :jail (:type %)))
       first
       :cell-index))


;; ======= Board Logistics =====================

(defn next-cell
  "Given a board, dice sum, and current cell idx, return
  the next cell idx after moving that number of cells"
  [board n idx]
  ;; Modulo after adding dice sum to current spot
  (mod (+ n idx)
       (-> board :cells count)))


;; ======= Transaction Management ==============

(defn append-tx [game-state & txs]
  ;; Allow transactions to come in as individual params or vectors of txs,
  ;; or even as nil, which will be filtered out (handy use with merge/when)
  (let [prepped
        (->> txs
             (mapcat (fn [tx]
                       (cond
                         (map? tx)        [tx]
                         (sequential? tx) tx
                         (nil? tx)        []
                         :else
                         (throw (ex-info
                                  "Appending a tx requires a map or collection of maps"
                                  {:type-given (type tx)})))))
             vec)]
    ;; For now we have to ensure we are concat'ing a vector with a vector...
    ;; TODO - Not sure where it's changing, is this fine to keep this way?
    (update game-state :transactions (comp vec concat) prepped)))


;; ======= Player Management ===================

;; TODO - for consistency, this should just return the player map
(defn next-player
  "Given a game-state, return the player ID of next player in line"
  [{:keys [players
           current-turn]}]
  (->> players
       ;; Attach ordinal
       (map-indexed (fn [idx p] (assoc p :player-index idx)))
       ;; Round Robin
       cycle
       ;; Find current player
       (drop-while #(not= (:id %) (:player current-turn)))
       ;; Only active, non-bankrupt, players
       (filter #(= :playing (:status %)))
       ;; Return next player ID
       fnext))

(defn player-by-id
  "Given a game-state, return player with given ID.
  Includes the index of the player"
  [{:keys [players]} id]
  (->> players
       ;; Attach ordinal
       (map-indexed (fn [idx p] (assoc p :player-index idx)))
       (filter #(= id (:id %)))
       first))

(defn other-players
  "Given a game-state and a player ID, return a collection
  of the other players, not including the given player ID.
  Includes the index of each player, :player-index."
  [{:keys [players]} id]
  (->> players
       ;; Attach ordinal
       (map-indexed (fn [idx p] (assoc p :player-index idx)))
       (remove #(= id (:id %)))))

(defn current-player
  "Given a game-state, return the current player.
  Includes the index of the player"
  [{:keys [current-turn]
    :as   game-state}]
  (player-by-id game-state (:player current-turn)))

(defn apply-end-turn
  "Given a game state, advance board, changing
  turns to the next active player in line."
  [game-state]
  ;; TODO - add transaction for this?
  (-> game-state
      ;; Remove dice rolls
      (assoc-in [:current-turn :dice-rolls] [])
      ;; Get/Set next player ID
      (assoc-in [:current-turn :player]
                (-> game-state next-player :id))))


;; ======= Jail Life Cycle =====================

(defn has-bail-card?
  [player]
  (->> player
       :cards
       (filter #(= :bail (:card.retain/use %)))
       seq))

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
        new-cell (jail-cell-index board)]
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
        (append-tx {:type        :move
                    :driver      :incarceration
                    :cause       cause
                    :player      player-id
                    :before-cell old-cell
                    :after-cell  new-cell})
        ;; All causes for going to jail result in forced end of turn
        apply-end-turn)))

(defn apply-jail-spell
  "Given a game state and player jail specific action, apply
  decision and all affects. This can be as little as a
  double dice roll attempt and staying in jail, to getting
  out, moving spaces and landing on another which can cause
  other side affects."
  [{{apply-dice-roll        :apply-dice-roll
     make-requisite-payment :make-requisite-payment}
    :functions
    :as game-state}
   action]

  ;; ?TODO? - Should this validate that certain things can happen?
  ;;          like affording bail or having the bail card? or
  ;;          should the caller do that?

  (let [player    (current-player game-state)
        player-id (:id player)
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
              ;; TODO - The "roll" transaction should happen here,
              ;;        but the apply-dice-roll is doing that for us ...
              (append-tx {:type   :bail
                          :player player-id
                          :means  [:roll :double new-roll]})
              ;; TODO - Somehow need to signal that they don't get another
              ;;        roll, because a double thrown while in jail doesn't
              ;;        grant that priviledge
              (apply-dice-roll new-roll))

          ;; Not a double, third attempt.
          ;; Force bail payment, and move
          (<= 3 attempt)
          (make-requisite-payment
            game-state player-id :bank bail
            #(-> %
                 (dissoc-in [:players pidx :jail-spell])
                 ;; TODO - The "roll" transaction should happen here,
                 ;;        but the apply-dice-roll is doing that for us ...
                 (append-tx {:type   :bail
                             :player player-id
                             :means  [:cash bail]})
                 (apply-dice-roll new-roll)))

          ;; Not a double, register roll
          :else
          (-> game-state
              ;; To both current, and jail spell records
              (update-in [:current-turn :dice-rolls] conj new-roll)
              (update-in [:players pidx :jail-spell :dice-rolls] conj new-roll)
              (append-tx {:type   :roll
                          :player player-id
                          :roll   new-roll}))))

      ;; If you can afford it, pay to get out of jail,
      ;; staying on the same cell
      ;; TODO - Should we validate there is enough money for this "chosen bail" action?? (maybe not REQUISITE-PAYMENT)
      ;;        The caller is checking this, but maybe it's good to throw an exception to catch bugs there?
      :jail/bail
      ;; TODO - this is quite duplicated code..
      (-> game-state
          (dissoc-in [:players pidx :jail-spell])
          (update-in [:players pidx :cash] - bail)
          (append-tx {:type   :bail
                      :player player-id
                      :means  [:cash bail]}))

      ;; If you have the card, use it to get out of jail,
      ;; staying on the same cell
      :jail/bail-card
      ;; TODO - this is quite duplicated code..
      (let [cards (has-bail-card? player)
            ;; TODO - we could ensure that bail cards are available here
            ;; Just take the first bail card
            card  (first cards)]
        (-> game-state
            (dissoc-in [:players pidx :jail-spell])
            (update-in [:players pidx :cards] set/difference #{card})
            (append-tx {:type   :bail
                        :player player-id
                        :means  [:card (:deck card)]
                        :card   card}))))))


;; ======= Taxes ===============================

(defn tax-owed?
  "Given a game-state, when a tax is owed by the
  current player, return the amount. Returns nil
  if no tax is due."
  [{:keys [board]
    :as   game-state}]
  (let [{:keys [cell-residency]}
        (current-player game-state)
        ;; Get the definition of the current cell *if* it's a property
        current-cell (get-in board [:cells cell-residency])]
    ;; Only return if tax is actually owed
    (when (= :tax (:type current-cell))
      (:cost current-cell))))


;; ======= Property Management =================

(defn owned-properties
  "Given a game-state, return the set of owned property ids/names"
  ;; TODO - Ignore bankrupt players?
  [game-state]
  (->> game-state :players
       (mapcat :properties)
       (map key) set))

;; TODO - Is a better name owned-properties-details?
;; TODO - this seems to be the most called, and most time spent of the functions ...
(defn- *owned-property-details
  "Given a game-state, return the set of owned property
  details as a map of prop ID -> owned state with attached:
    - owner ID
    - monopoly? (if a street type, does owner have a monopoly)
    - property details/definition"
  ([game-state]
   (*owned-property-details game-state
                            (:players game-state)))
  ([{:keys [board]}
   players]
  (let [;; Prep static aggs
        street-group->count
        (street-group-counts board)
        ;; First pass, basic assembly of info
        props (mapcat (fn [player]
                        (map (fn [[prop-name owner-state]]
                               [prop-name
                                (assoc owner-state
                                       :owner (:id player)
                                       ;; TODO - Performance: cache key by property names for this
                                       :def (->> board :properties
                                                 (filter #(= prop-name (:name %)))
                                                 first))])
                             (:properties player)))
                      players)]
    ;; Derive/Attach monopoly status to each street type
    (->> props
         (map (fn [[prop-name deets]]
                (let [type-owned        (->> props (map second)
                                             (filter #(= (:owner %) (:owner deets) ))
                                             (map :def)
                                             (filter #(= (:type %) (-> deets :def :type) )))
                      type-owned-count  (count type-owned)
                      group-owned-count (->> type-owned
                                             (filter #(= (:group-name %) (-> deets :def :group-name)))
                                             count)]
                  [prop-name
                   (assoc deets
                          :type-owned-count type-owned-count
                          :group-owned-count group-owned-count
                          :group-monopoly?
                          (= group-owned-count
                             ;; Number required for a monopoly, by this group
                             ;; TODO - this assumes that only one type of prop has a group name
                             (street-group->count (-> deets :def :group-name))))])))
         (into {})))))

(def owned-property-details
  ;; LRU cache with max 1000 entries to prevent memory leaks
  (memo/lru *owned-property-details :lru/threshold 1000))

(defn player-property-sell-worth
  "Given a game-state, and a player ID, calculate and return the player's \"sell worth\" as a cash integer.
     Mortgaged properties = 0
     Regular properties = [ their mortgage value ]
     Resources = [ half the price they were bought at ]"
  [game-state player-id]
  (let [player (player-by-id game-state player-id)
        props  (owned-property-details game-state [player])]
    (->> props
         vals
         (map (fn [{:keys [def status house-count] :as prop}]
                (cond
                  ;; Mortgaged properties aren't "worth"
                  ;; anything more in a bankruptcy situation
                  (= :mortgaged status)
                  0
                  ;; Half face value + Half house value
                  (= :paid status)
                  (+ (:mortgage def)
                     (half (* house-count (:house-price def 0))))
                  ;; Just in case we have an invalid value
                  :else
                  (throw (ex-info "Unknown property status" {:owned-property prop})))))
         (apply +))))


;; TODO - Need to do a special transfer/acquisition workflow for mortgaged properties,
;;        currently we just assume it's owned outright
(defn- apply-auction-property-workflow
  "Given a game-state and a property name, carry out the auction workflow by sequentially invoking the 'auction-bid' player decision method, per player, until a winner is found. Starts by establishing a random order to call players in, and continues that order in a loop. When a winner is found, the game-state is updated to reflect the purchase, indicating it was purchased via auction.

  NOTE - This does not handle correct mortgaged property rules yet"
  [game-state property]
  (let [;; Get property definition from board
        property-def (->> game-state :board :properties
                          (filter #(= property (:name %)))
                          first)
        ;; Establish random player call ordering (exclude current player who declined)
        current-player-id (get-in game-state [:current-turn :player])
        auction-players (->> game-state :players
                             (filter #(= :playing (:status %)))
                             (remove #(= current-player-id (:id %)))
                             (map-indexed #(assoc %2 :player-index %1))
                             shuffle)
        ;; Determine bid increment (default to $10 if no rules specified)
        bid-increment (get-in game-state [:board :rules :auction-increment] 10)
        starting-bid bid-increment

        ;; Record that auction was initiated (regardless of outcome)
        game-state-with-auction-start
        (append-tx game-state {:type :auction-initiated
                              :property property
                              :declined-by current-player-id
                              :eligible-bidders (map :id auction-players)
                              :starting-bid starting-bid
                              :participant-count (count auction-players)})]

    ;; Start auction loop
    (loop [active-players auction-players
           highest-bid 0
           highest-bidder nil
           current-bid starting-bid]

      (if (empty? active-players)
        ;; No more bidders - auction complete
        (if highest-bidder
          ;; Someone won the auction
          (-> game-state-with-auction-start
              ;; Add property to winner
              (update-in [:players (:player-index highest-bidder) :properties]
                         assoc property {:status :paid :house-count 0})
              ;; Deduct winning bid from winner
              (update-in [:players (:player-index highest-bidder) :cash]
                         - highest-bid)
              ;; Record auction completion transaction
              (append-tx {:type :auction-completed
                          :property property
                          :winner (:id highest-bidder)
                          :winning-bid highest-bid
                          :participants (map :id auction-players)}))
          ;; No one bid - property goes to bank, record the outcome
          (append-tx game-state-with-auction-start
                     {:type :auction-completed
                      :property property
                      :winner :bank
                      :winning-bid 0
                      :participants (map :id auction-players)
                      :outcome :no-bids}))

        ;; Continue auction - get next player's bid
        (let [current-player (first active-players)
              remaining-players (rest active-players)

              ;; Invoke player's auction-bid decision
              decision ((:function current-player)
                        game-state
                        :auction-bid
                        {:property property-def
                         :highest-bid highest-bid
                         :highest-bidder (when highest-bidder (:id highest-bidder))
                         :required-bid current-bid})]

          (case (:action decision)
            ;; Player declines - remove from active players
            :decline
            (recur remaining-players highest-bid highest-bidder current-bid)

            ;; Player bids - validate and update if valid
            :bid
            (let [bid-amount (:bid decision)]
              (if (and bid-amount
                       (>= bid-amount current-bid)
                       (>= (:cash current-player) bid-amount))
                ;; Valid bid - update highest bid and continue
                (recur (conj (vec remaining-players) current-player)
                       bid-amount
                       current-player
                       (+ bid-amount bid-increment))
                ;; Invalid bid - treat as decline
                (recur remaining-players highest-bid highest-bidder current-bid)))

            ;; Unknown action - treat as decline
            (recur remaining-players highest-bid highest-bidder current-bid)))))))

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
         :as   player} (current-player game-state)
        current-cell   (get-in board [:cells cell-residency])
        ;; Get the definition of the current cell *if* it's a property
        property       (and (-> current-cell :type (= :property))
                            (->> board :properties
                                 (filter #(= (:name %) (:name current-cell)))
                                 first))
        taken          (owned-properties game-state)]
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
          ;; NOTE - validation for this cash payment above (not a REQUISITE-PAYMENT)
          (update-in [:players player-index :cash]
                     - (:price property))
          ;; Track transaction
          ;; TODO - maybe add an indicator that this was a purchase via direct landing on? (vs auction or other acquisition)
          (append-tx {:type     :purchase
                      :player   (:id player)
                      :property (:name property)
                      :price    (:price property)}))

      ;; Apply auction workflow
      (apply-auction-property-workflow game-state (:name property)))))


;; ----- Rent ------------------------

(defmulti calculate-rent
  "Given a property id/name, owned property details state/map, and the previous dice role;
  calculate the amount of rent that would be owed."
  ;; TODO - list assmptions: we don't know who rolled, assuming it's not the owner. We need a valid dice roll
  (fn [prop owned-props _dice-roll]
    (get-in owned-props [prop :def :type])))

(defmethod calculate-rent :street
  [prop owned-props _dice-roll]
  (let [;; Pull out relavant info
        {houses               :house-count
         monopoly?            :group-monopoly?
         {:keys [rent group-rent
                 house-rent]} :def}
        (get owned-props prop)]
    (cond
      ;; Monoply + houses owned
      ;; TODO - what to do if def doesn't include required info?
      (and monopoly?
           (< 0 houses))
      (nth house-rent (dec houses))
      ;; Monoply, no houses
      monopoly? group-rent
      ;; Base rent rate
      :else     rent)))

(defmethod calculate-rent :utility
  [prop owned-props dice-roll]
  ;; TODO - should we assume the util is owned? Or do we need to check that, and return 0?
  (let [deets       (get owned-props prop)
        ;; Total number of utils owned by owner
        owned-count (:type-owned-count deets)
        ;; Dice multipier, based on number of utils owned
        ;; TODO - what to do if def doesn't include required info?
        multiplier  (-> deets :def :rent
                        (nth (dec owned-count))
                        :dice-multiplier)]
    ;; TODO - it shouldn't happen, but what if no dice rolls exist?
    (* multiplier (sum dice-roll))))

(defmethod calculate-rent :railroad
  [prop owned-props _dice-roll]
  (let [deets       (get owned-props prop)
        owned-count (:type-owned-count deets)]
    ;; TODO - what to do if def doesn't include required info?
    (-> deets :def
        :rent
        (nth (dec owned-count)))))

(defn rent-owed?
  "Given a game-state, when rent is owed by the current player
  on their current spot in the game, return the cash amount due,
  and the id of the player owed. Returns nil if no rent is due."
  ;; TODO - Assumes current player has rolled dice at least once
  [{:keys [board current-turn]
    :as   game-state}]
  (let [{:keys [cell-residency]
         :as   player} (current-player game-state)
        ;; Get the definition of the current cell *if* it's a property
        current-cell   (get-in board [:cells cell-residency])
        on-prop        (and (-> current-cell :type (= :property))
                            (->> board :properties
                                 (filter #(= (:name %) (:name current-cell)))
                                 first))
        owned-props    (owned-property-details game-state)
        owned-prop     (get owned-props (:name on-prop))]
    ;; Only return if rent is actually owed
    (when (and
            ;; ..is on an owned property
            owned-prop
            ;; ..is paid, not morgaged
            (= :paid (:status owned-prop))
            ;; ..is owned by someone else
            (not= (:owner owned-prop)
                  (:id player)))
      ;; Return the owner ID and rent amount owed
      [(:owner owned-prop)
       (calculate-rent (:name on-prop)
                       owned-props
                       (-> current-turn :dice-rolls last))])))


;; ----- Building Management ---------

;; - Of the (a) paid (b) street properties that (c) have a monopoly,
;;   is the total group house count less than [5 * group count]?

;; - Of all the potential places to buy a house
;;   (foreach prop owned, how many more houses can be built, represented by a dollar amount),
;;   is the cheapest one in the player's affordability?

;; Caveats
;; - You need to build houses across props evenly
;;   - Can't build more than 1+(sister prop with least amount of house count)
;; - There is a certain "stock" or "inventory" of houses and
;;   hotels, that is allowed to be bought from the bank,
;;   and be in play at a given time
;;   - This logic isn't implemented at this moment
;; - Are hotels any different from houses?
;;   - In this engine, a hotel is just the last house that can be bought..
;;   - The only difference _will_ be, when implementing the building inventory logic

;; TODO - Useful fns based on this input
;;        - Filter, in order, which potential purchases can be afforded with $x dollars
(defn potential-house-purchases
  "Given a player's owned properties details collection,
  return a list potential building purchase tuples.
  Form of: [group property price]"
  [player-owned-props]
  (let [;; Agg current min count of houses by group name,
        ;; to enforce even/distributed building
        group->min (->> player-owned-props
                        (map #(vector (-> % :def :group-name)
                                      (:house-count %)))
                        (group-by first)
                        (map (fn [[g pairs]]
                               [g (apply min (map second pairs))]))
                        (into {}))]
    (->> player-owned-props
         ;; Filter out mortgaged properties
         (filter #(= :paid (:status %)))
         ;; Only properties that have a monopoly
         (filter :group-monopoly?)
         ;; Filter out ones that already have a hotel (5 max/houses)
         (filter #(> 5 (:house-count %)))
         ;; Filter out "uneven" properties (more than the min count already)
         (filter #(= (:house-count %)
                     (group->min (-> % :def :group-name))))
         ;; Represent as a tuple
         (map (fn [deets]
                ;; TODO - At this point this is probably best as a map ..
                ;; TODO - We could also include the potential total count this purchase would bring to the prop
                [(-> deets :def :group-name)
                 (-> deets :def :name)
                 (-> deets :def :house-price)])))))

(defn can-buy-house?
  "Given a game-state, return wether or not the
  current player is in the position to buy a house or not [bool].
  Considers all game rules, along with current liquid cash.

  Optionally provide a property ID/name, to determine if a house
  can be purchased on that property, by the current player."
  ;; 1-Arity, any potential property
  ([game-state]
   (let [{player-id :id
          cash      :cash} (current-player game-state)
         potential         (->> game-state
                                owned-property-details
                                (map second)
                                (filter #(= player-id (:owner %)))
                                potential-house-purchases)
         cheapest          (->> potential
                                (sort-by #(nth % 2))
                                first)]
     ;; Do they have purchase potential, and can they
     ;; afford the cheapest single potential purchase?
     (and (< 0 (count potential))
          (>= cash (nth cheapest 2)))))
  ;; 2-Arity, check single property
  ;; TODO - There's a lot of duplication in these impls
  ([game-state prop-name]
   (let [{player-id :id
          cash      :cash} (current-player game-state)
         potential         (->> game-state
                                owned-property-details
                                (map second)
                                (filter #(= player-id (:owner %)))
                                potential-house-purchases)
         single-prop       (->> potential
                                (filter #(= prop-name (second %)))
                                first)]
     ;; Can they build on this single property,
     ;; and can they afford it?
     (and single-prop
          (>= cash (nth single-prop 2))))))

(defn- can-sell-house?
  [game-state prop-name]
  ;; TODO - the player in question should probably be passed as a prop
  (let [{player-id :id}
        (current-player game-state)
        ;; All properties owned
        owned       (->> game-state
                         owned-property-details
                         (map second)
                         (filter #(= player-id (:owner %))))
        ;; The property in question
        single-prop (->> owned
                         (filter #(= prop-name (-> % :def :name)))
                         first)
        ;; Current max houses owned in this group
        house-max   (->> owned
                         (filter #(= (-> % :def :group-name)
                                     (-> single-prop :def :group-name)))
                         (map :house-count)
                         (apply max))]
    ;; Itemized validation
    (cond
      ;; Ensure property ownership
      (not single-prop)
      [false :property-ownership]

      ;; Ensure home ownership
      (= 0 (:house-count single-prop))
      [false :house-inventory]

      ;; Ensure even house distribution
      ;; Can only sell if this property HAS the max house count in its group
      (not= house-max (:house-count single-prop))
      [false :even-house-distribution]

      ;; All good!
      :else [true nil])))

(defn apply-house-purchase
  "Given a game state and property, apply purchase of a single house
  for current player on given property. Validates and throws if house
  purchase is not allowed by game rules."
  [game-state property-name]
  (let [{player-id :id
         pidx      :player-index}
        (current-player game-state)
        ;; Get property definition
        property (->> game-state :board :properties
                      (filter #(= :street (:type %)))
                      (filter #(= property-name (:name %)))
                      first)]

    ;; Validation
    ;; TODO - Should this be done by the caller?
    (when-not (can-buy-house? game-state property-name)
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

(defn apply-house-sale
  "Given a game state and a property, apply the sell of a single house for
  current player on given property. Validates and throws if house sell is
  not allowed by game rules."
  [game-state property-name]
  ;; TODO - the player in question should probably be passed as a prop
  (let [{player-id :id
         pidx      :player-index}
        (current-player game-state)
        ;; Get property definition
        property (->> game-state :board :properties
                      (filter #(= :street (:type %)))
                      (filter #(= property-name (:name %)))
                      first)
        proceeds (half (:house-price property))]

    ;; Validation
    (let [valid? (can-sell-house? game-state property-name)]
      (when-not (first valid?)
        (throw (ex-info "Player decision not allowed"
                        {:action   :sell-house
                         :player   player-id
                         :property property-name
                         :reason   (second valid?)}))))

    ;; Apply the purchase
    ;; TODO - when we have a bank "house inventory", return houses back to it
    (-> game-state
        ;; Dec house count in player's owned collection
        (update-in [:players pidx :properties
                    property-name :house-count]
                   dec)
        ;; Add back money, half of original price
        (update-in [:players pidx :cash]
                   + proceeds)
        ;; Track transaction
        (append-tx {:type     :sell-house
                    :player   player-id
                    :property property-name
                    ;; TODO - is there a better, more consistent, key name for this?
                    :proceeds proceeds}))))

(defn apply-property-mortgage
  "Given a game-state and a property, apply the mortgaging
  of said property for current player. Validates and throws
  if current player cannot perform operation."
  [game-state property-name]
  ;; TODO - the player in question should probably be passed as a prop?
  (let [{player-id :id
         pidx      :player-index
         :as       player}
        (current-player game-state)
        ;; Get property definition
        property       (->> game-state :board :properties
                            (filter #(= property-name (:name %)))
                            first)
        mortgage-price (:mortgage property)
        prop-state     (get-in player [:properties property-name])]

    ;; Validate - make sure they own it
    (when (not prop-state)
      (throw (ex-info "Player decision not allowed"
                      {:action   :sell-house
                       :player   player-id
                       :property property-name
                       :reason   :property-not-owned})))
    ;; Validate - make sure it doesn't have houses
    (when (->> prop-state :house-count (< 0))
      (throw (ex-info "Player decision not allowed"
                      {:action   :sell-house
                       :player   player-id
                       :property property-name
                       :reason   :property-has-buildings})))
    ;; Validate - make sure it's not already mortgaged
    (when (not= :paid (:status prop-state))
      (throw (ex-info "Player decision not allowed"
                      {:action   :sell-house
                       :player   player-id
                       :property property-name
                       :reason   :property-not-paid})))
    ;; Apply the flip
    (-> game-state
        ;; Update to mortgaged status in player state
        (assoc-in [:players pidx :properties
                   property-name :status]
                  :mortgaged)
        ;; Pay player mortgage amount
        (update-in [:players pidx :cash]
                   + mortgage-price)
        ;; Track transaction
        (append-tx {:type     :mortgage-property
                    :player   player-id
                    :property property-name
                    :proceeds mortgage-price}))))
