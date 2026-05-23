# HexIntent

Hexcasting is capable of extraordinary things, but so much of its beauty can be lost in the moment of casting. A pattern is drawn, the world changes, and the intricate thought behind it vanishes almost instantly.

HexIntent is about giving that thought form.

Loosely themed around the idea of a caster impressing their intent upon the world, this mod explores what it means for a spell to be more than just its end result. Instead of magic appearing only as an instant effect, HexIntent adds ways for intention itself to manifest: through menus, relays, interfaces, and other visible constructs that make a hex feel present, responsive, and alive.

At the heart of the mod is the idea that a spell does not have to be a fixed instruction sealed away in a focus. A hex can present choices. It can be configured. It can adapt to the moment. If a caster wants to change a radius, alter a mode, or refine an effect, they should not always need to rewrite the whole working from scratch. By allowing menus and interactive elements to be crafted in hexcasting itself, HexIntent helps bridge the gap between abstract pattern and lived, in-world magic.

This also opens the door to building more expressive hexes without requiring every solution to lean entirely on the more meta aspects of the art. Techniques like quines, recursion, and complex focus manipulation remain impressive and valuable, and HexIntent is not meant to replace them. Instead, it offers another path: one that makes certain kinds of flexibility and interaction more accessible, while still attaching a cost so that convenience feels earned and mastery remains meaningful.

More than anything, HexIntent is about making a caster's will visible. It is about showing not just what a spell does, but what the caster means it to do. In that sense, the magic is not only the outcome.

Current release version: 2.0.0.

Usage details and pattern reference documentation are available on the docs site.

## Example

![Example teleport menu](./docs/assets/ExampleTeleportMenu.png)

Example menu built using inputs, sections and buttons. Executes a teleport to a predetermined destination or allows for custom X,Y,Z co-ordinates.

## Documentation

- Start here: [Docs Home](https://withgallantry.github.io/HexIntent/)
- Pattern reference: [patterns.html](https://withgallantry.github.io/HexIntent/patterns.html)
- Pattern scribe: [hex-pattern-scribe.html](https://withgallantry.github.io/HexIntent/hex-pattern-scribe.html)

## What You Can Do

- Build in-world interactive UIs with list, grid, and radial menus.
- Use intent components like buttons, text input, numeric input, sliders, checkboxes, dropdowns, and select lists.
- Open the casting screen uing a hex pattern, it requires a staff in hand to use. Great staff charms.
- Clear the current casting stack quickly with a dedicated pattern.
- Exit casting safely when interacting with world objects using `Exit If Interacting`. Useful for charms that occur on the right click.
- Create and link corridor (thresholds).
- Use splinter utilities for manifesting, renewing, locating, and cleaning up splinters. Slightly easier quines but at a cost.
- Cast advanced visuals: spell circles, trails, particle scatter and equation-driven clouds.
- 15 additional end game staffs.
- Create relays that trigger multiple things at once.
- Use mind vaults to store villagers minds, perfect for convienient flaying.

## Build

```sh
./gradlew build
```

The built jar is written to `build/libs/`.

## Config

Server config is in `config/manifestation.json`.

- `intentRelayMaxRangeBlocks`: max link distance in blocks (`-1` means unlimited).
- `intentRelayCooldownTicks`: cooldown between successful trigger activations.
- `intentRelayStepTriggerEnabled`: enables floor-mounted "step on" activation for players.
