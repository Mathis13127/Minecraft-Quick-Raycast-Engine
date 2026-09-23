package com.pixel.raycast.core.shape;

import com.pixel.raycast.core.voxel.VoxelFace;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable collection of sub-voxel bounding boxes representing a block's physical collision model.
 * Enables zero-allocation, millimeter-accurate sub-voxel raycasting through non-cube blocks.
 */
public final class VoxelShape {

    private final SubBox[] boxes;
    private final boolean isFull;

    public static final VoxelShape EMPTY = new VoxelShape(new SubBox[0], false);
    public static final VoxelShape FULL_CUBE = new VoxelShape(new SubBox[]{SubBox.FULL}, true);

    // Slabs
    public static final VoxelShape SLAB_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 1f)
    }, false);
    public static final VoxelShape SLAB_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 1f)
    }, false);

    // Stairs Bottom
    public static final VoxelShape STAIRS_NORTH_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 1f),
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 0.5f)
    }, false);
    public static final VoxelShape STAIRS_SOUTH_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 1f),
        new SubBox(0f, 0.5f, 0.5f, 1f, 1f, 1f)
    }, false);
    public static final VoxelShape STAIRS_WEST_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 1f),
        new SubBox(0f, 0.5f, 0f, 0.5f, 1f, 1f)
    }, false);
    public static final VoxelShape STAIRS_EAST_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 1f),
        new SubBox(0.5f, 0.5f, 0f, 1f, 1f, 1f)
    }, false);

    // Stairs Top (Upside Down)
    public static final VoxelShape STAIRS_NORTH_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 1f),
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 0.5f)
    }, false);
    public static final VoxelShape STAIRS_SOUTH_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 1f),
        new SubBox(0f, 0f, 0.5f, 1f, 0.5f, 1f)
    }, false);
    public static final VoxelShape STAIRS_WEST_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 1f),
        new SubBox(0f, 0f, 0f, 0.5f, 0.5f, 1f)
    }, false);
    public static final VoxelShape STAIRS_EAST_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 1f),
        new SubBox(0.5f, 0f, 0f, 1f, 0.5f, 1f)
    }, false);

    // Panes & Thin Panels
    public static final VoxelShape TRAPDOOR_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.1875f, 1f)
    }, false);
    public static final VoxelShape TRAPDOOR_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.8125f, 0f, 1f, 1f, 1f)
    }, false);
    public static final VoxelShape PANE_NS = new VoxelShape(new SubBox[]{
        new SubBox(0.4375f, 0f, 0f, 0.5625f, 1f, 1f)
    }, false);
    public static final VoxelShape PANE_EW = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0.4375f, 1f, 1f, 0.5625f)
    }, false);
    public static final VoxelShape PANE_CROSS = new VoxelShape(new SubBox[]{
        new SubBox(0.4375f, 0f, 0f, 0.5625f, 1f, 1f),
        new SubBox(0f, 0f, 0.4375f, 1f, 1f, 0.5625f)
    }, false);

    public VoxelShape(SubBox[] boxes) {
        this(boxes, boxes.length == 1 && boxes[0].isFullBlock());
    }

    private VoxelShape(SubBox[] boxes, boolean isFull) {
        this.boxes = Objects.requireNonNull(boxes, "Boxes array cannot be null");
        this.isFull = isFull;
    }

    public boolean isFullCube() {
        return isFull;
    }

    public boolean isEmpty() {
        return boxes.length == 0;
    }

    public SubBox[] getBoxes() {
        return boxes;
    }

    /**
     * Intersects the ray against the constituent sub-boxes of this shape.
     */
    public boolean intersect(double ox, double oy, double oz,
                             double dx, double dy, double dz,
                             double tEntry, double tExit,
                             SubBox.SubBoxHit hitOut) {
        if (isFull) {
            hitOut.t = Math.max(0.0, tEntry);
            return true;
        }
        if (boxes.length == 0) {
            return false;
        }

        double nearestT = Double.MAX_VALUE;
        VoxelFace nearestFace = VoxelFace.NONE;
        SubBox.SubBoxHit temp = new SubBox.SubBoxHit();

        for (SubBox box : boxes) {
            temp.reset();
            if (box.intersect(ox, oy, oz, dx, dy, dz, tEntry, tExit, temp)) {
                if (temp.t < nearestT) {
                    nearestT = temp.t;
                    nearestFace = temp.face;
                }
            }
        }

        if (nearestT <= tExit && nearestT < Double.MAX_VALUE) {
            hitOut.t = nearestT;
            hitOut.face = nearestFace;
            return true;
        }
        return false;
    }
}
