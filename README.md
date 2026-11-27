# SeedHorizon (NeoForge 21.1.248 / Minecraft 1.21.1)

Standalone client-side NeoForge mod that delivers **32-chunk (32x) horizon rendering** while capping actual Minecraft chunk generation/rendering at **6 chunks (6x)**, pushing terrain fog out to the LOD horizon, and staying compatible with **Sodium** (and Embeddium).

## How It Works
- **6x Real-World Render Cap (`OptionsMixin`, `SeedStateManager`)**: Caps `Options#getEffectiveRenderDistance()` at `6` chunks (configurable) and syncs the integrated singleplayer server's view distance to `6`, so neither vanilla nor Sodium wastes CPU/GPU meshing distant real chunks.
- **32x SeedGen-Style LOD Horizon (`SeedColumnSampler`, `LodSection`, `LodRenderHandler`)**: Samples `RandomState.router().finalDensity()` and `BiomeSource.getNoiseBiome(...)` asynchronously on a background worker pool (4-block step near, 8-block step far) with biome grass/water/snow/badlands/foliage colors and slope shading, uploading compact `VertexBuffer`s at `RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS`.
- **Fog Pushback (`FogHandler`, `FogRendererMixin`, `GameRendererMixin`)**: Extends `GameRenderer#getDepthFar()` so the 32-chunk horizon is never clipped by the camera far plane, and overrides `ViewportEvent.RenderFog` + `FogRenderer.setupFog` (`FogMode.FOG_TERRAIN`, `FogType.NONE`) so terrain fog wraps the 32-chunk LOD horizon instead of fogging out at 6 chunks. Fluid fog (water/lava/powder snow) and blindness/darkness effects remain untouched.
- **Sodium Compatibility**: Avoids fragile internal Sodium mixins. Sodium respects `Options#getEffectiveRenderDistance()`, reads `RenderSystem.getShaderFogStart/End()` for its chunk shaders, and shares the depth buffer with `RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS`.

## Building
Requires **Java 21** and internet access for Gradle to download NeoForge `21.1.248` & Minecraft `1.21.1` dependencies:
```bash
gradle build
```
(Or run `./gradlew build` after generating the Gradle wrapper with `gradle wrapper`.)

The compiled mod JAR will be output to `build/libs/seedhorizon-neoforge-1.21.1-1.0.0.jar`.

## Commands & Config
- Config file: `config/seedhorizon-client.toml`
- `/seedhorizon status` — prints current LOD distance, real cap, active sections, and Sodium detection.
- `/seedhorizon seed <seed>` — sets the world seed manually for multiplayer servers.
- `/seedhorizon reload` — clears and regenerates the LOD cache.
