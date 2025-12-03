# Scoundrel Solitaire - Game Rules

## Overview
Scoundrel is a solitaire card game using a standard 52-card deck where you must survive encounters in rooms by managing weapons, fighting monsters, and using healing potions strategically.

## Setup
- **Deck**: Standard 52-card deck (shuffled)
- **Starting Health**: 20 HP
- **Max Health**: 20 HP (healing cannot exceed this)
- **Win Condition**: Survive until the deck is exhausted and only 1 or fewer cards remain
- **Lose Condition**: Health drops to 0 or below

## Card Types
Each card has a **value** based on its rank:
- **Ace**: 1
- **2-10**: Face value (2, 3, 4, 5, 6, 7, 8, 9, 10)
- **Jack**: 11
- **Queen**: 12
- **King**: 13

Card types are determined by suit:
- **Diamonds (♦)**: Weapons
- **Clubs (♣) & Spades (♠)**: Monsters
- **Hearts (♥)**: Potions

## Game Flow

### Rooms
Each turn you enter a **room** with 4 cards dealt from the deck:
1. You must play **exactly 3 cards** from the room
2. The 4th card is left behind (discarded)
3. After completing a room, 3 new cards are dealt (replacing the 3 played), and you enter the next room

### Room Skipping
- You may **skip a room** to avoid danger
- When skipping: all 4 cards go to the bottom of the deck, and 4 new cards are dealt
- **Constraint**: You cannot skip consecutive rooms (must play at least one room between skips)

## Card Mechanics

### Weapons (♦)
- **Effect**: Equip the weapon (replaces any currently equipped weapon)
- **Combat**: When fighting a monster with an equipped weapon:
  - **Damage Reduction**: `damage = max(0, monster_value - weapon_value)`
  - Example: Fighting an 8-value monster with a 5-value weapon deals 3 damage (8-5=3)
  - Example: Fighting a 4-value monster with a 6-value weapon deals 0 damage (blocked)

**Weapon Constraint - Descending Monster Values**:
- Once you defeat a monster with a weapon, you can only attack monsters with **strictly lower values** than the last defeated monster
- Example sequence:
  - Defeat 9-value monster → can now only attack monsters with value < 9
  - Defeat 6-value monster → can now only attack monsters with value < 6
  - Defeat 3-value monster → can now only attack monsters with value < 3
- If you cannot use your weapon (monster value ≥ last defeated), you take **full damage**
- Equipping a new weapon **resets** the defeated monster history

### Monsters (♣, ♠)
- **With Weapon**: Take reduced damage (see Weapon mechanics above)
- **Without Weapon**: Take full damage equal to monster value
- **Damage**: `health -= damage`

### Potions (♥)
- **Effect**: Heal for the potion's value (capped at max health of 20)
- **Constraint**: Only the **first potion per room** has any effect
- Subsequent potions in the same room are wasted (no healing)
- **Wasted Healing**: Any healing above max health (20) is lost
  - Example: At 18 HP, using a 7-value potion heals 2 HP (5 wasted)

## Strategic Considerations

### Card Order Matters
The order you play cards is critical:
1. **Weapon Timing**: Equip weapons before fighting monsters to reduce damage
2. **Monster Sequencing**: Fight monsters in descending order when using a weapon (due to weapon constraint)
3. **Potion Timing**: Use potions strategically (only first potion per room works)

### Example Optimal Play
Room: ♦7 (weapon), ♠9 (monster), ♠5 (monster), ♥4 (potion)

**Optimal order**:
1. ♦7 - Equip weapon
2. ♠9 - Fight with weapon (9-7 = 2 damage)
3. ♠5 - Fight with weapon (5-7 = 0 damage, and 5 < 9 ✓)
4. Leave ♥4 behind

**Why this works**:
- Fight higher-value monster first (9) so weapon can still attack lower-value monster (5)
- Total damage: 2 HP

**Bad order**:
1. ♦7 - Equip weapon
2. ♠5 - Fight with weapon (0 damage)
3. ♠9 - Cannot use weapon (9 > 5), take full 9 damage!
4. Leave ♥4 behind

Total damage: 9 HP - much worse!

### Room Skipping Strategy
Skip rooms when:
- Even the best possible card order results in death
- You cannot skip consecutive rooms, so plan ahead

### Health Management
- Max health is 20 HP - cannot exceed this
- Avoid using potions when near full health (wasted healing)
- Consider taking damage before using potions to maximize healing value
- Remember: only first potion per room works!

## Game State Tracking
The game tracks:
- Current health (0-20)
- Equipped weapon (if any)
- Weapon's defeated monster history (for constraint checking)
- Whether last room was skipped (for consecutive skip prevention)
- Potions used this turn (for first-potion constraint)
- Turn number
- Deck remaining
- Current room cards

## Winning the Game
- Deck must be empty (all cards dealt)
- Room must have ≤ 1 card (no more playable rooms possible)
- Health must be > 0

The game is challenging - even smart strategies have low win rates!
