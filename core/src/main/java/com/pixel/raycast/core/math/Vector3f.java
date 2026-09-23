package com.pixel.raycast.core.math;

/**
 * High-performance, zero-allocation 3D single-precision float vector.
 * Designed for micro-benchmarked raycast arithmetic with mutable operations.
 */
public final class Vector3f {

    /** X coordinate component. */
    public float x;
    /** Y coordinate component. */
    public float y;
    /** Z coordinate component. */
    public float z;

    /**
     * Constructs a zero vector (0, 0, 0).
     */
    public Vector3f() {
        this(0.0f, 0.0f, 0.0f);
    }

    /**
     * Constructs a vector with explicit components.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public Vector3f(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Sets the components of this vector.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return This vector for chaining
     */
    public Vector3f set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /**
     * Copies coordinates from another vector.
     *
     * @param other Source vector
     * @return This vector for chaining
     */
    public Vector3f set(Vector3f other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        return this;
    }

    /**
     * Adds scalar delta components to this vector.
     *
     * @param dx Delta X
     * @param dy Delta Y
     * @param dz Delta Z
     * @return This vector for chaining
     */
    public Vector3f add(float dx, float dy, float dz) {
        this.x += dx;
        this.y += dy;
        this.z += dz;
        return this;
    }

    /**
     * Adds another vector to this vector in place.
     *
     * @param other Vector to add
     * @return This vector for chaining
     */
    public Vector3f add(Vector3f other) {
        this.x += other.x;
        this.y += other.y;
        this.z += other.z;
        return this;
    }

    /**
     * Subtracts another vector from this vector in place.
     *
     * @param other Vector to subtract
     * @return This vector for chaining
     */
    public Vector3f sub(Vector3f other) {
        this.x -= other.x;
        this.y -= other.y;
        this.z -= other.z;
        return this;
    }

    /**
     * Multiplies this vector by a scalar value in place.
     *
     * @param scalar Multiplier
     * @return This vector for chaining
     */
    public Vector3f mul(float scalar) {
        this.x *= scalar;
        this.y *= scalar;
        this.z *= scalar;
        return this;
    }

    /**
     * Computes the squared magnitude of this vector.
     *
     * @return Length squared
     */
    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    /**
     * Computes the Euclidean magnitude of this vector.
     *
     * @return Vector length
     */
    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    /**
     * Normalizes this vector to unit length in place.
     *
     * @return This vector for chaining
     */
    public Vector3f normalize() {
        float lenSq = lengthSquared();
        if (lenSq > 1e-12f) {
            float invLen = 1.0f / (float) Math.sqrt(lenSq);
            this.x *= invLen;
            this.y *= invLen;
            this.z *= invLen;
        }
        return this;
    }

    /**
     * Computes the dot product with another vector.
     *
     * @param other Second vector
     * @return Dot product scalar
     */
    public float dot(Vector3f other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    /**
     * Computes squared Euclidean distance to another vector.
     *
     * @param other Target vector
     * @return Distance squared
     */
    public float distanceSquared(Vector3f other) {
        float dx = this.x - other.x;
        float dy = this.y - other.y;
        float dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Computes Euclidean distance to another vector.
     *
     * @param other Target vector
     * @return Euclidean distance
     */
    public float distance(Vector3f other) {
        return (float) Math.sqrt(distanceSquared(other));
    }

    @Override
    public String toString() {
        return String.format("(%.3f, %.3f, %.3f)", x, y, z);
    }
}
