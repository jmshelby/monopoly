(ns jmshelby.monopoly.property
  (:require [jmshelby.monopoly.util :as util]))

(defn- apply-mortgaged-acquisition-workflow
  "Handle the player decision workflow for acquiring mortgaged properties.
  Player must choose between :pay-mortgage (immediate unmortgage) or
  :pay-interest (10% now, pay full later) for each mortgaged property.
  This function handles both the decision making AND the property transfer."
  [game-state from to prop-names]
  (let [player-fn (:function to)
        player-id (:id to)
        to-pidx   (:player-index to)
        from-pidx (:player-index from)
        board     (:board game-state)

        ;; Get property definitions for mortgage values
        prop-defs (->> board :properties
                       (reduce #(assoc %1 (:name %2) %2) {}))

        ;; Create context with mortgaged property details
        ;; TODO - should include who it's from maybe?
        context {:properties
                 (into {}
                       (map (fn [prop-name]
                              (let [prop (prop-defs prop-name)
                                    mortgage-val (:mortgage prop)]
                                   [prop-name (assoc prop
                                                     :mortgage-value mortgage-val
                                                     :unmortgage-cost (-> mortgage-val (* 1.1) Math/ceil int)
                                                     :interest-fee (-> mortgage-val (* 0.1) Math/ceil int))]))
                               prop-names))}

        ;; Invoke player decision
        decision (player-fn game-state player-id :acquisition context)]

    ;; Process each decision - transfer property and apply payments
    (reduce (fn [state [prop-name choice]]

              ;; Validate choice
              (when-not (#{:pay-interest :pay-mortgage} choice)
                (throw (ex-info "Invalid mortgaged property acquisition choice"
                                {:player player-id
                                 :property prop-name
                                 :choice choice
                                 :valid-choices [:pay-mortgage :pay-interest]})))

              (let [;; TODO - Is there some central place we can get a new property state from?
                    prop-state {:house-count 0}
                    final (case choice
                            ;; Pay the mortgage cost + 10% fee
                            :pay-mortgage
                            {:state (merge prop-state [:status :paid])
                             :fee (get-in context [:properties prop-name :unmortgage-cost])}
                            ;; Just pay the fee right now
                            :pay-interest
                            {:state (merge prop-state [:status :mortgaged])
                             :fee (get-in context [:properties prop-name :interest-fee])})]

                ;; Apply this decision/transfer/fee to the game state
                (-> state
                    ;; Remove from giver
                    (update-in [:players from-pidx :properties] dissoc prop-name)
                    ;; Give to receiver (with new state)
                    (assoc-in [:players to-pidx :properties prop-name] (:state final))
                    ;; Charge player fee
                    ;; TODO - Use "make requisite payment" workflow for these charges
                    ;;        (they are required to pay this now, and if they can't
                    ;;        they need to raise funds or go bankrupt)
                    (update-in [:players to-pidx :cash] - (:fee final))
                    ;; Record transaction
                    (util/append-tx {:type :acquisition
                                     :to-player player-id
                                     :from-player (:id from)
                                     :property prop-name
                                     ;; :reason ??
                                     :acquisition-style [:mortgaged choice]
                                     :mortgage-final-fee (:fee final)}))))
            game-state
            decision)))

(defn transfer
  "Transfer properties from one player to another. Handles mortgaged property
  acquisition workflow where receiving player must decide whether to immediately
  unmortgage (110% of mortgage value) or pay 10% interest and keep mortgaged.
  Does *not* add any tx data to resulting game state."
  ([game-state from to]
   ;; Default to transfering all properties owned by "from"
   (transfer game-state from to (-> from :properties keys)))
  ([game-state from to prop-names]
   (let [;; Get player indices
         to-pidx     (:player-index to)
         from-pidx   (:player-index from)
         ;; Get 'from' player property states
         prop-states (select-keys (:properties from) prop-names)
         ;; Separate mortgaged and non-mortgaged properties
         {mortgaged-props :mortgaged
          normal-props    :paid} (->> prop-states
                                      (group-by (fn [[_ state]] (:status state))))
         ;; Convert back to maps
         mortgaged-prop-states (into {} mortgaged-props)
         normal-prop-states (into {} normal-props)]
     ;; Handle properties differently based on status
     (cond-> game-state
       ;; Transfer non-mortgaged properties immediately
       (seq normal-prop-states)
       (-> ;; Remove normal props from the 'from' player
        (update-in [:players from-pidx :properties]
                   (fn [props] (apply dissoc props (keys normal-prop-states))))
           ;; Add normal props to the 'to' player
        (update-in [:players to-pidx :properties]
                   conj normal-prop-states))
       ;; Handle mortgaged properties through acquisition workflow
       (seq mortgaged-prop-states)
       (apply-mortgaged-acquisition-workflow from to (keys mortgaged-prop-states))))))
