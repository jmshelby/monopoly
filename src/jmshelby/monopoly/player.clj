(ns jmshelby.monopoly.player
  (:require [jmshelby.monopoly.util :as util]
            [jmshelby.monopoly.cards :as cards]))

(defn- net-worth
  [game-state player]
  ;; Just need to add cash to property value
  (+ (:cash player)
     (util/player-property-sell-worth
      game-state (:id player))))

(defn- reset-player-assets
  [game-state player]
  (let [pidx (:player-index player)]
    (-> game-state
        ;; Re/Set player state for bankruptcy
        (assoc-in [:players pidx :cash] 0)
        (assoc-in [:players pidx :cards] #{})
        (assoc-in [:players pidx :properties] {})
        (assoc-in [:players pidx :status] :bankrupt))))

(defn- transfer-cash
  "Given a game state, from and to entities, and an amount;
  deduct amount 'from' one player 'to' other player. Accepts
  bank as the from or to player ID. This is a lower level
  function and does *not* verify funds are available, nor
  does it trigger additional higher level workflows like
  raising money or bankruptcy.
  Multi-arity, if no amount is passed, all cash is tranfer from->to."
  ([game-state from to]
   (transfer-cash game-state (:cash from) from to))
  ([game-state amount from to]
   (letfn [(txact [gs p op]
             (update-in gs [:players (:player-index p) :cash]
                        op amount))]
     (cond-> game-state
       (not= :bank to)   (txact to +)
       (not= :bank from) (txact from -)))))

(defn- transfer-cards
  [game-state from to]
  (let [to-idx   (:player-index to)
        from-idx (:player-index from)
        cards    (get-in game-state [:players from-idx :cards])]
    (-> game-state
        (assoc-in [:players from-idx :cards] #{})
        (update-in [:players to-idx :cards] into cards))))

;; NOTE - mostly taken from trade/exchange-properties NS/fn
(defn- transfer-property
  ;; TODO - Do mortgaged/acquisition workflow logic
  ;;        -> As a part of this, should we signal something in the game-state that we're in the middle of a bankruptcy asset transfer???
  [game-state from to]
  (let [prop-names  (-> from :properties keys)
        ;; Get player maps
        to-pidx     (:player-index to)
        from-pidx   (:player-index from)
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

(defn- auction-bankrupt-properties
  "Auction off all properties owned by a bankrupt player"
  [game-state player bankruptcy-id]
  (let [properties (keys (:properties player))
        ;; First set the bankrupt player's status so they're excluded from auctions
        gs-with-bankrupt-status (assoc-in game-state [:players (:player-index player) :status] :bankrupt)]
    (reduce (fn [gs property-name]
              ;; Remove the property from the bankrupt player
              (let [updated-gs (update-in gs [:players (:player-index player) :properties]
                                          dissoc property-name)]
                ;; Then run auction for the property with bankruptcy context
                (util/apply-auction-property-workflow-with-context updated-gs property-name bankruptcy-id)))
            gs-with-bankrupt-status
            properties)))

(defn- bankrupt-to-bank
  [game-state player]
  (let [;; Get retained cards
        retain-cards
        (->> game-state
             (get-in [:players (:player-index player) :cards])
             ;; TODO - wait ... aren't all cards in a player's inventory, by definition, "retained"?
             (filter #(= :retain (:card/effect %))))
        ;; Get properties for transaction record
        properties (:properties player)
        ;; Generate a unique bankruptcy ID to link related transactions
        bankruptcy-id (str "bankruptcy-" (:id player) "-" (System/currentTimeMillis))]
    ;; Buildings are automatically returned to inventory when properties are liquidated
    ;; since we derive available inventory from current game state
    (-> game-state
        ;; FIRST: Record bankruptcy transaction to establish context
        (util/append-tx {:type         :bankruptcy
                         :bankruptcy-id bankruptcy-id
                         :player       (:id player)
                         :to           :bank
                         :cash         (:cash player)
                         :cards        retain-cards
                         :properties   properties})
        ;; THEN: Auction off all properties (with bankruptcy context)
        (auction-bankrupt-properties player bankruptcy-id)
        ;; Put retained cards back into their decks (bottom)
        (cards/add-to-deck-queues retain-cards))))

(defn- bankrupt-to-player
  [game-state debtor debtee]
  (let [;; General details
        pidx        (:player-index debtor)
        ;; Get property details
        props       (util/owned-property-details game-state [debtor])
        ;; Get house sell value
        house-worth (->> props
                         vals
                         (filter #(= :street (-> % :def :type))) ; Only street properties have houses
                         (map (fn [{:keys [def house-count]}]
                                (* house-count (:house-price def))))
                         (apply +)
                         util/half)]
    (-> game-state
        ;; First sell off all houses to bank, half price for each house
        (update-in [:players pidx :cash] + house-worth)
        ;; TODO - when we have a bank "house inventory", return houses back to it
        (update-in [:players pidx :properties]
                   (fn [props] (->> props
                                    (map (fn [[k m]] [k (assoc m :house-count 0)]))
                                    (into {}))))
        ;; Transfer all current cash (after the above sell off) to debtee
        (transfer-cash debtor debtee)
        ;; Transfer all cards over to debtee
        (transfer-cards debtor debtee)
        ;; Transfer all properties over to debtee (including mortgaged acquisition workflow)
        (transfer-property debtor debtee)
        ;; Record bankruptcy transaction
        ;; TODO - need to think about a better tx structure for bankruptcy type
        (util/append-tx {:type       :bankruptcy
                         :player     (:id debtor)
                         :to         (:id debtee)
                         :cash       (:cash debtor)
                         :cards      (get-in game-state [:players pidx :cards])
                         :properties (:properties debtor)}))))

(defn- invoke-and-apply-raise-funds
  "Given a game state, player, and outstanding amount, invoke the player's
  raise-funds decision logic and apply their chosen action to the game state.
  Player can choose to sell houses or mortgage properties. Returns updated
  game state with the applied action and corresponding transaction."
  [game-state player amount]
  ;; TODO - should we also determine which actions are currently available for the player?
  (let [player-fn (:function player)
        ;; Make actual call to player decision logic
        decision  (player-fn game-state
                             (:id player)
                             :raise-funds
                             {:amount amount})]
    ;; TODO - we can probably route this through core sometime...
    ;; TODO - we'll need a default that doesn't allow indecision...
    (case (:action decision)
      :sell-house        (util/apply-house-sale
                          game-state
                          (:property-name decision))
      :mortgage-property (util/apply-property-mortgage
                          game-state
                          (:property-name decision)))))

(defn- apply-raise-funds-workflow
  "Given a game state, player, and required amount, initiate the raise-funds
  workflow where the player must liquidate assets to cover their debt.
  Continuously invokes player decision logic until sufficient cash is raised.
  Sets :raise-funds flag on current turn and removes it when complete."
  [game-state player amount]
  ;; Set GS to indicate this current player owes a certain amount (more than they have)
  ;;  - probably just setting a "target owed amount" on the :current-turn map
  (let [pid   (:id player)
        ;; For now, just the *total* amount of cash that player needs to raise
        ;; TODO - should/can we track both original total, *and* remaining amounts?
        state (assoc-in game-state
                        [:current-turn :raise-funds]
                        amount)]

    ;; TODO - The nature of this operation is susceptible to an endless
    ;;        loop, we'll need to figure out how to detect a player that
    ;;        just can't figure how to sell his shit

    ;; Start loop/reduce, until player cash is sufficient:
    (loop [gs state]
      (let [;; Get current params
            player      (->> gs :players
                             (filter #(= pid (:id %)))
                             first)
            remaining   (- amount (:cash player))
            ;; Call out to player, apply decision
            gs-next     (invoke-and-apply-raise-funds
                         gs player remaining)
            player-next (->> gs-next :players
                             (filter #(= pid (:id %)))
                             first)]
        ;; Check if we need to keep raising
        ;; TODO - should we allow the user to go down to $0?
        (if (<= amount (:cash player-next))
          ;; Player has raised enough cash, return last game state
          gs-next
          ;; Player needs more cash, keep invoking
          (recur gs-next))))))

(defn make-requisite-payment
  "Given a game-state, player, and amount of cash, perform a requisite payment if possible.
  Four things can happen:
  - player has enough cash
  - player doesn't have enough cash, but does have net worth..
  - [owes bank] player doesn't have enough net worth to cover the amount needed
  - [owes opponent] player doesn't have enough net worth to cover the amount needed"
  [game-state
   debtor-id debtee-id
   amount follow-up]
  ;; TODO - Should we also add the transaction?
  (let [debtor (util/player-by-id game-state debtor-id)
        debtee (if (= debtee-id :bank)
                 :bank
                 (util/player-by-id game-state debtee-id))]
    (cond
      ;; Player has enough cash
      ;;  -> Just deduct, and custom follow-up
      (<= amount (:cash debtor))
      (-> game-state
          (transfer-cash amount debtor debtee)
          follow-up)
      ;; Player doesn't have enough cash,
      ;; but does have net worth..
      ;;  -> Force raise money workflow
      ;;  -> Then deduct, and custom follow-up
      (<= amount (net-worth game-state debtor))
      (-> game-state
          (apply-raise-funds-workflow debtor amount)
          (transfer-cash amount debtor debtee)
          follow-up)
      ;; Bankrupt to bank
      ;;  -> Bankruptcy sequence, no custom follow-up
      (= :bank debtee-id)
      (-> game-state
          (bankrupt-to-bank debtor)
          (reset-player-assets debtor))
      ;; Bankrupt to other player
      ;;  -> Bankrupcy flow
      ;;  -> Property + cash transfer/acquisition workflow
      ;;  -> No custom follow-up
      debtee
      (-> game-state
          (bankrupt-to-player debtor debtee)
          (reset-player-assets debtor))
      ;; Anything else is an invalid state
      :else
      (throw (ex-info "make-requisite-payment: no valid player state. player can't pay, and we don't know who to pay to..."
                      {:debtor debtor-id
                       :debtee debtee-id
                       :amount amount})))))
