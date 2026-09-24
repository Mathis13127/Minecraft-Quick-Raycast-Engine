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

    /** Empty non-colliding voxel shape. */
    public static final VoxelShape EMPTY = new VoxelShape(new SubBox[0], false);
    /** Standard solid 1x1x1 full cube shape. */
    public static final VoxelShape FULL_CUBE = new VoxelShape(new SubBox[]{SubBox.FULL}, true);

    // Slabs
    /** Bottom slab shape [0..0.5 Y]. */
    public static final VoxelShape SLAB_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 1f)
    }, false);
    /** Top slab shape [0.5..1.0 Y]. */
    public static final VoxelShape SLAB_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 1f)
    }, false);

    // Stairs Bottom
    /** North-facing bottom stairs shape. */
    public static final VoxelShape STAIRS_NORTH_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 1f),
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 0.5f)
    }, false);
    /** South-facing bottom stairs shape. */
    public static final VoxelShape STAIRS_SOUTH_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 1f),
        new SubBox(0f, 0.5f, 0.5f, 1f, 1f, 1f)
    }, false);
    /** West-facing bottom stairs shape. */
    public static final VoxelShape STAIRS_WEST_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 1f),
        new SubBox(0f, 0.5f, 0f, 0.5f, 1f, 1f)
    }, false);
    /** East-facing bottom stairs shape. */
    public static final VoxelShape STAIRS_EAST_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 1f),
        new SubBox(0.5f, 0.5f, 0f, 1f, 1f, 1f)
    }, false);

    // Stairs Top (Upside Down)
    /** North-facing top stairs shape. */
    public static final VoxelShape STAIRS_NORTH_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 1f),
        new SubBox(0f, 0f, 0f, 1f, 0.5f, 0.5f)
    }, false);
    /** South-facing top stairs shape. */
    public static final VoxelShape STAIRS_SOUTH_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 1f),
        new SubBox(0f, 0f, 0.5f, 1f, 0.5f, 1f)
    }, false);
    /** West-facing top stairs shape. */
    public static final VoxelShape STAIRS_WEST_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 1f),
        new SubBox(0f, 0f, 0f, 0.5f, 0.5f, 1f)
    }, false);
    /** East-facing top stairs shape. */
    public static final VoxelShape STAIRS_EAST_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.5f, 0f, 1f, 1f, 1f),
        new SubBox(0.5f, 0f, 0f, 1f, 0.5f, 1f)
    }, false);

    // Panes & Thin Panels
    /** Bottom trapdoor shape. */
    public static final VoxelShape TRAPDOOR_BOTTOM = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0f, 1f, 0.1875f, 1f)
    }, false);
    /** Top trapdoor shape. */
    public static final VoxelShape TRAPDOOR_TOP = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0.8125f, 0f, 1f, 1f, 1f)
    }, false);
    /** North-South thin pane / iron bar shape. */
    public static final VoxelShape PANE_NS = new VoxelShape(new SubBox[]{
        new SubBox(0.4375f, 0f, 0f, 0.5625f, 1f, 1f)
    }, false);
    /** East-West thin pane / iron bar shape. */
    public static final VoxelShape PANE_EW = new VoxelShape(new SubBox[]{
        new SubBox(0f, 0f, 0.4375f, 1f, 1f, 0.5625f)
    }, false);
    /** Crossed thin pane / iron bar post shape. */
    public static final VoxelShape PANE_CROSS = new VoxelShape(new SubBox[]{
        new SubBox(0.4375f, 0f, 0f, 0.5625f, 1f, 1f),
        new SubBox(0f, 0f, 0.4375f, 1f, 1f, 0.5625f)
    }, false);

    /**
     * Constructs a VoxelShape from an array of SubBoxes.
     *
     * @param boxes Array of constituent sub-voxel bounding boxes
     */
    public VoxelShape(SubBox[] boxes) {
        this(boxes, boxes.length == 1 && boxes[0].isFullBlock());
    }

    private VoxelShape(SubBox[] boxes, boolean isFull) {
        this.boxes = Objects.requireNonNull(boxes, "Boxes array cannot be null");
        this.isFull = isFull;
    }

    /**
     * Checks if this shape represents an un-occluded 1x1x1 full solid cube.
     *
     * @return True if full cube
     */
    public boolean isFullCube() {
        return isFull;
    }

    /**
     * Checks if this shape has zero collision boxes.
     *
     * @return True if empty
     */
    public boolean isEmpty() {
        return boxes.length == 0;
    }

    /**
     * Retrieves the array of constituent sub-boxes.
     *
     * @return SubBox array
     */
    public SubBox[] getBoxes() {
        return boxes;
    }

    /**
     * Intersects the ray against the constituent sub-boxes of this shape.
     *
     * @param ox     Ray origin X relative to voxel min corner
     * @param oy     Ray origin Y relative to voxel min corner
     * @param oz     Ray origin Z relative to voxel min corner
     * @param dx     Normalized ray direction X
     * @param dy     Normalized ray direction Y
     * @param dz     Normalized ray direction Z
     * @param tEntry Entry distance into voxel
     * @param tExit  Exit distance out of voxel
     * @param hitOut Mutable container receiving closest impact data
     * @return True if ray intersects any sub-box within [tEntry, tExit]
     */
    public boolean intersect(double ox, double oy, double oz,
                             double dx, double dy, double dz,
                             double tEntry, double tExit,
                             SubBox.SubBoxHit hitOut) {
        if (isFull) {
            hitOut.t = Math.max(0.0, tEntry);
            hitOut.face = VoxelFace.NONE;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VoxelShape that = (VoxelShape) o;
        return isFull == that.isFull && Arrays.equals(boxes, that.boxes);
    }

    @Override
    public int hashCode() {
        return 31 * Boolean.hashCode(isFull) + Arrays.hashCode(boxes);
    }
}
