package com.gexrture.pose

/**
 * Mahony-style complementary filter: integrates the gyro and pulls pitch/roll toward the
 * accelerometer's gravity direction. Yaw has no absolute reference and drifts (measured on
 * device: ≈0.2°/min after bias removal), which Recenter handles.
 *
 * All vectors are in the GL body frame (see [GlassesFrame]); [q] maps body → world, world y = up.
 */
class OrientationFilter(
    /** Proportional gain on the gravity error (rad/s per unit error). */
    private val kp: Double = 1.0,
    /** Integral gain; learns residual pitch/roll gyro bias. Yaw bias is unobservable here. */
    private val ki: Double = 0.02,
) {
    var q: Quat = Quat.IDENTITY
        private set
    var initialized = false
        private set
    private var errorIntegral = Vec3.ZERO

    /** Tilt from gravity, yaw = 0. The accelerometer reads +1 g "up" at rest. */
    fun initFromAccel(accel: Vec3) {
        q = Quat.fromTo(accel.normalized(), Vec3.UP)
        errorIntegral = Vec3.ZERO
        initialized = true
    }

    /**
     * @param gyroRad body angular rate, rad/s, bias already removed
     * @param accelG body specific force in g
     * @param dt seconds since the previous sample
     */
    fun update(gyroRad: Vec3, accelG: Vec3, dt: Double) {
        if (!initialized) {
            initFromAccel(accelG)
            return
        }
        var w = gyroRad
        val an = accelG.norm()
        // Only trust gravity when the glasses are not accelerating noticeably.
        if (an in 0.85..1.15) {
            val measuredUp = accelG * (1.0 / an)
            val estimatedUp = q.conj().rotate(Vec3.UP)
            val e = measuredUp cross estimatedUp
            if (ki > 0) errorIntegral += e * dt
            w = w + e * kp + errorIntegral * ki
        }
        val dq = q * Quat(0.0, w.x, w.y, w.z)
        q = Quat(
            q.w + 0.5 * dt * dq.w,
            q.x + 0.5 * dt * dq.x,
            q.y + 0.5 * dt * dq.y,
            q.z + 0.5 * dt * dq.z,
        ).normalized()
    }
}
