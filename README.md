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
   - On player turn action (proactive property management)
 - Mortgage Property
   - On "raise funds" workflow
   - On player turn action (proactive property management)
 - Unmortgage Property
   - On player turn action (strategic monopoly completion)
 - Player "lose" logic
   - detect if bankrupt
   - force sell off, "raise funds" workflow
   - Bankruptcy to Bank: When players owe the Bank more than they can pay, all properties are auctioned off to remaining players
   - Mortgaged property acquisitions, require min of 10%, or unmortgaging
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
     - Deferred effects: Cards that modify rent calculation during movement (utility 10x dice roll, railroad 2x rent)
 - Proposals
   - On player turn action
     - Offer workflow
     - Mortgaged property acquisitions, require min of 10%, or unmortgaging
 - Auction System
   - Property auctions when purchase declined, or can't afford
   - Bankruptcy auctions: All properties auctioned when player goes bankrupt to bank
   - Sequential bidding with configurable increments
   - Random player order for fairness
   - Proper player context (each bidder uses their own cash/data)
 ---------------------------
#### Remaining Logic
 - Proposal "Counter-offer" workflow
   - not super important, as any
 - Obscure Rules
   > "INCOME TAX": If you land here you have two options: You may estimate your tax at $900 and pay the Bank, or you may pay 10% of your total worth to the Bank. Your total worth is all your cash on hand, printed prices of mortgaged and unmortgaged properties and cost price of all buildings you own. You must decide which option you will take before you add up your total worth.
     - ?? Which version of the game has these rules ??



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
