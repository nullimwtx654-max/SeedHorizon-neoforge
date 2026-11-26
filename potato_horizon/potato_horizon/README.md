# PotatoHorizon (`potato_horizon`)

An ultra-lightweight, Distant-Horizons-inspired **Level-of-Detail (LOD) native mod** for **[Horizon](https://github.com/horizon-rust/horizon)** (Minecraft: Java Edition 26.3 rewritten in Rust), engineered specifically for low-end / "potato" PCs.

Instead of trying to render 256–1024 chunks with deep quadtrees and gigabytes of SQLite data, PotatoHorizon assumes your PC can comfortably run **6 real chunks** and seamlessly extends the world out to **16 chunks** at almost zero CPU, RAM, or GPU cost.

## Why 6 → 16 Chunks Is Way Lighter

- **Only ~691 LOD chunks total**: A 16-chunk radius circle contains ~804 chunks; subtracting your 6-chunk real radius (~113 chunks) leaves just **~691 LOD chunks**.
- **2-Tier LOD Ring (No Heavy Quadtree)**:
  - **Real Chunks (`0 ..= 6`)**: Standard Horizon chunks.
  - **Tier 1 — Near LOD (`6 < d <= 11`)**: `2×2` block columns (`8×8` grid per chunk). Crisp enough that the transition right past chunk 6 looks natural.
  - **Tier 2 — Far LOD (`11 < d <= 16`)**: `4×4` block columns (`4×4` grid per chunk). Keeps hills, coastlines, rivers, and forests recognizable at 1/16th the horizontal density.
- **< 1 MB Total RAM**: Each column stores at most **3 vertical height-spans** (primary surface + optional water layer + optional tree canopy/overhang), skipping all caves and underground blocks.
- **Strict Potato-PC Frame Budgets**:
  - `UltraPotato`: max 1 mesh/frame, 1 sample/tick, `0.6 ms` frame cap, surface + water only.
  - `Potato` *(default)*: max 2 meshes/frame, 2 samples/tick, `1.0 ms` frame cap, includes tree canopy layer.
  - `Balanced`: max 4 meshes/frame, 4 samples/tick, `1.8 ms` frame cap.
- **2D Greedy Meshing + Cliff Skirts**: Merges flat plains, water, and plateaus into single quads and emits merged cliff skirts between elevation steps, keeping the entire 16-chunk ring around **15k–40k triangles** total.
- **Smooth Fog & Zero-Crack Seam**: Pushes Horizon's `terrain_distance` and fog curve out so fog gently fades into the sky between chunks 12.5 and 16 instead of cutting off in a harsh wall at chunk 6.
- **Zero-Dependency `.plod` Disk Cache**: Compact binary region files (`r.<rx>.<rz>.plod`) with no SQLite dependency.

## Project Layout

```text
potato_horizon/
├── Cargo.toml                         # Standalone Rust crate for `cargo test`
├── compatibility.json                 # Horizon hooks & Nightly revision metadata
├── tools/package.py                   # Builds target/potato_horizon-0.1.0.hnmod
└── mixins/potato_horizon/
    ├── horizon-mixin.toml             # horizon-mixins manifest
    ├── runtime.rs                     # Pure-Rust LOD engine + unit tests (zero external deps)
    ├── bridge.rs                      # Horizon chunk/worldgen/renderer integration layer
    ├── tick.rs                        # HEAD hook: native_tick
    ├── frame.rs                       # HEAD hook: native_frame
    ├── command.rs                     # HEAD hook: native_command (/plod)
    ├── mods.rs                        # HEAD hook: native_mods (Options > Mods)
    ├── settings.rs                    # HEAD hook: native_settings
    ├── settings_click.rs              # HEAD hook: native_settings_click
    └── terrain_distance.rs            # RETURN hook: Game::terrain_distance
```

## Build, Test & Install

### 1. Run Standalone Unit Tests
```bash
cargo test
```

### 2. Package `.hnmod`
```bash
python3 tools/package.py
# Writes target/potato_horizon-0.1.0.hnmod
```

### 3. Build into Horizon
Follow Horizon's [Make a mod](https://playhorizon.dev/docs/make-a-mod) workflow:
```bash
horizon-launcher native-build /path/to/potato_horizon/mixins/potato_horizon --trust-native-code
```
> **Note**: Because `bridge.rs` reads Horizon's internal chunk/world-generator/far-terrain structures and hooks `Game::terrain_distance`, check the adapter methods in `mixins/potato_horizon/bridge.rs` against your installed Horizon Nightly if you update past commit `d009ebd`.

## In-Game Commands (`/plod`)

| Command | Effect |
| :--- | :--- |
| `/plod` | Shows status (real/LOD radius, active chunks, quad/tri count, RAM KB, preset) |
| `/plod on` / `/plod off` | Toggles LOD rendering on or off |
| `/plod distance <8..32>` | Sets LOD horizon radius in chunks (default `16`) |
| `/plod real <2..12>` | Sets your real chunk cutoff (default `6`) |
| `/plod preset <ultra\|potato\|balanced>` | Switches CPU/GPU frame budget preset |
| `/plod caves <on\|off>` | Toggles strict underground/cave culling (default `on`) |
| `/plod save` | Flushes dirty `.plod` region files to disk |
| `/plod clear` | Clears cached LODs and rebuilds around you |
