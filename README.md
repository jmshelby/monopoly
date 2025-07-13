# Monopoly
Monopoly Game Engine + Pluggable Player API

## Current Progress

#### Finished Logic
 - Basic dice roll and move
 - Double dice roll goes again
 - Pass Go "allowance"
 - Tax payment due
 - Property Purchase option
 - Property Rent charge
   - with full rules implemented by type
     - street: monopoly; houses; hotel
     - utility: monopoly; dice roll w/multiplier
     - railroad: rent based on # owned
 - House Buying
   - Require "even" and distributed house building
   - Limited house/hotel inventory (32 houses, 12 hotels)
   - Hotel exchange mechanics (5th house becomes hotel, returns 4 houses to bank)
 - Sell House
   - On "raise funds" workflow
 - Mortgage Property
   - on "raise funds" workflow
 - Player "lose" logic
   - detect if bankrupt
   - force sell off, "raise funds" workflow
   - **Bankruptcy to Bank**: When players owe the Bank more than they can pay, all properties are auctioned off to remaining players
   > Should you owe the Bank, instead of another player, more than you can pay (because of taxes or penalties) even by selling off buildings and mortgaging property, you must turn over all assets to the Bank. In this case, the Bank immediately sells by auction all property so taken, except buildings
 - Go to Jail
   - "go to jail" cell/spot
   - Roll 3 consecutive doubles, "got to jail"
   - "go to jail" card
 - In Jail
   - Roll double dice to get out
   - [Force out, after being in jail a certain amount of time]
   - Pay $X to get out
   - "Get out of jail free" card bail
 - Cards
   - Multiple decks
   - Draw logic/transaction
   - Re-shuffle
   - Effects:
     - Move / Retain / Incarcerate / Pay / Collect
     - [Multiple effects] (pay per house AND pay per hotel)
 - Proposals
   - On player turn action
     - offer workflow
 - Auction System
   - Property auctions when purchase declined, or can't afford
   - **Bankruptcy auctions**: All properties auctioned when player goes bankrupt to bank
   - Sequential bidding with configurable increments
   - Random player order for fairness
   - Proper player context (each bidder uses their own cash/data)
 ---------------------------
#### Remaining Logic
 - Cards
   - Deferred effects (go to nearest utility, pay 10x dice roll)
 - [Full] Player "lose" logic
   - ~~Bankrupt to bank auctions off properties~~ ✅ **COMPLETED**
   - Acquisition workflow to owed party (if not bank)
     - requiring 10% payment of mortgaged properties or to instantly unmortgage property to acquire
   > [Ability to make deals when needing funds]
 - Mortgage/Un-mortgage
   - On player turn action
 - Sell House
   - On player turn action
 - Proposals
   - Mortgaged property requirements
     > If you are the new owner, you may lift the mortgage at once if you wish by paying off the mortgage plus 10% interest to the Bank. If the mortgage is not lifted at once, you must pay the Bank 10% interest when you buy the property and if you lift the mortgage later you must pay the Bank an additional 10% interest as well as the amount of the mortgage
   - Counter-offer workflow
 - Obscure Rules
   > "INCOME TAX": If you land here you have two options: You may estimate your tax at $900 and pay the Bank, or you may pay 10% of your total worth to the Bank. Your total worth is all your cash on hand, printed prices of mortgaged and unmortgaged properties and cost price of all buildings you own. You must decide which option you will take before you add up your total worth.
     - ?? Which version of the game has these rules ??



## Known Issues

#### Code Quality
- ~~**Player Decision Interface**: Player decision functions incorrectly assumed they were always called for the current turn player, causing auction bidding errors and wrong player context usage~~ ✅ **FIXED**
- **Parameter Passing Inconsistency**: Several utility functions in `util.clj` rely on "current player" context instead of accepting explicit player parameters. This affects:
  - `can-sell-house?` - should accept player parameter
  - `apply-house-sale` - should accept player parameter  
  - `apply-property-mortgage` - should accept player parameter
  - This creates tight coupling and makes functions less reusable/testable

## Player Decision Interface

The player decision system uses a consistent function signature:

```clojure
(defn decide [game-state player-id method params])
```

**Parameters:**
- `game-state` - Complete game state
- `player-id` - ID of the player making the decision (ensures correct player context)
- `method` - Decision type (`:take-turn`, `:auction-bid`, `:trade-proposal`, `:raise-funds`, etc.)
- `params` - Method-specific parameters

**Key Methods:**
- `:take-turn` - Primary turn actions (roll, buy house, trade proposal, etc.)
- `:auction-bid` - Bidding decisions during property auctions
- `:trade-proposal` - Accept/decline trade offers from other players
- `:raise-funds` - Choose assets to liquidate when short on cash
- `:property-option` - Buy/decline property after landing on it

This interface ensures each player makes decisions using their own financial state and property ownership, not the current turn player's context.

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

## Future

#### Engine Features
 - Seed(s) to drive random number generators
   - Dice and card shuffle order
 - HTTP Interface for player decision logic
   - Handling faulty players...
     - Latency issues
     - Timeouts/Retries?
