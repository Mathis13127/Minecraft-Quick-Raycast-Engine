package com.pixel.raycast.neoforge.mixin;

import com.pixel.raycast.core.cache.VoxelChunkColumn;
import com.pixel.raycast.core.voxel.VoxelSection;
import com.pixel.raycast.neoforge.bridge.MinecraftVoxelBridge;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin injecting IRaycastChunkSection into LevelChunkSection and capturing
 * setBlockState mutations for instant dirty tracking in the VoxelCache.
 */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin implements IRaycastChunkSection {

    /**
     * Default constructor for LevelChunkSectionMixin.
     */
    protected LevelChunkSectionMixin() {}

    @Unique
    private VoxelSection raycast$voxelSection;

    @Unique
    private VoxelChunkColumn raycast$voxelColumn;

    @Unique
    private int raycast$sectionY;

    @Override
    public VoxelSection raycast$getVoxelSection() {
        return raycast$voxelSection;
    }

    @Override
    public void raycast$setVoxelSection(VoxelSection section) {
        this.raycast$voxelSection = section;
    }

    @Override
    public VoxelChunkColumn raycast$getVoxelColumn() {
        return raycast$voxelColumn;
    }

    @Override
    public void raycast$setVoxelColumn(VoxelChunkColumn column, int sectionY) {
        this.raycast$voxelColumn = column;
        this.raycast$sectionY = sectionY;
    }

    @Override
    public int raycast$getSectionY() {
        return raycast$sectionY;
    }

    @Inject(
        method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN")
    )
    private void raycast$onSetBlockState(int x, int y, int z, BlockState state, boolean lock,
                                         CallbackInfoReturnable<BlockState> cir) {
        MinecraftVoxelBridge.onBlockStateChanged(this, x, y, z, state);
    }
}
