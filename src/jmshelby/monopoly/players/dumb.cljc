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
         ;;   filter (group-owned / group-total) >= 0.5
         (filter (fn [prop]
                   (<= 0.5
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
         ;;   -> filter (group-owned / group-total) < 0.5
         ;;    OR utils OR railroads
         (filter (fn [prop]
                   (let [type (-> prop :def :type)]
                     (or (= :utility type)
                         (= :railroad type)
                         (> 0.5
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
                       (-> target-prop :def :price))]

        ;; Only create an offer if we have something to sacrifice
        (when (seq sacrifice)
          (let [;; Assemble a proposal map, from/to player ids, asking/offering maps
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
              offer)))))))

;; TODO - multimethods..
(defn decide
  [game-state player-id method params]
  (let [{my-id :id
         cash  :cash
         :as   player}
        (util/player-by-id game-state player-id)]
    (case method

      ;; Simple auction bidding logic
      :auction-bid
      (let [{:keys [property _highest-bid required-bid]} params
            cash-reserve 100 ;; Keep some cash on hand
            property-value (when property (:price property))
            ;; Determine if this property is valuable to us
            property-worth-it? (and
                                ;; Property exists and has a price
                                property-value
                                ;; Don't bid more than property's face value
                                (<= required-bid property-value)
                                ;; Make sure we can afford the bid AND maintain reserve
                                (>= cash (+ required-bid cash-reserve)))]
        (if property-worth-it?
          ;; Bid just the current asking price
          {:action :bid
           :bid required-bid}
          ;; Not worth it for us
          {:action :decline}))

      ;; Mortgaged property acquisition decisions
      ;; Player must choose between :pay-mortgage (immediate unmortgage) or :pay-interest (10% now, pay full later)
      :acquisition
      (let [{:keys [properties]} params
            player-cash (:cash player)
            cash-reserve 200 ;; Keep some cash on hand

            ;; For each mortgaged property, decide whether to pay mortgage or interest
            decisions (into {}
                            (map (fn [[prop-name prop-info]]
                                   (let [{:keys [unmortgage-cost interest-fee]} prop-info
                                        ;; Choose immediate unmortgage if we have enough cash after reserves
                                         can-afford-unmortgage? (>= (- player-cash unmortgage-cost) cash-reserve)
                                         choice (if can-afford-unmortgage?
                                                  :pay-mortgage  ;; Immediate unmortgage
                                                  :pay-interest)] ;; Pay 10% now, deal with it later
                                     [prop-name choice]))
                                 properties))]
        decisions)

      ;; When we owe more cash than we have, we are
      ;; forced to sell off our assets until we have
      ;; enough cash.
      ;; Params: {:amount 999} ;; where amount is the remaining amount needed to raise
      :raise-funds
      (let [owned-props (->> (util/owned-property-details game-state)
                             vals
                             (filter #(= my-id (:owner %))))
            ;; First try to sell houses (gives back 50% of house cost)
            houses-to-sell (->> owned-props
                                (filter #(> (:house-count %) 0))
                                (sort-by :house-count >)
                                first)
            ;; Then try to mortgage unmortgaged properties
            props-to-mortgage (->> owned-props
                                   (filter #(= :paid (:status %)))
                                   (sort-by #(-> % :def :mortgage) >)
                                   first)]
        (cond
          houses-to-sell
          {:action :sell-house
           :property-name (-> houses-to-sell :def :name)}

          props-to-mortgage
          {:action :mortgage-property
           :property-name (-> props-to-mortgage :def :name)}

          ;; If no houses to sell or properties to mortgage, we're bankrupt
          :else
          (throw (ex-info "Player cannot raise funds - no assets to liquidate"
                          {:player-id my-id
                           :amount-needed (:amount params)}))))

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

      ;; Dumb, always buy a property if we can,
      ;; we won't be called unless we can afford it
      ;; TODO - maybe keep _some_ money minimum?
      :property-option {:action :buy}

      ;; Turn Logic...
      :take-turn
      ;; Cache property lookups and pre-compute action candidates
      (let [owned-props (->> (util/owned-property-details game-state)
                             vals
                             (filter #(= my-id (:owner %))))
            ;; Pre-compute candidates for each action type
            house-candidates (when (-> params :actions-available :buy-house)
                               (->> owned-props
                                    util/potential-house-purchases
                                    (sort-by #(nth % 2))))
            unmortgage-candidates (when (-> params :actions-available :unmortgage-property)
                                    (->> owned-props
                                         (filter #(= :mortgaged (:status %)))
                                         (filter #(let [property (-> % :def)
                                                        unmortgage-cost #?(:clj (-> property :mortgage (* 1.1) Math/ceil int)
                                                                           :cljs (-> property :mortgage (* 1.1) js/Math.ceil int))]
                                                    (>= cash unmortgage-cost)))
                                         (sort-by #(-> % :def :mortgage))))
            ;; Pre-compute properties that we could mortgage, if needed
            ;; NOTE - commenting this out now, need to determine if it's necessary
            ;; mortgage-candidates (when (-> params :actions-available :mortgage-property)
            ;;                       (->> owned-props
            ;;                            (filter #(= :paid (:status %)))
            ;;                            (filter #(= 0 (:house-count %)))
            ;;                            (sort-by #(-> % :def :mortgage) <)))
            ;; Pre-compute sellable house candidates using owned-props data
            ;; NOTE - commenting this out now, need to determine if it's necessary
            ;; sellable-house-candidates (when (-> params :actions-available :sell-house)
            ;;                             (->> owned-props
            ;;                                  (filter #(> (:house-count %) 0))
            ;;                                  (filter #(first (util/can-sell-house? game-state (-> % :def :name))))
            ;;                                  (map #(-> % :def :name))))
            ]
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
          ;; and have more than $40 left (yes very dumb, and arbitrary)
          (and house-candidates
               (> cash 40))
          {:action :buy-house
           :property-name (-> house-candidates first second)}

          ;; Consider unmortgaging properties if we have excess cash
          (and unmortgage-candidates
               (> cash 300)) ;; Only if we have plenty of cash
          {:action :unmortgage-property
           :property-name (-> unmortgage-candidates first :def :name)}

          ;; Consider selling houses if we're getting low on cash (but not in raise-funds)
          ;; NOTE - commenting this out now, need to determine if it's necessary
          ;; (and sellable-house-candidates
          ;;      (< cash 100))
          ;; {:action :sell-house
          ;;  :property-name (first sellable-house-candidates)}

          ;; Consider mortgaging properties if we're low on cash
          ;; NOTE - commenting this out now, need to determine if it's necessary
          ;; (and mortgage-candidates
          ;;      (< cash 50))
          ;; {:action :mortgage-property
          ;;  :property-name (-> mortgage-candidates first :def :name)}

          ;; No other options, end turn
          ;; TODO - soon, "done" might not be available in all cases
          :else {:action :done})))))