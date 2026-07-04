# HexIntent

I started this mod after I fell in love with Hex Casting. It started out as a menu system built to allow dynamic input into hexes, to take a players intent and have it influence a hex. That's where the mod got its name. 

Over time I added more features, and I began to realise what it is I was wanting to add to Hex Casting. I was adding things that were more physical and visual to the world of Hex Casting. Things that showed the intent of the caster, through menus, particles, visual spell circles, new staffs. They were all adding to the phsyiscal aspect of the already rich world of Hex Casting. So this is what this mod has evolved into.

---

# ✨ Features

There's quite a few features, I'd say read the [wiki](https://withgallantry.github.io/HexIntent/v/latest/main/en_us/#manifestation/visual_manifestation) for a more comprehensive overview.

## Main Features

- **Manifest Menus**: Create menus through hex that allow you to dynamically change hexes.
- **Manifest Relay**: Spooky action at a distance. This hex creates a surface on a block that when interacted with, relays that interaction to a target. Think triggering toolsmith impetus from a distance etc.
- **Manifest Threshold:** Force two points in space to become one. Crossing this threshold instantly moves you between these locations.
- **Splinters**: Independant casters that can be summoned.
- **Equation Hex Cloud**: First off, great name, I know. This lets you create large multi point particle clouds and animations based on mathematical equations, keeping them optimised. 
- **Equation Synthesizer**: This is how you create those Equation Iotas. There's a bunch of presets baked in so don't panic, you don't have to learn maths to use it.
- **Hex Trail**: The similar but very different thing to Equation Hex Cloud. This is a single particle that has a memory. You give it an ID and if that ID is the same accross recasts, it will smoothly animate between it's current position and the new positin given in the recast. This means you can do things like creating nice smooth transitions by animating every 5,8,10, however many ticks, instead of updating it's position every tick. But you don't have to understand ticks to get the full benefit, which is nice animations.

## Additional Features

- **Memory Crystals**: Like properties from [hexcellular](https://modrinth.com/mod/hexcellular) but tied to an item. You can also really jam these things into items capable of casting or storing iotas. Giving them a portable memory bank. I was told they're great for charms, for those subverting the natural laws of order with lani, whatever that means.
- **The Mind Vault**: Who doesn't love the musical grunts of villagers? But if you do get tired of them, why not consume them body and mind into a vault? Store villager minds for use in Flaying. The minds have a cooldown and can be re-used!
- **Hex Reliquary**: Store up to 5 patterns in this. Using a focus with a pattern will store it in a slot, and a blank focus will copy it.
- **Charming Intent**: Give your Hexical charmed items a bit of flair by giving them a new sound on cast. Requires the latest Hexical for this.

---

# 🐛 Reporting Issues

If you encounter crashes, bugs, catastrophic meltdown or just unexpected behavior, please report them through the [issue tracker](https://github.com/withgallantry/HexIntent/issues) and include:

- Minecraft version
- Mod loader version
- Installed mods
- Crash reports or `latest.log` files where possible

## Config

Server config is in `config/manifestation.json` and is rewritten with sanitized values on load.

### Top-Level Keys

- `menuOpenLoopWindowMs` (`1400`, range `200..10000`)
- `menuOpenLoopTriggerCount` (`3`, range `2..12`)
- `intentRelayMaxRangeBlocks` (`-1`, range `-1..32`; `-1` = unlimited)
- `intentRelayCooldownTicks` (`4`, range `0..40`)
- `intentRelayStepTriggerEnabled` (`true`)
- `portalLiveViewEnabled` (`true`)
- `portalLiveViewCols` (`48`, range `12..96`)
- `portalLiveViewRows` (`72`, range `18..128`)
- `portalLiveViewDistanceBlocks` (`48`, range `8..128`)
- `menuDispatchRefillPerSecond` (`12.0`, range `1.0..40.0`)
- `menuDispatchBurstTokens` (`36.0`, range `4.0..120.0`)
- `menuDispatchViolationDecayMs` (`15000`, range `1000..120000`)
- `menuDispatchBaseCooldownMs` (`500`, range `100..5000`)
- `menuDispatchMaxCooldownMs` (`8000`, range `250..60000`, and always `>= menuDispatchBaseCooldownMs`)
- `splinterCasterEnabled` (`true`)
- `splinterMaxActivePerOwner` (`-1`, range `-1..4096`; `-1` = unlimited)
- `splinterPerformancePreset` (`"safe"`; options: `safe`, `balanced`, `fast`, `blast`, `custom`)
- `splinterUseExternalizedForEachFrame` (`true`)
- `splinterAdvanced` (nested block, only used when preset is `custom`)
- `splinterWriteAdvancedConfig` (`false`; when true, writes full advanced values even for non-`custom` presets)

### Splinter Preset Examples

Default / safer server profile:

```json
{
	"splinterPerformancePreset": "safe"
}
```

Balanced profile:

```json
{
	"splinterPerformancePreset": "balanced"
}
```

Aggressive testing profile:

```json
{
	"splinterPerformancePreset": "fast"
}
```

### Custom Splinter Tuning

Use `custom` + `splinterAdvanced.enabled: true` when you want explicit manual tuning:

```json
{
	"splinterPerformancePreset": "custom",
	"splinterAdvanced": {
		"enabled": true,
		"globalBudgetMicrosPerTick": 45000,
		"opsPerSlice": 25000,
		"sliceBudgetMicros": 45000,
		"maxSlicesPerTick": 4,
		"maxRecordScansPerTick": 512,
		"emergencySliceMillis": 500,
		"maxTotalWorkUnits": 5000000,
		"largeListChunkSize": 4096,
		"largeForeachChunkExecutionSize": 512,
		"safeInlineForeachRemainingCap": 4096,
		"debugSliceTelemetry": false
	}
}
```
