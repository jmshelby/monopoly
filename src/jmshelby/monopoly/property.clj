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
        context {:properties
                 (into {} (map (fn [prop-name]
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
              (let [mortgage-val (get-in context [prop-name :mortgage-value])
                    interest-fee (get-in context [prop-name :interest-fee])
                    unmortgage-cost (get-in context [prop-name :unmortgage-cost])]

                ;; TODO - use "make requisite payment" workflow for these charges (they are required)

                (case choice
                  ;; Pay mortgage + 10% to unmortgage immediately
                  :pay-mortgage
                  (-> state
                      ;; Transfer property with paid status
                      (update-in [:players from-pidx :properties] dissoc prop-name)
                      (assoc-in [:players to-pidx :properties prop-name]
                                ;; TODO - Is there some central place we can get a new property state from?
                                {:status :paid
                                 :house-count 0})
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
                      ;; Transfer property with mortgaged status
                      (update-in [:players from-pidx :properties] dissoc prop-name)
                      (assoc-in [:players to-pidx :properties prop-name]
                                ;; TODO - Is there some central place we can get a new property state from?
                                {:status :mortgaged
                                 :house-count 0})
                      ;; Charge player 10% interest
                      (update-in [:players to-pidx :cash] - interest-fee)
                      ;; Record transaction
                      (util/append-tx {:type :mortgaged-acquisition
                                       :player player-id
                                       :property prop-name
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
