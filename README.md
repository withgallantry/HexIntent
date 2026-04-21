# Manifestation

Manifestation is a Fabric companion mod for Hex Casting.
Current release version: 1.0.0.

This README is intentionally minimal. Full usage and pattern details live in the docs.

## Example

![Example teleport menu](./docs/assets/ExampleTeleportMenu.png)

Example menu built using inputs, sections and buttons. Executes a teleport to a predetermined destination or allows for custom X,Y,Z co-ordinates.

## Documentation

- Start here: [Docs Home](https://withgallantry.github.io/HexManifestation/)
- Pattern reference: [patterns.html](https://withgallantry.github.io/HexManifestation/patterns.html)
- Pattern scribe: [hex-pattern-scribe.html](https://withgallantry.github.io/HexManifestation/hex-pattern-scribe.html)

## Build

```sh
./gradlew build
```

The built jar is written to `build/libs/`.

## Config

Runtime config is at `config/manifestation.json`.

- `intentRelayMaxRangeBlocks`: max link distance in blocks (`-1` means unlimited).
- `intentRelayCooldownTicks`: cooldown between successful trigger activations.
- `intentRelayStepTriggerEnabled`: enables floor-mounted "step on" activation for players.

For discoverability, this README is a good place for a short summary like this.
Detailed behavior and examples can live in the docs site.
