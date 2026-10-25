package com.gexrture.pose

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(this dot this)
    fun normalized(): Vec3 = norm().let { n -> if (n == 0.0) this else Vec3(x / n, y / n, z / n) }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        /** OpenGL-style frame used everywhere after [GlassesFrame]: x right, y up, z back. */
        val UP = Vec3(0.0, 1.0, 0.0)
        val FORWARD = Vec3(0.0, 0.0, -1.0)
        val RIGHT = Vec3(1.0, 0.0, 0.0)
    }
}

/** Unit quaternion (w, x, y, z). As an orientation it maps body vectors to world vectors. */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conj() = Quat(w, -x, -y, -z)

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return Quat(w / n, x / n, y / n, z / n)
    }

    /** Rotates [v] by this quaternion (q v q*). */
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2.0
        return v + t * w + (u cross t)
    }

    /** Column-major 4×4 rotation matrix, as OpenGL expects. */
    fun toMat4(): FloatArray {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return floatArrayOf(
            (1 - 2 * (yy + zz)).toFloat(), (2 * (xy + wz)).toFloat(), (2 * (xz - wy)).toFloat(), 0f,
            (2 * (xy - wz)).toFloat(), (1 - 2 * (xx + zz)).toFloat(), (2 * (yz + wx)).toFloat(), 0f,
            (2 * (xz + wy)).toFloat(), (2 * (yz - wx)).toFloat(), (1 - 2 * (xx + yy)).toFloat(), 0f,
            0f, 0f, 0f, 1f,
        )
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        fun axisAngle(axis: Vec3, angleRad: Double): Quat {
            val a = axis.normalized()
            val s = sin(angleRad / 2)
            return Quat(cos(angleRad / 2), a.x * s, a.y * s, a.z * s)
        }

        /** Shortest rotation taking unit vector [a] to unit vector [b]. */
        fun fromTo(a: Vec3, b: Vec3): Quat {
            val an = a.normalized()
            val bn = b.normalized()
            val c = an dot bn
            if (c < -0.999999) {
                val ortho = if (kotlin.math.abs(an.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
                return axisAngle(an cross ortho, Math.PI)
            }
            val v = an cross bn
            return Quat(1 + c, v.x, v.y, v.z).normalized()
        }
    }
}
