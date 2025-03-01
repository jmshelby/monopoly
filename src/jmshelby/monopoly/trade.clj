(ns jmshelby.monopoly.trade
  (:require [clojure.set :as set
             :refer [union subset? difference]]
            [jmshelby.monopoly.util :as util]))

(defn- exchange-properties
  ;; TODO - add notes, emphasising that this doesn't validate the exchange
  [game-state from-pidx to-pidx prop-names]
  (let [;; Get player maps
        ;; to-player   (get-in game-state [:players to-pidx])
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
        (update-in [:players to-pidx :properties]
                   ;; Just a conj of existing prop states
                   ;; into player's own property state map
                   conj prop-states))))

(defn- apply-trade
  "Given a game state and a proposal, exchange the resources
  in the accepted proposal, between the parties respectively."
  [game-state proposal]
  (let [;; Get player indexes
        {pidx-proposer
         :player-index} (util/player-by-id
                          game-state (:trade/from-player proposal))
        {pidx-acceptor
         :player-index} (util/player-by-id
                          game-state (:trade/to-player proposal))
        ;; Pull out both sides of the proposal and ensure
        ;; all resource keys are available with empty values
        stub-defaults   (fn [p] (merge {:cash       0
                                        :cards      #{}
                                        :properties #{}}
                                       p))
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

(defn- append-tx
  [game-state status proposal]
  (util/append-tx game-state
                  {:type     :trade
                   ;; TODO - or :trade/status?
                   ;; TODO - or "stage"??
                   :status   status
                   :to       (:trade/to-player proposal)
                   :from     (:trade/from-player proposal)
                   :asking   (:trade/asking proposal)
                   :offering (:trade/offering proposal)}))

(defn validate-side
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

(defn apply-proposal
  "Given a game-state, and a player's trade proposal, invoke's
  proposal to other player targeted, and applies the result to
  the game state. A decline resulting in transactions of the
  events, and an accept resulting in both an interchange of the
  involved resources between the two players.
  TODO - counter-proposal logic"
  [game-state proposal]
  (let [asking      (:trade/asking proposal)
        offering    (:trade/offering proposal)
        to-player   (util/player-by-id game-state
                                       (:trade/to-player proposal))
        from-player (util/player-by-id game-state
                                       (:trade/from-player proposal))]
    ;; Validation
    ;; TODO - maybe we just assume it's valid and the caller can validate first...
    (when-not
        (and
          ;; The current player is offering
          ;; TODO - Should *we* really care about this?
          (= (:id from-player)
             (:trade/from-player proposal))
          ;; Offerred player has resources
          (validate-side to-player asking)
          ;; Offering player has resources
          (validate-side from-player offering))
      (throw (ex-info "Invalid trade proposal"
                      {:checks     {;; The current player is offering
                                    :current-player-offering?
                                    (boolean (= (:id from-player)
                                                (:trade/from-player proposal)))
                                    ;; Offerred player has resources
                                    :offered-player-has-resources?
                                    (boolean (validate-side to-player asking))
                                    ;; Offering player has resources
                                    :offering-player-has-resource?
                                    (boolean (validate-side from-player offering))}
                       :proposal   proposal
                       :game-state game-state})))

    (let [;; Log initial proposal
          game-state   (append-tx game-state :proposal proposal)
          ;; Dispatch trade-proposal to other player's decision logic
          to-player-fn (:function to-player)
          decision     (to-player-fn game-state :trade-proposal proposal)]

      ;; TODO - Implement "counter proposal" logic
      ;; TODO - Somehow need to prevent endless proposal loops from happening

      (case (:action decision)
        ;; Turned down, log last status/stage
        :decline
        (append-tx game-state :decline proposal)
        ;; Accepted
        :accept
        (-> game-state
            ;; Perform transaction of resources
            (apply-trade proposal)
            ;; Log last status/stage
            (append-tx :accept proposal))))))

;; TODO - This is actually pretty kind-of a hard part of logic to write ... lots of different cases...
;;        Perhaps a rules engine would be better, because it could be many and/or/and combinations
;;        For now, this just returns true if the game is being played
(defn can-propose?
  "Given a game-state and a player-id, determine if the
  player is in a position to offer a trade proposal to
  any other active player in the game."
  [game-state player-id]

  (let [player (util/player-by-id game-state player-id)
        others (util/other-players game-state player-id)]
    (and
      ;; Game is still active
      (= (:playing (:status game-state)))
      ;; ...
      )

    ;; OR
    ;; - If you own some property, with no houses
    ;; - If someone else owns some property, with no houses
    ;; OR
    ;; - If you have a card
    ;; OR
    ;; ??
    )
  true
  )
