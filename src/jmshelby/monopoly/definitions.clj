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
           {:type :jail}
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
           {:type :community-chest}
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
           {:type :community-chest}
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


  )
