(ns jmshelby.monopoly.player
  (:require [jmshelby.monopoly.util :as util]
            [jmshelby.monopoly.cards :as cards]
            [jmshelby.monopoly.property :as property]))

(defn- net-worth
  [game-state player]
  ;; Just need to add cash to property value
  (+ (:cash player)
     (util/player-property-sell-worth
      game-state (:id player))))

(defn- sell-off-houses
  "Given a game state and a player, sell off all houses/hotels
  on all owned properties. Buildings are sold at half original
  price, per the board defs."
  [game-state player]
  (let [pidx        (:player-index player)
        ;; Get property details
        props       (util/owned-property-details game-state [player])
        ;; Get house sell value, half price for each house
        house-worth (->> props
                         vals
                         ;; Only street properties have houses
                         (filter #(= :street (-> % :def :type)))
                         (map (fn [{:keys [def house-count]}]
                                (* house-count (:house-price def))))
                         (apply +)
                         util/half)]
  ;; TODO - should this be a transaction?
    (-> game-state
        ;; First sell off all houses to bank
        (update-in [:players pidx :cash] + house-worth)
        (update-in [:players pidx :properties]
                   (fn [props] (->> props
                                    (map (fn [[k m]] [k (assoc m :house-count 0)]))
                                    (into {})))))))

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
        cards    (:cards from)]
    (-> game-state
        (assoc-in [:players from-idx :cards] #{})
        (update-in [:players to-idx :cards] into cards))))

(defn- auction-bankrupt-properties
  "Auction off all properties owned by a bankrupt player"
  [game-state player]
  (let [properties (keys (:properties player))
        ;; First set the bankrupt player's status so they're excluded from auctions
        gs-with-bankrupt-status (assoc-in game-state [:players (:player-index player) :status] :bankrupt)]
    (reduce (fn [gs property-name]
              ;; Remove the property from the bankrupt player
              (let [updated-gs (update-in gs [:players (:player-index player) :properties]
                                          dissoc property-name)]
                ;; Then run auction for the property with bankruptcy context
                (util/apply-auction-property-workflow updated-gs property-name :tx-context {:reason :bankruptcy})))
            gs-with-bankrupt-status
            properties)))

(defn- bankrupt-to-bank
  [game-state player]
  (let [pidx (:player-index player)
        ;; Get retained cards
        retain-cards
        (get-in game-state [:players pidx :cards])
        ;; Get properties for transaction record
        properties (:properties player)]
    ;; Buildings are automatically returned to inventory when properties are liquidated
    ;; since we derive available inventory from current game state
    (-> game-state
        ;; FIRST: Record bankruptcy transaction + status to establish context
        (assoc-in [:players pidx :status] :bankrupt)
        (util/append-tx {:type       :bankruptcy
                         :player     (:id player)
                         :to         :bank
                         :cash       (:cash player)
                         :cards      retain-cards
                         :properties properties})
        ;; THEN: Auction off all properties (with bankruptcy context)
        (auction-bankrupt-properties player)
        ;; Put retained cards back into their decks (bottom)
        (cards/add-to-deck-queues retain-cards))))

(defn- bankrupt-to-player
  [game-state
   {pidx :player-index
    :as debtor}
   debtee]
  (as-> game-state *
    ;; First, set player status
    (assoc-in * [:players pidx :status] :bankrupt)
    ;; Next, before transfers, sell off required assets
    (sell-off-houses game-state debtor)
    ;; Then record bankruptcy transaction, to set context
    (util/append-tx * {:type       :bankruptcy
                       :player     (:id debtor)
                       :to         (:id debtee)
                       ;; Amount of cash on hand
                       :cash       (:cash debtor)
                       ;; Amount of cash from selling off assets
                       :cash-from-assets (- (get-in * [:players pidx :cash])
                                            (:cash debtor))
                       :cards      (get-in * [:players pidx :cards])
                       :properties (:properties debtor)})
      ;; Transfer all current cash
    (transfer-cash * debtor debtee)
        ;; Transfer all cards over to debtee
    (transfer-cards * debtor debtee)
        ;; Transfer all properties over to debtee
    ;; (including mortgaged acquisition workflow)
    (property/transfer * debtor debtee)))

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
                          game-state player
                          (:property-name decision))
      :mortgage-property (util/apply-property-mortgage
                          game-state player
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
            player (util/player-by-id gs pid)
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
      (bankrupt-to-bank game-state debtor)
      ;; Bankrupt to other player
      ;;  -> Bankrupcy flow
      ;;  -> Property + cash transfer/acquisition workflow
      ;;  -> No custom follow-up
      debtee
      (bankrupt-to-player game-state debtor debtee)
      ;; Anything else is an invalid state
      :else
      (throw (ex-info (str "make-requisite-payment: No valid player state."
                           " Player can't pay, and we don't know who to pay to...")
                      {:debtor debtor-id
                       :debtee debtee-id
                       :amount amount})))))
