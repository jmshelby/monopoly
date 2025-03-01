(ns jmshelby.monopoly.players.dumb
  (:require [clojure.set :as set]
            [jmshelby.monopoly.util :as util]))

;; Right now, just the fn/methods that act as a player logic "function"

;; For now we just have some dumb logic, to help
;; develop and test the engine until it's done

(defn- percent-props-owned
  [game-state]
  (let [total (->> game-state :board :properties
                   (map :name) count)
        owned (->> game-state
                   util/owned-properties
                   count)]
    (/ owned total)))

(defn trade-side-value
  "Given a game board, player, and resources from one
  side of a trade proposal, return the total \"real\"
  value of all resources."
  [board player resources]
  (letfn [(prop-def [id]
            (->> board :properties
                 (filter #(= id (:name %)))
                 first))]
    (->> resources
         ;; Expand each sub-resource into it's value
         (mapcat (fn [[type resource]]
                   (case type
                     ;; Simply the cash value
                     :cash  [resource]
                     ;; Each card is $50
                     ;; TODO - Need to lookup value based on card.retain/use
                     :cards (repeat (count resource) 50)
                     ;; Each property current "real" value
                     :properties
                     (map (fn [prop-name]
                            (let [prop       (prop-def prop-name)
                                  prop-state (get-in player [:properties prop-name])]
                              (case (:status prop-state)
                                :paid      (:price prop)
                                :mortgaged (:mortgage prop))))
                          resource))))
         ;; Just sum from here
         (apply +))))

(defn proposal-benefit
  "Given a game-state and proposal map, return the ratio
  benefit/gain based on value of resources. Mortgaged
  properties are worth half (although we should probably
  subtract 10% for real value)."
  [game-state proposal]
  (let [board       (:board game-state)
        asking      (:trade/asking proposal)
        offering    (:trade/offering proposal)
        to-player   (util/player-by-id game-state
                                       (:trade/to-player proposal))
        from-player (util/player-by-id game-state
                                       (:trade/from-player proposal))
        ask-value   (trade-side-value board to-player asking)
        offer-value (trade-side-value board from-player offering)]
    ;; Just offerring over asking
    (/ offer-value ask-value)))

(defn find-desired-properties
  [game-state player]
  (let [player-id    (:id player)
        group->count (util/street-group-counts (:board game-state))
        ;; Map group name -> set of prop names
        group->names (->> game-state :board
                          :properties
                          (filter #(= :street (:type %)))
                          (group-by :group-name)
                          (map (fn [[k coll]] [k (->> coll (map :name) set)]))
                          (into {}))
        owned-props  (util/owned-property-details game-state)
        taken-props  (->> owned-props
                          (remove #(= player-id (:owner %)))
                          (into {}))]
    ;; Of all owned props
    (->> owned-props vals
         ;; - owned by us
         (filter #(= player-id (:owner %)))
         ;; - just streets
         (filter #(= :street (-> % :def :type)))
         ;; - not monopolized
         (remove :group-monopoly?)
         ;; - Only one left to get a monopoly
         ;;   filter (group-owned / group-total) >= 1/2
         (filter (fn [prop]
                   (<= 1/2
                       (/ (:group-owned-count prop)
                          (group->count (-> prop :def :group-name))))))
         ;; - grouped by group name
         (group-by #(-> % :def :group-name))
         ;; - mapcat -> missing prop(s) from group, if owned by another player
         ;;   (find the desired props, based on groups above)
         (mapcat (fn [[group-name props]]
                   (let [own      (->> props
                                       (map (comp :name :def))
                                       set)
                         all      (group->names group-name)
                         want     (first (set/difference all own))
                         eligible (taken-props want)]
                     (when eligible
                       [eligible])))))))

(defn find-proposable-properties
  [game-state player target-value]
  (let [player-id    (:id player)
        group->count (util/street-group-counts (:board game-state))
        ;; Map group name -> set of prop names
        owned-props  (util/owned-property-details game-state)]
    ;; Of all owned props
    (->> owned-props vals
         ;; - owned by us
         (filter #(= player-id (:owner %)))
         ;; - Groups that we only own 1 spot of,
         ;;   or any non-street type
         ;;   -> filter (group-owned / group-total) < 1/2
         ;;    OR utils OR railroads
         (filter (fn [prop]
                   (let [type (-> prop :def :type)]
                     (or (= :utility type)
                         (= :railroad type)
                         (> 1/2
                            (/ (:group-owned-count prop)
                               (group->count (-> prop :def :group-name))))))))
         ;; - attach a value
         ;;   TODO - using mortgage value if applicable
         (map #(assoc % :value (-> % :def :price)))
         ;; - sorted by value
         ;;   TODO - THE BIG QUESTION!! SHOULD IT BE ASC OR DESC
         (sort-by :value)
         ;; (sort-by :value rcompare)
         ;; - [take until sum value is more than desired/target prop]
         (reduce (fn [acc prop]
                   ;; Is the sum total value of acc'd props more than the target?
                   (if (> target-value (reduce + (map :value acc)))
                     (conj acc prop)
                     ;;   TODO - Would be nice to use a reduce/variant that can terminate early
                     acc))
                 []))))

(defn proposal?
  "Given a game-state and player, return the best current
  proposal available, _if_ it's a smart time to propose one."
  ;; Street Properties:
  ;; - If we own 50% or more of a street group (but not all),
  ;;   AND someone else owns a property of the same street...
  ;;       -> Attempt to build offer for that "target" property
  ;;           - From list of our "undesirable" properties, ordered
  ;;             by current value (taking mortgaged into account),
  ;;             take enough props to reach a value that is more
  ;;             than the face value of the "target" property.
  ;;             - "undesirable" properties:
  ;;               - utils
  ;;               - railroads
  ;;               - we own less than 50% of the street group
  ;;       -> Make sure attempt offer has never been made before,
  ;;          from transaction history
  [game-state player]
  ;; Very dumb initial logic
  ;;  NOTE: no cash or jail free cards involved yet
  (let [;; Get a possible single desired property
        target-prop (->> (find-desired-properties game-state player)
                         ;; TODO
                         ;; - sorted by something?
                         ;;   (if we randomize this, it can auto
                         ;;    rotate through multiple possibilities)
                         ;; - take first
                         first)]

    (when target-prop
      (let [;; Get props we can offer
            ;; _         (println (:id player) ": Going after prop: " target-prop)
            sacrifice (find-proposable-properties
                        game-state player
                        (-> target-prop :def :price))

            ;; Assemble a proposal map, from/to player ids, asking/offering maps
            offer {:trade/to-player (:owner target-prop)
                   ;; Only one target property we're going after
                   :trade/asking    {:properties #{(-> target-prop :def :name)}}
                   ;; Only offering set of properties
                   :trade/offering  {:properties (set (map (comp :name :def) sacrifice))}}

            ;; Make sure we haven't offered this before
            prospective-tx {:type     :trade
                            :status   :proposal
                            :to       (:trade/to-player offer)
                            :from     (:id player)
                            :asking   (:trade/asking offer)
                            :offering (:trade/offering offer)}]
        ;; Should we return this proposal?
        ;;  - should just be a set intersection, between transactions and assembled proposal
        (when-not ((set (:transactions game-state))
                   prospective-tx)
          offer)))))

;; TODO - multimethods..
(defn decide
  [game-state method params]
  (let [{my-id :id
         cash  :cash
         :as   player}
        (util/current-player game-state)]
    (case method

      ;; Dumb, always decline these actions
      :acquisition {:action :decline}
      :auction-bid {:action :decline}

      ;; A trade proposal offered to us
      :trade-proposal
      ;; Real simple, no worry about our state or the other player's state.
      ;;   - Accept if offerred resources value (taking mortgaged into account)
      ;;     add up to at least X times the amount of the asking resources
      {:action
       (let [gain (proposal-benefit game-state params)]
         (cond
           ;; For now we'll go with a *1.5* times minimum gain
           (<= 1.5 gain) :accept
           :else         :decline))}

      ;; Dumb, always buy a property if we can
      ;; TODO - maybe keep _some_ money minimum?
      :property-option {:action :buy}

      ;; Turn Logic...
      :take-turn
      (cond

        ;; First, check if we can roll, and do it
        (-> params :actions-available :roll)
        {:action :roll}

        ;; Check to see if we can AND should make an offer
        ;; TODO - This could return random results and should
        ;;        be called only once per decision
        (and (-> params :actions-available :trade-proposal)
             (proposal? game-state player))

        ;; When we're ready to send a proposal
        ;; {:action                   :trade-proposal
        ;;  :trade/to-player "A"
        ;;  ;; Note - You'd never have :cash in both asking+offering
        ;;  ;;        I guess you _could_, but there'd be no point, so we should restrict it
        ;;  ;; Note - You'd never have :cards in both asking+offering (in standard rules board)
        ;;  ;; Note - You can (and often will) have properties in both asking+offering
        ;;  ;; Note - A single player _could_ have 2 get out of jail free cards and offer them
        ;;  :trade/asking    {;; Cash dollar amount > 0
        ;;                    :cash       123
        ;;                    ;; Full card definitions
        ;;                    :cards      #{}
        ;;                    ;; Just names of properties
        ;;                    :properties #{}}
        ;;  :trade/offering  {:cash       123
        ;;                    :cards      #{}
        ;;                    :properties #{}}}

        (into {:action :trade-proposal}
              (proposal? game-state player))

        ;; OR, if we are in jail and have a free out card
        ;; THEN use it
        (-> params :actions-available :jail/bail-card)
        {:action :jail/bail-card}

        ;; OR, if we are in jail and can pay bail,
        ;; AND there's more than 25% of prop for sell,
        ;; THEN post bail
        (and (-> params :actions-available :jail/bail)
             (> 0.75 (percent-props-owned game-state)))
        {:action :jail/bail}

        ;; OR, if we are in jail and can roll for doubles, do that
        (-> params :actions-available :jail/roll)
        {:action :jail/roll}

        ;; Next, if we can buy a house,
        ;; and have more than $40 left (yes very dumb)
        (and (-> params :actions-available :buy-house)
             (> cash 40))
        {:action :buy-house
         :property-name
         (let [potential (->> game-state
                              util/owned-property-details
                              (map second)
                              (filter #(= my-id (:owner %)))
                              util/potential-house-purchases)
               cheapest  (->> potential
                              (sort-by #(nth % 2))
                              first)]
           ;; Property ID/Name, tuple 2 pos
           (second cheapest))}

        ;; No other options, end turn
        ;; TODO - soon, "done" might not be available in all cases
        :else {:action :done}))))
