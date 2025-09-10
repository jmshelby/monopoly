# Monopoly
Monopoly Game Engine + Pluggable Player API

## License

This project is **not licensed** for use, modification, or distribution.
All rights are reserved under copyright law.

The code is shared publicly for:
- Educational viewing
- Portfolio demonstration
- Learning purposes only

You may not use this code in your own projects without explicit written permission.

To request permission to use this code, please open a GitHub issue with details about your intended use.

## Development Commands

### Running Tests
```bash
clojure -M:test
```

### Test Coverage
```bash
# Generate coverage report
./bin/coverage

# View HTML report
open target/coverage/index.html
```

See [docs/COVERAGE.md](docs/COVERAGE.md) for detailed coverage information.

### Game Simulation
Run parallel game simulations to analyze game mechanics and balance:

```bash
# Quick test (100 games - default)
clojure -M:sim

# Small test (50 games)
clojure -M:sim 50

# Large simulation (1000 games)
clojure -M:sim 1000

# For large simulations, increase memory if needed
clojure -J-Xmx8g -M:sim 1000
```

The simulation provides statistics on:
- Game completion rates and winner distribution
- Transaction counts and game length analysis
- Auction system metrics (occurrence rates, success/failure ratios, average bids)
- Performance metrics and memory usage
- Incomplete game breakdown (failsafe cases)

## Game State

This engine is implemented as a game state that goes through iterative "advancements" to progress through the game. Here is the definition of the game state as an example map:

```clj
  {;; Static board definition for the game
   :board "[See definitions NS]"

   ;; Game Status - playing | complete
   :status :playing

   ;; The list of players, in their game play order,
   ;; and their current state in the game.
   ;; When a game starts, players will be randomly sorted
   :players [{;; Probably some auto-generated one
              :id             "some-uuid"
              ;; Status, playing/bankrupt
              :status         :playing
              ;; Current amount of money on hand
              :cash           1
              ;; Special card collection, current set
              :cards          #{:get-out-of-jail-free}
              ;; Which cell on the board are they currently in
              :cell-residency 0
              ;; If on jail cell (haha), and incarcerated,
              ;; track stats on stay
              :jail-spell     {:cause      "[polymorphic] How did they end up in jail"
                               ;; While in jail, the dice roll attempts
                               ;; made to get a double, one for each
                               ;; turn only 3 max are allowed
                               :dice-rolls []}
              ;; The current set of owned "properties", and current state
              :properties     {:park-place {:status      :paid-OR-mortgaged
                                            :house-count 0}}}]

   ;; Separately, track what is going on with the current "turn".
   ;; At any given type there is always a single player who's turn it is,
   ;; but other things can be happening at the same time.
   :current-turn {:player     "player uuid"
                  ;; All the dice rolls from the current turn player,
                  ;; multiple because doubles get another roll
                  :dice-rolls []
                  ;; Opt - when needing to raise funds for a player
                  :raise-funds 999}

   ;; The current *ordered* care queue to pull from.
   ;; At the beginning of the game these will be loaded at random,
   ;; when one queue is exhausted, it is randomly filled again.
   :card-queue {:chance          []
                :community-chest []}

   ;; A list of all game move history, and it's details.
   ;; This is probably more of an enhanced feature...
   ;; Thoughts:
   ;;  - This is a lot like datomic...
   ;;  - Each item in this list could be every unique game state
   :transactions []}
```

## Future

#### Engine Features
 - Seed(s) to drive random number generators
   - Dice and card shuffle order
 - HTTP Interface for player decision logic
   - Handling faulty players...
     - Latency issues
     - Timeouts/Retries?
