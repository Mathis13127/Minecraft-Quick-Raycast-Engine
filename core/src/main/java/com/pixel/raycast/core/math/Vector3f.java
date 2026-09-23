package com.pixel.raycast.core.math;

/**
 * High-performance, zero-allocation 3D single-precision float vector.
 * Designed for micro-benchmarked raycast arithmetic with mutable operations.
 */
public final class Vector3f {

    public float x;
    public float y;
    public float z;

    public Vector3f() {
        this(0.0f, 0.0f, 0.0f);
    }

    public Vector3f(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3f set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public Vector3f set(Vector3f other) {
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        return this;
    }

    public Vector3f add(float dx, float dy, float dz) {
        this.x += dx;
        this.y += dy;
        this.z += dz;
        return this;
    }

    public Vector3f add(Vector3f other) {
        this.x += other.x;
        this.y += other.y;
        this.z += other.z;
        return this;
    }

    public Vector3f sub(Vector3f other) {
        this.x -= other.x;
        this.y -= other.y;
        this.z -= other.z;
        return this;
    }

    public Vector3f mul(float scalar) {
        this.x *= scalar;
        this.y *= scalar;
        this.z *= scalar;
        return this;
    }

    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

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

    public float dot(Vector3f other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    public float distanceSquared(Vector3f other) {
        float dx = this.x - other.x;
        float dy = this.y - other.y;
        float dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public float distance(Vector3f other) {
        return (float) Math.sqrt(distanceSquared(other));
    }

    @Override
    public String toString() {
        return String.format("(%.3f, %.3f, %.3f)", x, y, z);
    }
}
