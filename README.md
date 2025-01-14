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
 - [Simple] Player "lose" logic
   - When player is out of money, take them out of the game
 - Require "even" and distributed house building
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
     - Move
     - Retain
     - Incarcerate
     - Pay
     - Collect
     - [Multiple effects] (pay per house; pay per hotel)
 ---------------------------
#### Remaining Logic
 - Cards
   - Deferred effects (go to nearest utility, pay 10x dice roll)
 - Proposals
   - On player turn action
     - offer workflow
   - Counter offer workflow
 - [Full] Player "lose" logic
   - detect if bankrupt
     - Acquisition workflow to owed party (if not bank)
   - force sell off, "raise funds" workflow
 - Mortgage/Un-mortgage
   - On player turn action
   - on "raise funds" workflow
 - Sell House
   - On player turn action
   - On "raise funds" workflow
 - Auction of property (when purchased denied/unable)
   - [still need to figure out a good way to do this]
