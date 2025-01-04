(ns jmshelby.monopoly.util
  (:require [clojure.set :as set]))

;; ======= General =============================

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

;; TODO - could easily memoize this
(defn street-group-counts
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

(defn current-player
  "Given a game-state, return the current player. Includes the index of the player"
  [{:keys [players
           current-turn]}]
  (->> players
       ;; Attach ordinal
       (map-indexed (fn [idx p] (assoc p :player-index idx)))
       (filter #(= (:id %) (:player current-turn)))
       first))


;; ======= Property Management =================

(defn owned-properties
  "Given a game-state, return the set of owned property ids/names"
  [game-state]
  (->> game-state :players
       (mapcat :properties)
       (map key) set))

;; TODO - Is a better name owned-properties-details?
(defn owned-property-details
  "Given a game-state, return the set of owned property
  details as a map of prop ID -> owned state with attached:
    - owner ID
    - monopoly? (if a street type, does owner have a monopoly)
    - property details/definition"
  [{:keys [board]
    :as   game-state}]
  (let [;; Prep static aggs
        street-group->count (street-group-counts board)
        ;; First pass, basic assembly of info
        props               (mapcat (fn [player]
                                      (map (fn [[prop-name owner-state]]
                                             [prop-name
                                              (assoc owner-state
                                                     :owner (:id player)
                                                     :def (->> board :properties
                                                               (filter #(= prop-name (:name %)))
                                                               first))])
                                           (:properties player)))
                                    (:players game-state))]
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
         (into {}))))

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
  (->> player-owned-props
       ;; Filter out mortgaged properties
       (filter #(= :paid (:status %)))
       ;; Only properties that have a monopoly
       (filter :group-monopoly?)
       ;; Filter out ones that already have a hotel (5 max/houses)
       (filter #(> 5 (:house-count %)))
       ;; TODO - Filter to ensure *even* building is happening
       (mapcat (fn [deets]
                 ;; Expand potential house purchases
                 (repeat (- 5 (:house-count deets))
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
