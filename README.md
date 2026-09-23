# Quick Raycast Engine (QRE)

[![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)](https://neoforged.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.229-orange.svg)](https://neoforged.net/)
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://www.oracle.com/java/)
[![License: Custom (QRE-AL)](https://img.shields.io/badge/License-Custom%20(QRE--AL)-blueviolet.svg)](LICENSE)
[![Throughput](https://img.shields.io/badge/Throughput-36M%2B%20rays%2Fsec-success.svg)](#benchmarks)

**Quick Raycast Engine** is an ultra-fast, deterministic 3D voxel traversal and raycasting library for **Minecraft 1.21.1 (NeoForge)** and standalone Java applications. 

Designed specifically for demanding military-grade simulation mods (artillery ballistic predictors, phased-array radars, CIWS point-defense systems, and virtual projectile engines), QRE replaces slow vanilla raycasting with hardware-friendly, zero-allocation algorithms that achieve **over 36 million rays per second**—an acceleration of **180× to 300×** over vanilla Minecraft.

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
                        |           VoxelCache (L1 + L2 Lock-Free)        |
                        +-------------------------------------------------+
                        | - Direct-mapped L1 bitmask cache                |
                        | - Concurrent lock-free L2 chunk storage         |
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

### 4. Zero-Allocation Traversal
- Traversal state is held in pre-allocated thread-local `RaycastContext` structures.
- No `BlockPos`, `BlockHitResult`, or intermediate vector objects are instantiated during ray execution.

### 5. Multi-Tiered Lock-Free Cache (L1 Direct-Mapped + L2 Concurrent)
- **L1 Direct-Mapped Cache**: Bit-indexed slot array for instant ($<1\text{ ns}$) hits on recently traversed chunk sections.
- **L2 Concurrent Storage**: Lock-free hash storage permitting continuous concurrent reads across multiple worker threads while chunk loaders or block changes mutate data.

### 6. Sub-Voxel AABB Intersections (`SubBox` & `ShapeRegistry`)
- Full voxel collisions can lead to false-positives on slabs, stairs, fences, iron bars, and trapdoors.
- QRE resolves non-cubic blocks via compact `SubBox` lists and analytical slab/stairs ray-box clipping, ensuring $100\%$ visual and ballistic precision.

### 7. Direct MCA Anvil Streaming (`McaRegionReader` & `FastNbtReader`)
- Capable of reading raw `.mca` Anvil region files directly from the save directory using zero-copy memory-mapped buffers and single-pass NBT parsing.
- Allows long-range radar scans, ballistic missiles, and artillery simulations to traverse **unloaded terrain thousands of blocks away** without loading chunks or degrading the main server tick loop.

### 8. Work-Stealing Multi-Threading (`RaycastThreadPool`)
- Dedicated worker pool optimized for parallel ray batching (`RaycastThreadPool.parallelFor`).
- Ideal for phased-array radar sweeps (thousands of rays per rotation) and multi-projectile simulations.

---

## Benchmarks

Measurements conducted on an **AMD Ryzen / Intel Core i9**, Java 21, 64-bit JVM:

| Scenario / Engine | Rays Executed | Time (ms) | Throughput (rays/sec) | Average Latency |
| :--- | :--- | :--- | :--- | :--- |
| **Vanilla `level.clip` (Loaded chunks)** | 100,000 | ~510 ms | ~196,000 rays/s | ~5,100 ns/ray |
| **QRE: Sky Fast-Pass (Macro-stepping)** | 200,000 | 4.4 ms | **45,097,862 rays/s** | **22.2 ns/ray** |
| **QRE: Real MCA World (Single-Thread)** | 200,000 | 34.4 ms | **5,804,942 rays/s** | **172.3 ns/ray** |
| **QRE: Real MCA World (4 Workers)** | 400,000 | 51.6 ms | **7,747,854 rays/s** | **129.1 ns/ray** |
| **QRE: Synthetic Dense Grid (Peak)** | 1,000,000 | ~27.0 ms | **36,800,000 rays/s** | **27.1 ns/ray** |

---

## Project Structure

The project is organized into two clean, decoupled modules:

- **`:core`** (`com.pixel.raycast.core.*`): Pure Java 21 library. **Zero Minecraft dependencies**. Contains the 3D DDA traverser, bitmask compressor, VoxelCache, MCA Anvil reader, sub-voxel shapes, and thread pool. Can be run in headless tools, test suites, or external simulations.
- **`:raycastengine` (Root Mod)** (`com.pixel.raycast.neoforge.*`): NeoForge 1.21.1 mod bridging Minecraft `ServerLevel` and `LevelChunkSection` to `VoxelCache` via mixin dirty tracking and exposing `VoxelRaycastAPI`.

---

## API Usage

### 1. Basic Line-of-Sight Check (Live Minecraft Level)

```java
import com.pixel.raycast.neoforge.api.VoxelRaycastAPI;
import com.pixel.raycast.core.api.RayHitResult;
import net.minecraft.world.phys.Vec3;

// Raycast between two world positions
Vec3 start = new Vec3(100.5, 64.0, 200.5);
Vec3 end = new Vec3(100.5, 80.0, 450.0);

RayHitResult result = VoxelRaycastAPI.raycast(serverLevel, start, end);

if (result.isHit()) {
    System.out.println("Hit block at: " + result.getBlockX() + ", " + result.getBlockY() + ", " + result.getBlockZ());
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

### 3. Direct VoxelCache Traversal (Pure Java / Standalone)

```java
import com.pixel.raycast.core.cache.VoxelCache;
import com.pixel.raycast.core.traversal.VoxelDDA;
import com.pixel.raycast.core.api.RayHitResult;

VoxelCache cache = new VoxelCache();
// Populate or bind to an MCA region provider...

RayHitResult hit = new RayHitResult();
boolean blocked = VoxelDDA.raycast(cache, 0.0f, 100.0f, 0.0f, 500.0f, 100.0f, 500.0f, hit);
```

### 4. High-Performance Multi-Threaded Batch (Radar Array / Ballistic Swarm)

```java
import com.pixel.raycast.core.thread.RaycastThreadPool;

RaycastThreadPool pool = RaycastThreadPool.getInstance();

int rayCount = 10000;
pool.parallelFor(0, rayCount, (context, index) -> {
    // Each worker thread has its own isolated RayHitResult context
    RayHitResult hit = context.hitResult;
    VoxelDDA.raycast(cache, originsX[index], originsY[index], originsZ[index],
                            targetsX[index], targetsY[index], targetsZ[index], hit);
    results[index] = hit.isHit();
});
```

---

## In-Game Commands (`/raycast` or `/qre`)

Quick Raycast Engine includes built-in commands for in-game testing, visual debugging, stress-testing, and telemetry:

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/raycast test [distance]` | All (Level 0) | Fires a real-time ray along player line-of-sight with visual particles. Displays hit block, face, coordinates, distance, and latency in nanoseconds. |
| `/raycast benchmark <rays> [distance]` | OP (Level 2) | Multi-threaded stress-test (Fibonacci sphere) across live loaded chunks. Reports total duration, throughput (rays/sec), and hit/miss ratios. |
| `/raycast benchmark_mca <rx> <rz> <rays>` | OP (Level 2) | Streams an unloaded `r.rx.rz.mca` region directly from disk and benchmarks traversal throughput without loading chunks into Minecraft memory. |
| `/raycast cache stats` | All (Level 0) | Displays real-time VoxelCache telemetry (cached chunk columns and active sections). |
| `/raycast cache clear` | OP (Level 2) | Flushes all in-memory voxel caches to allow comparative cold-start testing. |

---

## Integration in Gradle

To include Quick Raycast Engine as a dependency in your mod:

### Via Composite Build (`includeBuild`)

In `settings.gradle`:
```groovy
includeBuild("../raycast_engine") {
    dependencySubstitution {
        substitute module('com.pixel.raycastengine:raycastengine') using project(':')
        substitute module('com.pixel.raycast:raycast-core') using project(':core')
    }
}
```

In `build.gradle`:
```groovy
dependencies {
    implementation 'com.pixel.raycastengine:raycastengine:1.0.0'
}
```

---

## License & Attribution

This project is licensed under the **Quick Raycast Engine - Modding, Attribution & Non-Commercial License (QRE-AL)**.

### Summary of Permissions & Requirements:
- **Free for Minecraft Projects**: You are free to include, link against, and distribute Quick Raycast Engine as a library or dependency within any public or private Minecraft mod, modpack, or server.
- **Mandatory Attribution**: Any project utilizing this software **MUST prominently and visibly display**:
  1. Author: **`Pixel`**
  2. Software Name: **`Quick Raycast Engine`**
  3. Official Repository Link: `https://github.com/Mathis13127/Minecraft-Quick-Raycast-Engine`
  *(in your mod description on CurseForge, Modrinth, GitHub, and in-game credit screens).*
- **Strictly Non-Commercial**: The software, its binaries, and derived code may not be sold, monetized, or paywalled (e.g. Patreon early access, Tebex, VIP server monetization) without express prior written consent from Pixel.
- **No Concealed Reuse / Anti-Plagiarism**: You may not rename packages, strip headers, or claim authorship of this codebase. Public derivatives must remain open-source and preserve these terms and attribution.

See the full [LICENSE](LICENSE) file for complete legal terms.

