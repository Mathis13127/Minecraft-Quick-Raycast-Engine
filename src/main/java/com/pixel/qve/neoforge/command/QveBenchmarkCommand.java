package com.pixel.qve.neoforge.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pixel.qve.api.raycast.RayHitResult;
import com.pixel.qve.mca.McaRegionReader;
import com.pixel.qve.mca.McaVoxelGrid;
import com.pixel.qve.neoforge.api.VoxelRaycastAPI;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelBridge;
import com.pixel.qve.neoforge.bridge.MinecraftVoxelGrid;
import com.pixel.qve.raycast.RaycastThreadPool;
import com.pixel.qve.raycast.VoxelDDA;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

/**
 * Handles all real-time and offline raycasting benchmark stress tests and trajectory testing commands.
 */
public final class QveBenchmarkCommand {

    private QveBenchmarkCommand() {}

    /**
     * Registers the {@code test}, {@code benchmark}, and {@code benchmark_mca} commands onto the root QVE builder.
     *
     * @param root Root {@code /qve} literal argument builder
     */
    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("test")
                .executes(ctx -> executeTest(ctx, 256.0))
                .then(Commands.argument("distance", DoubleArgumentType.doubleArg(0.1))
                        .executes(ctx -> executeTest(ctx, DoubleArgumentType.getDouble(ctx, "distance")))));

        root.then(Commands.literal("benchmark")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("rays", IntegerArgumentType.integer(100, 10_000_000))
                        .executes(ctx -> executeBenchmark(ctx, IntegerArgumentType.getInteger(ctx, "rays"), 256.0))
                        .then(Commands.argument("distance", DoubleArgumentType.doubleArg(0.1))
                                .executes(ctx -> executeBenchmark(ctx,
                                        IntegerArgumentType.getInteger(ctx, "rays"),
                                        DoubleArgumentType.getDouble(ctx, "distance"))))));

        root.then(Commands.literal("benchmark_mca")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("regionX", IntegerArgumentType.integer())
                        .then(Commands.argument("regionZ", IntegerArgumentType.integer())
                                .then(Commands.argument("rays", IntegerArgumentType.integer(100, 10_000_000))
                                        .executes(ctx -> executeBenchmarkMca(ctx,
                                                IntegerArgumentType.getInteger(ctx, "regionX"),
                                                IntegerArgumentType.getInteger(ctx, "regionZ"),
                                                IntegerArgumentType.getInteger(ctx, "rays")))))));
    }

    private static int executeTest(CommandContext<CommandSourceStack> ctx, double distance) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Vec3 start;
        Vec3 look;

        if (source.getEntity() instanceof ServerPlayer player) {
            start = player.getEyePosition();
            look = player.getLookAngle().normalize();
        } else {
            start = source.getPosition();
            look = new Vec3(0, -1, 0); // default straight down for console/blocks
        }

        Vec3 end = start.add(look.scale(distance));
        ServerPlayer player = (source.getEntity() instanceof ServerPlayer sp) ? sp : null;

        RaycastThreadPool.submit(() -> {
            try {
                RayHitResult hit = new RayHitResult();
                long t0 = System.nanoTime();
                VoxelRaycastAPI.raycast(level, start, end, hit);
                long elapsedNs = System.nanoTime() - t0;

                server.execute(() -> {
                    if (player != null && !player.hasDisconnected()) {
                        double rayLength = hit.isHit() ? hit.getDistance() : distance;
                        int particleCount = (int) Math.min(rayLength, 96.0);
                        for (int i = 1; i <= particleCount; i++) {
                            Vec3 p = start.add(look.scale(i));
                            level.sendParticles(player, ParticleTypes.CRIT, false, p.x, p.y, p.z, 1, 0, 0, 0, 0);
                        }
                    }

                    if (hit.isHit()) {
                        BlockPos pos = new BlockPos(hit.getBlockX(), hit.getBlockY(), hit.getBlockZ());
                        BlockState state = level.getBlockState(pos);
                        String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

                        source.sendSuccess(() -> Component.literal(String.format(
                                "§6[QVE] §aHIT §7at §e[%d, %d, %d] §7(§b%s§7, face: §f%s§7, dist: §f%.2fm§7, time: §d%,d ns§7)",
                                hit.getBlockX(), hit.getBlockY(), hit.getBlockZ(),
                                blockName,
                                hit.getFace(),
                                hit.getDistance(),
                                elapsedNs
                        )), false);
                    } else {
                        source.sendSuccess(() -> Component.literal(String.format(
                                "§6[QVE] §eMISS §7(Clear line of sight for §f%.1fm§7, time: §d%,d ns§7)",
                                distance,
                                elapsedNs
                        )), false);
                    }
                });
            } catch (Exception e) {
                server.execute(() -> source.sendFailure(Component.literal("§c[QVE] Raycast error: " + e.getMessage())));
            }
        });

        return 1;
    }

    private static int executeBenchmark(CommandContext<CommandSourceStack> ctx, int rays, double distance) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Vec3 origin = source.getPosition();

        float ox = (float) origin.x;
        float oy = (float) origin.y;
        float oz = (float) origin.z;
        float dist = (float) distance;

        source.sendSuccess(() -> Component.literal(String.format(
                "§6[QVE] §7Starting background benchmark: §f%,d rays §7at §f%.1fm§7...",
                rays, distance
        )), false);

        MinecraftVoxelGrid grid = MinecraftVoxelBridge.getOrCreateGrid(level);
        RaycastThreadPool.submit(() -> {
            try {
                LongAdder hitCounter = new LongAdder();
                LongAdder missCounter = new LongAdder();
                long t0 = System.nanoTime();
                RaycastThreadPool.parallelFor(0, rays, i -> {
                    RaycastThreadPool.RaycastContext context = RaycastThreadPool.getThreadLocalContext();
                    // Fibonacci sphere distribution around origin
                    double phi = Math.acos(1.0 - 2.0 * (i + 0.5) / rays);
                    double theta = Math.PI * (1.0 + Math.sqrt(5.0)) * i;
                    float dx = (float) (Math.sin(phi) * Math.cos(theta));
                    float dy = (float) (Math.sin(phi) * Math.sin(theta));
                    float dz = (float) Math.cos(phi);

                    float tx = ox + dx * dist;
                    float ty = oy + dy * dist;
                    float tz = oz + dz * dist;

                    var ray = context.ray.setFromPoints(ox, oy, oz, tx, ty, tz);
                    RayHitResult result = context.hitResult;
                    VoxelDDA.trace(ray, grid, result);
                    if (result.isHit()) {
                        hitCounter.increment();
                    } else {
                        missCounter.increment();
                    }
                });
                long elapsedNs = System.nanoTime() - t0;

                double elapsedMs = elapsedNs / 1_000_000.0;
                double elapsedSec = elapsedNs / 1_000_000_000.0;
                double throughput = (double) rays / Math.max(elapsedSec, 1e-9);
                double latencyNs = (double) elapsedNs / rays;

                server.execute(() -> source.sendSuccess(() -> Component.literal(String.format(
                        "§6=== [Quick Voxel Engine: Live Benchmark] ===\n" +
                        "§7Total Rays: §f%,d §7| Distance: §f%.1fm\n" +
                        "§7Duration: §f%.2f ms §7| Latency: §d%.1f ns/ray\n" +
                        "§7Throughput: §a§l%,.0f rays/second\n" +
                        "§7Hits: §e%,d §7| Misses: §b%,d",
                        rays, distance,
                        elapsedMs, latencyNs,
                        throughput,
                        hitCounter.sum(), missCounter.sum()
                )), true));
            } catch (Exception e) {
                server.execute(() -> source.sendFailure(Component.literal("§c[QVE] Benchmark error: " + e.getMessage())));
            }
        });

        return 1;
    }

    private static int executeBenchmarkMca(CommandContext<CommandSourceStack> ctx, int rx, int rz, int rays) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();

        Path worldDir = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dimDir = DimensionType.getStorageFolder(level.dimension(), worldDir);
        Path regionPath = dimDir.resolve("region").resolve("r." + rx + "." + rz + ".mca");

        if (!Files.exists(regionPath)) {
            // Also fallback to root region folder
            regionPath = worldDir.resolve("region").resolve("r." + rx + "." + rz + ".mca");
        }

        if (!Files.exists(regionPath)) {
            final Path searched = regionPath;
            source.sendFailure(Component.literal("§c[QVE] Region file r." + rx + "." + rz + ".mca not found on disk at: " + searched));
            return 0;
        }

        final Path targetRegion = regionPath;
        source.sendSuccess(() -> Component.literal(String.format(
                "§6[QVE MCA Benchmark] §7Streaming unloaded region r.%d.%d.mca from disk...",
                rx, rz
        )), false);

        RaycastThreadPool.submit(() -> {
            var registry = MinecraftVoxelBridge.getBlockRegistry();
            try (McaVoxelGrid mcaGrid = new McaVoxelGrid(registry);
                 McaRegionReader reader = new McaRegionReader(targetRegion, registry)) {

                long tLoad0 = System.nanoTime();
                int sectionsLoaded = mcaGrid.preloadRegion(reader);
                long loadMs = (System.nanoTime() - tLoad0) / 1_000_000;

                int minX = rx << 9;
                int minZ = rz << 9;
                LongAdder hits = new LongAdder();
                LongAdder misses = new LongAdder();

                long t0 = System.nanoTime();
                RaycastThreadPool.parallelFor(0, rays, i -> {
                    RaycastThreadPool.RaycastContext context = RaycastThreadPool.getThreadLocalContext();
                    float sx = minX + (float) ((i * 17) % 512);
                    float sz = minZ + (float) ((i * 31) % 512);
                    float sy = 320.0f;
                    float ex = sx + 20.0f;
                    float ez = sz + 20.0f;
                    float ey = -64.0f;

                    var ray = context.ray.setFromPoints(sx, sy, sz, ex, ey, ez);
                    RayHitResult result = context.hitResult;
                    VoxelDDA.trace(ray, mcaGrid, result);
                    if (result.isHit()) {
                        hits.increment();
                    } else {
                        misses.increment();
                    }
                });
                long elapsedNs = System.nanoTime() - t0;

                double elapsedMs = elapsedNs / 1_000_000.0;
                double elapsedSec = elapsedNs / 1_000_000_000.0;
                double throughput = (double) rays / Math.max(elapsedSec, 1e-9);
                double latencyNs = (double) elapsedNs / rays;

                server.execute(() -> source.sendSuccess(() -> Component.literal(String.format(
                        "§6=== [Quick Voxel Engine: Unloaded MCA Benchmark] ===\n" +
                        "§7Region File: §fr.%d.%d.mca §7(Sections: §f%d §7loaded in §f%d ms§7)\n" +
                        "§7Rays: §f%,d §7| Duration: §f%.2f ms §7| Latency: §d%.1f ns/ray\n" +
                        "§7Throughput: §a§l%,.0f rays/second\n" +
                        "§7Hits: §e%,d §7| Misses: §b%,d",
                        rx, rz, sectionsLoaded, loadMs,
                        rays, elapsedMs, latencyNs,
                        throughput,
                        hits.sum(), misses.sum()
                )), true));
            } catch (Exception e) {
                server.execute(() -> source.sendFailure(Component.literal("§c[QVE] Error running MCA benchmark: " + e.getMessage())));
            }
        });

        return 1;
    }
}
