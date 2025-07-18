(ns jmshelby.monopoly.property)

(defn transfer
  ;; TODO - Do mortgaged/acquisition workflow logic
  ;;        -> As a part of this, should we signal something in the game-state that we're in the middle of a bankruptcy asset transfer???
  ([game-state from to]
   ;; Default to transfering all properties owned by "from"
   (transfer game-state from to (-> from :properties keys)))
  ([game-state from to prop-names]
   (let [;; Get player maps
         to-pidx     (:player-index to)
         from-pidx   (:player-index from)
        ;; Get 'from' player property states, only
        ;; needed to preserve mortgaged status
         prop-states (select-keys (:properties from) prop-names)]
     (-> game-state
        ;; Remove props from the 'from' player
         (update-in [:players from-pidx :properties]
                   ;; Just a dissoc that takes a set
                    (partial apply dissoc) prop-names)
        ;; Add props + existing state to the 'to' player
         (update-in [:players to-pidx :properties]
                   ;; Just a conj of existing prop states
                   ;; into player's own property state map
                    conj prop-states)))))
