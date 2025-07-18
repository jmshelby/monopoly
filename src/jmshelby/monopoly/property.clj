(ns jmshelby.monopoly.property
  (:require [jmshelby.monopoly.util :as util]))

(defn- handle-mortgaged-acquisitions
  "Handle the player decision workflow for acquiring mortgaged properties.
  Player must choose between :pay-mortgage (immediate unmortgage) or
  :pay-interest (10% now, pay full later) for each mortgaged property.
  This function handles both the decision making AND the property transfer."
  [game-state from to mortgaged-prop-states]
  (let [player-fn (:function to)
        player-id (:id to)
        to-pidx   (:player-index to)
        from-pidx (:player-index from)
        board     (:board game-state)
        mortgaged-props (keys mortgaged-prop-states)

        ;; Get property definitions for mortgage values
        prop-defs (->> board :properties
                      (reduce #(assoc %1 (:name %2) %2) {}))

        ;; Create context with mortgaged property details
        context {:properties
                 (into {} (map (fn [prop-name]
                                (let [mortgage-val (:mortgage (prop-defs prop-name))]
                                  [prop-name {:mortgage-value mortgage-val
                                              :unmortgage-cost (-> mortgage-val (* 1.1) Math/ceil int)
                                              :interest-fee (-> mortgage-val (* 0.1) Math/ceil int)}]))
                              mortgaged-props))}

        ;; Get player decisions
        decisions (player-fn game-state player-id :acquisition context)]

    ;; Process each decision - transfer property and apply payments
    (reduce (fn [state [prop-name choice]]
              (let [prop-def (prop-defs prop-name)
                    mortgage-val (:mortgage prop-def)
                    interest-fee (-> mortgage-val (* 0.1) Math/ceil int)
                    unmortgage-cost (-> mortgage-val (* 1.1) Math/ceil int)
                    prop-state (mortgaged-prop-states prop-name)]

                (case choice
                  ;; Pay mortgage + 10% to unmortgage immediately
                  :pay-mortgage
                  (-> state
                      ;; Transfer property with paid status
                      (update-in [:players from-pidx :properties] dissoc prop-name)
                      (assoc-in [:players to-pidx :properties prop-name]
                               (assoc prop-state :status :paid))
                      ;; Charge player mortgage + 10%
                      (update-in [:players to-pidx :cash] - unmortgage-cost)
                      ;; Record transaction
                      (util/append-tx {:type :mortgaged-acquisition
                                       :player player-id
                                       :property prop-name
                                       :choice :immediate-unmortgage
                                       :amount unmortgage-cost
                                       :mortgage-value mortgage-val
                                       :interest-fee interest-fee}))

                  ;; Pay 10% interest now, keep property mortgaged
                  :pay-interest
                  (-> state
                      ;; Transfer property with mortgaged status and deferred interest flag
                      (update-in [:players from-pidx :properties] dissoc prop-name)
                      (assoc-in [:players to-pidx :properties prop-name]
                               (assoc prop-state :deferred-interest true))
                      ;; Charge player 10% interest
                      (update-in [:players to-pidx :cash] - interest-fee)
                      ;; Record transaction
                      (util/append-tx {:type :mortgaged-acquisition
                                       :player player-id
                                       :property prop-name
                                       :choice :deferred-unmortgage
                                       :amount interest-fee
                                       :mortgage-value mortgage-val
                                       :interest-fee interest-fee}))

                  ;; Invalid choice
                  (throw (ex-info "Invalid mortgaged property acquisition choice"
                                  {:player player-id
                                   :property prop-name
                                   :choice choice
                                   :valid-choices [:pay-mortgage :pay-interest]})))))
            game-state
            decisions)))

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
       (handle-mortgaged-acquisitions from to mortgaged-prop-states)))))
