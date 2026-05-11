# HexIntent

HexIntent is a Fabric companion mod for Hex Casting.
Current release version: 2.0.0.

Usage details and pattern reference documentation are available on the docs site.

## Example

![Example teleport menu](./docs/assets/ExampleTeleportMenu.png)

Example menu built using inputs, sections and buttons. Executes a teleport to a predetermined destination or allows for custom X,Y,Z co-ordinates.

## Documentation

- Hex Docs: [Docs Home](https://withgallantry.github.io/HexIntent/)


## Features

### Menus

- List, grid, and radial manifested menus.
- UI intent primitives: button, input, numeric input, slider, checkbox, selectable list, section, dropdown.
- Server-side anti-loop and dispatch throttling protections.

### Intents and World Manifestations

- Presence Intent for location-directed behavior.
- Manifest Echo for delegated casting at an impetus.
- Intent Relay blocks for linked destination activation with optional redstone signal output.
- Corridor Portal (Manifest Threshold) support.
- Destroy Manifestation for cleanup in an area.

### Splinters

- Manifest Splinter, Renew Splinter, Locate Splinter, Dismiss Splinters.
- Splinter-cast Hex Trail visual channels.
- Watchdog protection against repeated heavy splinter execution.
- Configurable active splinter cap per owner.

### Contained Splinter

- Stores a focus and manifests anchored splinters from spell circles.
- Circle hold-and-release behavior while the splinter executes.
- Redstone interrupt support: signal dispels anchored splinter and allows circle continuation.
- Config toggle to disable Splinter Caster behavior server-side.

### Visual and UX

- Client-side Hex Trail rendering with multiple particle modes.
- Nearby-player broadcast for circle/splinter trail visibility.
- ID namespacing by caster for trail channel isolation.

## Build

```sh
./gradlew build
```

The built jar is written to `build/libs/`.

## Config

Server config is in `config/manifestation.json`.

All values are server-side and sanitized to safe bounds on load.

### Config Keys

| Key | Default | Range | Description |
|---|---:|---|---|
| `menuOpenLoopWindowMs` | `1400` | `200..10000` | Sliding time window used to detect repeated menu-open loops. |
| `menuOpenLoopTriggerCount` | `3` | `2..12` | Number of open events inside the loop window that triggers protection. |
| `intentRelayMaxRangeBlocks` | `-1` | `-1..32` | Max relay link distance in blocks. `-1` means unlimited. |
| `intentRelayCooldownTicks` | `4` | `0..40` | Cooldown between successful relay activations. |
| `intentRelayStepTriggerEnabled` | `true` | boolean | Enables floor step-trigger activation for relays. |
| `portalLiveViewEnabled` | `true` | boolean | Enables live-view portal rendering features. |
| `portalLiveViewCols` | `48` | `12..96` | Portal live-view horizontal resolution budget. |
| `portalLiveViewRows` | `72` | `18..128` | Portal live-view vertical resolution budget. |
| `portalLiveViewDistanceBlocks` | `48` | `8..128` | Max distance for portal live-view behavior. |
| `menuDispatchRefillPerSecond` | `12.0` | `1.0..40.0` | Token refill rate for menu action dispatch limiting. |
| `menuDispatchBurstTokens` | `36.0` | `4.0..120.0` | Maximum burst token capacity for menu dispatches. |
| `menuDispatchViolationDecayMs` | `15000` | `1000..120000` | Violation decay time window for dispatch abuse tracking. |
| `menuDispatchBaseCooldownMs` | `500` | `100..5000` | Base cooldown applied when dispatch protections trigger. |
| `menuDispatchMaxCooldownMs` | `8000` | `250..60000` | Ceiling for adaptive dispatch cooldown; auto-clamped to be >= base cooldown. |
| `splinterWatchdogMaxAvgExecMs` | `25.0` | `5.0..250.0` | Max moving-average execution time before splinter watchdog starts counting breaches. |
| `splinterWatchdogMaxBreaches` | `5` | `1..64` | Breaches required before watchdog dispels an owner's splinters. |
| `splinterMaxActivePerOwner` | `-1` | `-1..4096` | Max active splinters per owner. `-1` means unlimited. |
| `splinterCasterEnabled` | `true` | boolean | Enables or disables Contained Splinter behavior (Splinter Caster block runtime behavior). |

### Example

```json
{
	"intentRelayMaxRangeBlocks": 16,
	"intentRelayCooldownTicks": 4,
	"intentRelayStepTriggerEnabled": true,
	"splinterCasterEnabled": true,
	"splinterMaxActivePerOwner": 64,
	"splinterWatchdogMaxAvgExecMs": 25.0,
	"splinterWatchdogMaxBreaches": 5
}
```
