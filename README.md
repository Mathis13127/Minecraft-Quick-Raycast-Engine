<p align="center">
  <img src="logo.png" width="180" alt="Quick Voxel Engine Logo"/>
</p>

# Quick Voxel Engine (QVE)

[![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)](https://neoforged.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.229-orange.svg)](https://neoforged.net/)
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://www.oracle.com/java/)
[![License: Custom (QRE-AL)](https://img.shields.io/badge/License-Custom%20(QRE--AL)-blueviolet.svg)](LICENSE)
[![Throughput](https://img.shields.io/badge/Throughput-36M%2B%20rays%2Fsec-success.svg)](#benchmarks)
[![Capacity](https://img.shields.io/badge/Block%20IDs-2.14%20Billion%20(32--bit)-purple.svg)](#key-features--mathematical-foundations)

**Quick Voxel Engine (QVE)** is an ultra-fast, deterministic 3D voxel traversal, chunk indexing, and raycasting library for **Minecraft 1.21.1 (NeoForge)** and standalone Java applications.

Designed specifically for demanding military-grade simulation mods (artillery ballistic predictors, phased-array radars, CIWS point-defense systems, and virtual projectile engines), QVE replaces slow vanilla raycasting with hardware-friendly, zero-allocation algorithms that achieve **over 36 million rays per second**—an acceleration of **180× to 300×** over vanilla Minecraft.

---

## Architecture Overview

```
                                    +------------------------------------------+
                                    |         User Application / Mod           |
                                    |    (Phalanx, Radars, Ballistics, etc.)   |
                                    +------------------------------------------+
                                                         |
                                                         v
                                    +------------------------------------------+
                                    |     VoxelRaycastAPI (NeoForge Bridge)    |
                                    +------------------------------------------+
                                       |                                    |
            [Live Level Sync]          v                                    v          [Unloaded Chunks]
    +--------------------------------------+                        +--------------------------------------+
    |          MinecraftVoxelGrid          |                        |             McaVoxelGrid             |
    +--------------------------------------+                        +--------------------------------------+
    | - Mixin palette interception         |                        | - Direct Anvil (.mca) streaming      |
    | - Real-time dirty cache tracking     |                        | - FastNbtReader (0 allocation)       |
    +--------------------------------------+                        +--------------------------------------+
                       \                                                   /
                        \                                                 /
                         v                                               v
                        +-------------------------------------------------+
                        |           UnifiedVoxelCache (L1 + L2)           |
                        +-------------------------------------------------+
                        | - Direct-mapped L1 bitmask cache                |
                        | - Concurrent lock-free L2 chunk storage         |
                        | - 32-bit Block Identifier System (2.14B capacity|
                        | - 2D Column Heightmaps                          |
                        +-------------------------------------------------+
                                                 |
                                                 v
                        +-------------------------------------------------+
                        |             VoxelDDA Traversal Core             |
                        +-------------------------------------------------+
                        | - Amanatides & Woo 3D DDA                       |
                        | - 512-Byte section bitmask macro-stepping       |
                        | - Sky Fast-Pass (Heightmap2D culling)           |
                        | - Sub-voxel AABB collision (SubBox)             |
                        | - Thread-local RaycastContext (Zero GC alloc)   |
                        +-------------------------------------------------+
```

---

## Key Features & Mathematical Foundations

### 1. 3D Digital Differential Analyzer (Amanatides & Woo)
Standard voxel traversal uses the proven **Amanatides & Woo (1987)** algorithm:
$$\vec{P}(t) = \vec{O} + t \vec{D}$$
- Calculates initial boundary intersections $t_{\text{max}, x}, t_{\text{max}, y}, t_{\text{max}, z}$ and step increments $\Delta t_x, \Delta t_y, \Delta t_z$.
- In each step, advances only along the axis with $\min(t_{\text{max}})$, performing exact face resolution ($-\text{stepX} \implies \text{EAST}$, etc.) without floating-point trigonometry or square roots.

### 2. 512-Byte Section Bitmask & Section Macro-Stepping
- Each $16\times16\times16$ sub-chunk (4,096 voxels) is packed into **64 `long` integers (512 bytes)**.
- If a section contains zero solid voxels (or is completely empty air), the DDA calculates the exact exit point from the $16\times16\times16$ bounding box in $O(1)$ and **macro-steps directly to the next section boundary**, skipping up to 4,095 redundant voxel checks in a single clock cycle.

### 3. Heightmap-Accelerated Sky Fast-Pass
- Every chunk column maintains a compact 2D heightmap (`Heightmap2D`) representing the highest solid block in each column.
- Any ray segment whose $\min(Y_{\text{start}}, Y_{\text{end}}) \ge Y_{\text{max}} + 1.0$ is immediately certified as a **sky miss** in $<22\text{ ns}$, yielding up to **45,000,000 rays/sec**.

### 4. 32-Bit Block Identifier System (Massive Modpack Support)
- Uses **32-bit `int` block identifiers**, scaling to **2,147,483,647 unique blocks and blockstates** without any risk of 16-bit integer overflow.
- Tested and verified on massive modpacks with $>66,000$ block variants.
- Direct-array $O(1)$ lookups maintain 1-cycle CPU performance without hash lookups during traversal.

### 5. Unified BlockState Dictionary & Dynamic Property Indexing
- Two-level hierarchical indexing (`PropertyIndexRegistry` & `BlockStateDictionary`) supporting up to 65,536 property keys and 65,536 values per key.
- Pre-packs blockstate properties into compact bit-vectors for zero-allocation property comparisons.

### 6. Asynchronous Boot-Time Warmup Engine (`VoxelWarmupEngine`)
- Pre-indexes all registered vanilla and modded blocks and their state variants on a background thread upon game boot.
- Eliminates first-time discovery lag spikes during gameplay.

### 7. Dedicated Client Telemetry Card (HUD Overlay)
- Real-time aerospace/cyberpunk telemetry card rendered on the client screen (`ClientHudRenderer`).
- **Fixed Left-Aligned Anchoring**: Pinned at the top-left corner (`x = 8, y = 8`), completely eliminating horizontal text jitter.
- Displays live nanosecond latency, reach up to 99,999m, impact coordinates, struck face, canonical block registry name, 32-bit ID, and blockstates.
- Uses an ultra-lightweight custom network packet (`<30` bytes) and includes a customizable client hotkey (`key.quickvoxelengine.toggle_hud`).

### 8. Direct MCA Anvil Streaming (`McaRegionReader` & `FastNbtReader`)
- Capable of reading raw `.mca` Anvil region files directly from the save directory using zero-copy memory-mapped buffers and single-pass NBT parsing.
- Allows long-range radar scans, ballistic missiles, and artillery simulations to traverse **unloaded terrain thousands of blocks away** without loading chunks or degrading the main server tick loop.

### 9. Work-Stealing Multi-Threading (`RaycastThreadPool`)
- Dedicated worker pool optimized for parallel ray batching (`RaycastThreadPool.parallelFor`).
- Ideal for phased-array radar sweeps (thousands of rays per rotation) and multi-projectile simulations.

---

## Benchmarks

Measurements conducted on an **AMD Ryzen / Intel Core i9**, Java 21, 64-bit JVM:

| Scenario / Engine | Rays Executed | Time (ms) | Throughput (rays/sec) | Average Latency |
| :--- | :--- | :--- | :--- | :--- |
| **Vanilla `level.clip` (Loaded chunks)** | 100,000 | ~510 ms | ~196,000 rays/s | ~5,100 ns/ray |
| **QVE: Sky Fast-Pass (Macro-stepping)** | 200,000 | 4.4 ms | **45,097,862 rays/s** | **22.2 ns/ray** |
| **QVE: Real MCA World (Single-Thread)** | 200,000 | 34.4 ms | **5,804,942 rays/s** | **172.3 ns/ray** |
| **QVE: Real MCA World (4 Workers)** | 400,000 | 51.6 ms | **7,747,854 rays/s** | **129.1 ns/ray** |
| **QVE: Synthetic Dense Grid (Peak)** | 1,000,000 | ~27.0 ms | **36,800,000 rays/s** | **27.1 ns/ray** |

---

## Project Structure

The project is organized into two clean, decoupled modules:

- **`:core`** (`com.pixel.qve.*`): Pure Java 21 library. **Zero Minecraft dependencies**. Contains the 3D DDA traverser, bitmask compressor, UnifiedVoxelCache, MCA Anvil reader, sub-voxel shapes, and thread pool. Can be run in headless tools, test suites, or external simulations.
- **`:quickvoxelengine` (Root Mod)** (`com.pixel.qve.neoforge.*`): NeoForge 1.21.1 mod bridging Minecraft `ServerLevel` and `LevelChunkSection` to `UnifiedVoxelCache` via mixin dirty tracking, network telemetry, client HUD overlay, and exposing `VoxelRaycastAPI`.

---

## API Usage

### 1. Basic Line-of-Sight Check (Live Minecraft Level)

```java
import com.pixel.qve.neoforge.api.VoxelRaycastAPI;
import com.pixel.qve.api.raycast.RayHitResult;
import net.minecraft.world.phys.Vec3;

// Raycast between two world positions
Vec3 start = new Vec3(100.5, 64.0, 200.5);
Vec3 end = new Vec3(100.5, 80.0, 450.0);

RayHitResult result = VoxelRaycastAPI.raycast(serverLevel, start, end);

if (result.isHit()) {
    System.out.println("Hit block ID: " + result.getBlockId());
    System.out.println("Hit coordinates: [" + result.getBlockX() + ", " + result.getBlockY() + ", " + result.getBlockZ() + "]");
    System.out.println("Impact distance: " + result.getDistance() + " meters");
    System.out.println("Face struck: " + result.getFace());
} else {
    System.out.println("Line of sight clear!");
}
```

### 2. Player Line-of-Sight / Targeting

```java
// Raycast in the direction the player is looking up to 128 blocks
RayHitResult result = VoxelRaycastAPI.raycastPlayerLook(player, 128.0);
```

### 3. Direct UnifiedVoxelCache Traversal (Pure Java / Standalone)

```java
import com.pixel.qve.world.cache.UnifiedVoxelCache;
import com.pixel.qve.raycast.VoxelDDA;
import com.pixel.qve.api.raycast.RayHitResult;
import com.pixel.qve.state.BlockIdRegistry;

BlockIdRegistry registry = new BlockIdRegistry();
UnifiedVoxelCache cache = new UnifiedVoxelCache(registry);

RayHitResult hit = new RayHitResult();
boolean blocked = VoxelDDA.raycast(cache, 0.0f, 100.0f, 0.0f, 500.0f, 100.0f, 500.0f, hit);
```

### 4. High-Performance Multi-Threaded Batch (Radar Array / Ballistic Swarm)

```java
import com.pixel.qve.raycast.RaycastThreadPool;

int rayCount = 10000;
RaycastThreadPool.parallelFor(0, rayCount, (context, index) -> {
    // Each worker thread has its own isolated RayHitResult context
    RayHitResult hit = context.hitResult;
    VoxelDDA.raycast(cache, originsX[index], originsY[index], originsZ[index],
                            targetsX[index], targetsY[index], targetsZ[index], hit);
    results[index] = hit.isHit();
});
```

---

## In-Game Commands (`/qve`, `/raycast`, `/qre`)

Quick Voxel Engine includes built-in commands for in-game testing, telemetry, and debugging:

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/qve hud` | All (Level 0) | Toggles the dedicated real-time client telemetry HUD card (99,999m reach). |
| `/qve hud on [distance]` | All (Level 0) | Explicitly enables HUD telemetry with optional custom reach distance. |
| `/qve hud off` | All (Level 0) | Disables HUD telemetry overlay. |
| `/qve inspect [x y z]` | All (Level 0) | Inspects block ID, custom shape, and blockstates at crosshair or specified position. |
| `/qve test [distance]` | All (Level 0) | Fires a single real-time ray along line-of-sight with visual particles. |
| `/qve benchmark <rays> [distance]` | OP (Level 2) | Multi-threaded stress-test (Fibonacci sphere) across live loaded chunks. |
| `/qve benchmark_mca <rx> <rz> <rays>` | OP (Level 2) | Streams an unloaded `r.rx.rz.mca` region directly from disk and benchmarks throughput. |
| `/qve cache stats` | All (Level 0) | Displays real-time cache telemetry (columns, sections, estimated RAM, property keys). |
| `/qve cache clear` | OP (Level 2) | Flushes in-memory voxel caches to allow comparative cold-start testing. |

---

## Integration in Gradle

To include Quick Voxel Engine as a dependency in your mod:

### Via Composite Build (`includeBuild`)

In `settings.gradle`:
```groovy
includeBuild("../raycast_engine") {
    dependencySubstitution {
        substitute module('com.pixel.quickvoxelengine:quickvoxelengine') using project(':')
        substitute module('com.pixel.qve:qve-core') using project(':core')
    }
}
```

In `build.gradle`:
```groovy
dependencies {
    implementation 'com.pixel.quickvoxelengine:quickvoxelengine:1.0.0'
}
```

---

## License & Attribution

This project is licensed under the **Quick Raycast Engine - Modding, Attribution & Non-Commercial License (QRE-AL)**.

### Summary of Permissions & Requirements:
- **Free for Minecraft Projects**: You are free to include, link against, and distribute Quick Voxel Engine as a library or dependency within any public or private Minecraft mod, modpack, or server.
- **Mandatory Attribution**: Any project utilizing this software **MUST prominently and visibly display**:
  1. Author: **`Pixel`**
  2. Software Name: **`Quick Voxel Engine`**
  3. Official Repository Link: `https://github.com/Mathis13127/Minecraft-Quick-Raycast-Engine`
  *(in your mod description on CurseForge, Modrinth, GitHub, and in-game credit screens).*
- **Strictly Non-Commercial**: The software, its binaries, and derived code may not be sold, monetized, or paywalled (e.g. Patreon early access, Tebex, VIP server monetization) without express prior written consent from Pixel.
- **No Concealed Reuse / Anti-Plagiarism**: You may not rename packages, strip headers, or claim authorship of this codebase. Public derivatives must remain open-source and preserve these terms and attribution.

See the full [LICENSE](LICENSE) file for complete legal terms.
