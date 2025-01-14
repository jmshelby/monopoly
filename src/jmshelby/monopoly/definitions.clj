(ns jmshelby.monopoly.definitions)

;; Static definition of a board
(def board
  {:cells [;; 1
           {:type      :go
            :allowance 200}
           {:type :property
            :name :mediterranean-ave}
           {:type :card
            :name :community-chest}
           {:type :property
            :name :baltic-ave}
           {:type :tax
            :cost 200}
           {:type :property
            :name :reading-railroad}
           {:type :property
            :name :oriental-ave}
           {:type :card
            :name :chance}
           {:type :property
            :name :vermont-ave}
           {:type :property
            :name :connecticut-ave}

           ;; 2
           {:type :jail
            :bail 50}
           {:type :property
            :name :st-charles-place}
           {:type :property
            :name :electric-company}
           {:type :property
            :name :states-ave}
           {:type :property
            :name :virginia-ave}
           {:type :property
            :name :pennsylvania-railroad}
           {:type :property
            :name :st-james-place}
           {:type :card
            :name :community-chest}
           {:type :property
            :name :tennessee-ave}
           {:type :property
            :name :new-york-ave}

           ;; 3
           {:type :free}
           {:type :property
            :name :kentucky-ave}
           {:type :card
            :name :chance}
           {:type :property
            :name :indiana-ave}
           {:type :property
            :name :illinois-ave}
           {:type :property
            :name :b&o-railroad}
           {:type :property
            :name :atlantic-ave}
           {:type :property
            :name :ventnor-ave}
           {:type :property
            :name :water-works}
           {:type :property
            :name :marvin-gardens}

           ;; 4
           {:type :go-to-jail}
           {:type :property
            :name :pacific-ave}
           {:type :property
            :name :north-carolina-ave}
           {:type :card
            :name :community-chest}
           {:type :property
            :name :pennsylvania-ave}
           {:type :property
            :name :short-line-railroad}
           {:type :card
            :name :chance}
           {:type :property
            :name :park-place}
           {:type :tax
            :cost 100}
           {:type :property
            :name :boardwalk}]

   ;; TODO - logic
   ;; TODO - some cards are keepable/tradable
   :cards #{;; *Move* actions
            {:text           "Advance to Go (Collect $200)"
             :simplified     "Move to X cell"
             :deck           :chance
             :card/effect    :move
             :card.move/cell [:type :go]}
            {:text           "Advance to Go (Collect $200)"
             :simplified     "Move to X cell"
             :deck           :community-chest
             :card/effect    :move
             :card.move/cell [:type :go]}
            {:text           "Advance to Boardwalk"
             :simplified     "Move to X cell"
             :deck           :chance
             :card/effect    :move
             :card.move/cell [:property :boardwalk]}
            {:text           "Advance to Illinois Avenue. If you pass Go, collect $200"
             :simplified     "Move to X cell"
             :deck           :chance
             :card/effect    :move
             :card.move/cell [:property :illinois-ave]}
            {:text           "Advance to St. Charles Place. If you pass Go, collect $200"
             :simplified     "Move to X cell"
             :deck           :chance
             :card/effect    :move
             :card.move/cell [:property :st-charles-place]}
            {:text           "Take a trip to Reading Railroad. If you pass Go, collect $200"
             :simplified     "Move to X cell"
             :deck           :chance
             :card/effect    :move
             :card.move/cell [:property :reading-railroad]}

            {:text           "Go Back 3 Spaces"
             :simplified     "Move back x cells"
             :deck           :chance
             :card/effect    :move
             :card.move/cell [:back 3]}

            ;; Jail actions
            {:text            "Get Out of Jail Free"
             :simplified      "Collect card, later use, get out of jail"
             :deck            :chance
             :card/effect     :retain
             :card.retain/use :bail}
            {:text            "Get Out of Jail Free"
             :simplified      "Collect card, later use, get out of jail"
             :deck            :community-chest
             :card/effect     :retain
             :card.retain/use :bail}

            {:text        "Go to Jail. Go directly to Jail, do not pass Go, do not collect $200"
             :simplified  "Go to Jail"
             :deck        :chance
             :card/effect :incarcerate}
            {:text        "Go to Jail. Go directly to jail, do not pass Go, do not collect $200"
             :simplified  "Go to Jail"
             :deck        :community-chest
             :card/effect :incarcerate}

            ;; Pay/receive actions
            {:text              "Bank pays you dividend of $50"
             :simplified        "Receive X money"
             :deck              :chance
             :card/effect       :collect
             :card.collect/cash 50}
            {:text              "Your building loan matures. Collect $150"
             :simplified        "Receive X money"
             :deck              :chance
             :card/effect       :collect
             :card.collect/cash 150}
            {:text              "You inherit $100"
             :simplified        "Receive X money"
             :deck              :community-chest
             :card/effect       :collect
             :card.collect/cash 100}
            {:text              "Receive $25 consultancy fee"
             :simplified        "Receive X money"
             :deck              :community-chest
             :card/effect       :collect
             :card.collect/cash 25}
            {:text              "Income tax refund. Collect $20"
             :simplified        "Receive X money"
             :deck              :community-chest
             :card/effect       :collect
             :card.collect/cash 20}
            {:text              "From sale of stock you get $50"
             :simplified        "Receive X money"
             :deck              :community-chest
             :card/effect       :collect
             :card.collect/cash 50}
            {:text              "Holiday fund matures. Receive $100"
             :simplified        "Receive X money"
             :deck              :community-chest
             :card/effect       :collect
             :card.collect/cash 100}
            {:text              "Life insurance matures. Collect $100"
             :simplified        "Receive X money"
             :deck              :community-chest
             :card/effect       :collect
             :card.collect/cash 100}
            {:text              "Bank error in your favor. Collect $200"
             :simplified        "Receive X money"
             :deck              :community-chest
             :card/effect       :collect
             :card.collect/cash 200}
            {:text              "You have won second prize in a beauty contest. Collect $10"
             :simplified        "Receive X money"
             :deck              :community-chest
             :card/effect       :collect
             :card.collect/cash 10}

            {:text          "Speeding fine $15"
             :simplified    "Pay X money"
             :deck          :chance
             :card/effect   :pay
             :card.pay/cash 15}
            {:text          "Doctor’s fee. Pay $50"
             :simplified    "Pay X money"
             :deck          :community-chest
             :card/effect   :pay
             :card.pay/cash 50}
            {:text          "Pay school fees of $50"
             :simplified    "Pay X money"
             :deck          :community-chest
             :card/effect   :pay
             :card.pay/cash 50}
            {:text          "Pay hospital fees of $100"
             :simplified    "Pay X money"
             :deck          :community-chest
             :card/effect   :pay
             :card.pay/cash 100}

            {:text                 "You have been elected Chairman of the Board. Pay each player $50"
             :notes                ["This may be the official card, but the app version pays this money to the bank."
                                    "TODO - figure out which one we should implement"]
             :simplified           "Pay X money, multiplied by # of players"
             :deck                 :chance
             :card/effect          :pay
             :card.pay/cash        50
             :card.cash/multiplier :player/count}
            {:text                 "It is your birthday. Collect $10 from every player"
             :notes                ["This may be the official card, but the app version pays this money from the bank."
                                    "TODO - figure out which one we should implement"]
             :simplified           "Receive X money, multiplied by # of players"
             :deck                 :community-chest
             :card/effect          :collect
             :card.collect/cash    10
             :card.cash/multiplier :player/count}

            {:text        ["Make general repairs on all your property."
                           "For each house pay $25. For each hotel pay $100."]
             :simplified  ["Pay X money, multiplied by # owned houses."
                           "Pay Y money, multiplied by # owned hotels."]
             :deck        :chance
             :card/effect [{:card/effect          :pay
                            :card.pay/cash        25
                            :card.cash/multiplier :house/count}
                           {:card/effect          :pay
                            :card.pay/cash        100
                            :card.cash/multiplier :hotel/count}]}
            {:text        "You are assessed for street repair. $40 per house. $115 per hotel"
             :simplified  ["Pay X money, multiplied by # owned houses."
                           "Pay Y money, multiplied by # owned hotels."]
             :deck        :community-chest
             :card/effect [{:card/effect          :pay
                            :card.pay/cash        40
                            :card.cash/multiplier :house/count}
                           {:card/effect          :pay
                            :card.pay/cash        115
                            :card.cash/multiplier :hotel/count}]}

            ;; Move + Pay actions
            {:text                           ["Advance token to nearest Utility."
                                              "If unowned, you may buy it from the Bank."
                                              "If owned, throw dice and pay owner a total ten times amount thrown."]
             :simplified                     "Move to next utility, roll dice, pay w/multiplier X"
             :deck                           :chance
             :card/effect                    :move
             :card.move/cell                 [[:type :property]
                                              [:type :utility]]
             :card.retain/effect             :inflation
             :card.inflation/dice-multiplier 10}
            {:text                      ["Advance to the nearest Railroad."
                                         "If unowned, you may buy it from the Bank."
                                         "If owned, pay twice the rental to which they are otherwise entitled."]
             :simplified                "Move to next type==X property; pay multiplied by Y"
             :count                     2
             :deck                      :chance
             :card/effect               :move
             :card.move/cell            [[:type :property]
                                         [:type :railroad]]
             :card.retain/effect        :inflation
             :card.inflation/multiplier 10}}

   :properties #{;; Utilities
                 {:name     :water-works
                  :type     :utility
                  :price    150
                  :mortgage 75
                  :rent     [{:dice-multiplier 4}
                             {:dice-multiplier 10}]}
                 {:name     :electric-company
                  :type     :utility
                  :price    150
                  :mortgage 75
                  :rent     [{:dice-multiplier 4}
                             {:dice-multiplier 10}]}
                 ;; Railroads
                 {:name     :reading-railroad
                  :type     :railroad
                  :price    200
                  :mortgage 100
                  :rent     [25 50 100 200]}
                 {:name     :pennsylvania-railroad
                  :type     :railroad
                  :price    200
                  :mortgage 100
                  :rent     [25 50 100 200]}
                 {:name     :b&o-railroad
                  :type     :railroad
                  :price    200
                  :mortgage 100
                  :rent     [25 50 100 200]}
                 {:name     :short-line-railroad
                  :type     :railroad
                  :price    200
                  :mortgage 100
                  :rent     [25 50 100 200]}

                 ;; Brown Street
                 {:name        :mediterranean-ave
                  :type        :street
                  :group-name  :brown
                  :price       60
                  :mortgage    30
                  :rent        2
                  :group-rent  4
                  :house-price 50
                  :house-rent  [10 30 90 160 250]}
                 {:name        :baltic-ave
                  :type        :street
                  :group-name  :brown
                  :price       60
                  :mortgage    30
                  :rent        4
                  :group-rent  8
                  :house-price 50
                  :house-rent  [20 60 180 320 450]}

                 ;; Light Blue Street
                 {:name        :oriental-ave
                  :type        :street
                  :group-name  :light-blue
                  :price       100
                  :mortgage    50
                  :rent        6
                  :group-rent  12
                  :house-price 50
                  :house-rent  [30 90 270 400 550]}
                 {:name        :vermont-ave
                  :type        :street
                  :group-name  :light-blue
                  :price       100
                  :mortgage    50
                  :rent        6
                  :group-rent  12
                  :house-price 50
                  :house-rent  [30 90 270 400 550]}
                 {:name        :connecticut-ave
                  :type        :street
                  :group-name  :light-blue
                  :price       120
                  :mortgage    60
                  :rent        8
                  :group-rent  16
                  :house-price 50
                  :house-rent  [40 100 300 450 600]}

                 ;; Purple Street
                 {:name        :st-charles-place
                  :type        :street
                  :group-name  :purple
                  :price       140
                  :mortgage    70
                  :rent        10
                  :group-rent  20
                  :house-price 100
                  :house-rent  [50 150 450 625 750]}
                 {:name        :states-ave
                  :type        :street
                  :group-name  :purple
                  :price       140
                  :mortgage    70
                  :rent        10
                  :group-rent  20
                  :house-price 100
                  :house-rent  [50 150 450 625 750]}
                 {:name        :virginia-ave
                  :type        :street
                  :group-name  :purple
                  :price       160
                  :mortgage    80
                  :rent        12
                  :group-rent  24
                  :house-price 100
                  :house-rent  [60 180 500 700 900]}

                 ;; Orange Street
                 {:name        :st-james-place
                  :type        :street
                  :group-name  :orange
                  :price       180
                  :mortgage    90
                  :rent        14
                  :group-rent  28
                  :house-price 100
                  :house-rent  [70 200 550 750 950]}
                 {:name        :tennessee-ave
                  :type        :street
                  :group-name  :orange
                  :price       180
                  :mortgage    90
                  :rent        14
                  :group-rent  28
                  :house-price 100
                  :house-rent  [70 200 550 750 950]}
                 {:name        :new-york-ave
                  :type        :street
                  :group-name  :orange
                  :price       200
                  :mortgage    100
                  :rent        16
                  :group-rent  32
                  :house-price 100
                  :house-rent  [80 220 600 800 1000]}

                 ;; Red Street
                 {:name        :kentucky-ave
                  :type        :street
                  :group-name  :red
                  :price       220
                  :mortgage    110
                  :rent        18
                  :group-rent  36
                  :house-price 150
                  :house-rent  [90 250 700 875 1050]}
                 {:name        :indiana-ave
                  :type        :street
                  :group-name  :red
                  :price       220
                  :mortgage    110
                  :rent        18
                  :group-rent  36
                  :house-price 150
                  :house-rent  [90 250 700 875 1050]}
                 {:name        :illinois-ave
                  :type        :street
                  :group-name  :red
                  :price       240
                  :mortgage    120
                  :rent        20
                  :group-rent  40
                  :house-price 150
                  :house-rent  [100 300 750 925 1100]}

                 ;; Yellow Street
                 {:name        :atlantic-ave
                  :type        :street
                  :group-name  :yellow
                  :price       260
                  :mortgage    130
                  :rent        22
                  :group-rent  44
                  :house-price 150
                  :house-rent  [110 330 800 975 1150]}
                 {:name        :ventnor-ave
                  :type        :street
                  :group-name  :yellow
                  :price       260
                  :mortgage    130
                  :rent        22
                  :group-rent  44
                  :house-price 150
                  :house-rent  [110 330 800 975 1150]}
                 {:name        :marvin-gardens
                  :type        :street
                  :group-name  :yellow
                  :price       280
                  :mortgage    140
                  :rent        24
                  :group-rent  48
                  :house-price 150
                  :house-rent  [120 360 850 1025 1200]}

                 ;; Green Street
                 {:name        :pacific-ave
                  :type        :street
                  :group-name  :green
                  :price       300
                  :mortgage    150
                  :rent        26
                  :group-rent  52
                  :house-price 200
                  :house-rent  [130 390 900 1100 1275]}
                 {:name        :north-carolina-ave
                  :type        :street
                  :group-name  :green
                  :price       300
                  :mortgage    150
                  :rent        26
                  :group-rent  52
                  :house-price 200
                  :house-rent  [130 390 900 1100 1275]}
                 {:name        :pennsylvania-ave
                  :type        :street
                  :group-name  :green
                  :price       320
                  :mortgage    160
                  :rent        28
                  :group-rent  56
                  :house-price 200
                  :house-rent  [150 450 1000 1200 1400]}

                 ;; Blue Street
                 {:name        :park-place
                  :type        :street
                  :group-name  :blue
                  :price       350
                  :mortgage    175
                  :rent        35
                  :group-rent  70
                  :house-price 200
                  :house-rent  [175 500 1100 1300 1500]}
                 {:name        :boardwalk
                  :type        :street
                  :group-name  :blue
                  :price       400
                  :mortgage    200
                  :rent        50
                  :group-rent  100
                  :house-price 200
                  :house-rent  [200 600 1400 1700 2000]}}})

(comment

  ;; Cell exploration
  (-> board
      :cells
      (get 13))

  ;; Property exploration
  (->> board
       ;; :cells
       :properties
       (filter #(= :street (-> % :type)))
       (group-by (juxt :type :group-name :name))
       keys
       sort
       ;; (map :name)
       ;; count
       )

  ;; Card exploration
  (->> board
       :cards
       (map :simplified)
       frequencies
       (map (fn [[k v]] [(if (vector? k) (apply str k) k) v]))
       ;; (map first)
       (sort-by second)
       )

  (->> board
       :cards
       (map :card/effect)
       frequencies
       )

  )
