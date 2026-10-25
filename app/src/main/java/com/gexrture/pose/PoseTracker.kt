package com.gexrture.pose

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Turns raw IMU samples (gyro deg/s, accel g, device timestamps) into a head orientation.
 *
 * Gyro bias is measured per session (on device it differed between sessions: (−0.13, −0.14,
 * 0.01) vs (−0.27, −0.21, −0.03) deg/s) from the first 1 s window in which the glasses are still.
 * Until then the filter runs with zero bias and [PoseSnapshot.calibrated] is false.
 *
 * Fed from the single IMU reader thread; [latest] is safe to call from any thread.
 */
class PoseTracker {
    data class PoseSnapshot(
        /** Head orientation, GL body → world. */
        val q: Quat,
        /** System.nanoTime() when the sample that produced this pose was received. */
        val arrivalNanos: Long,
        val calibrated: Boolean,
        /** Changes whenever the world frame is reset (new session, axis flip). */
        val epoch: Int,
        val biasDegPerS: Vec3,
    )

    @Volatile var flipHorizontal: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                resetRequested = true
            }
        }

    @Volatile private var resetRequested = false
    private val latestRef = AtomicReference<PoseSnapshot?>(null)
    private var filter = OrientationFilter()
    private var epoch = nextEpoch.incrementAndGet()
    private var prevTs = -1L

    // Bias calibration window (device time).
    private var bias = Vec3.ZERO
    private var calibrated = false
    private var winStartTs = -1L
    private var n = 0
    private var sum = Vec3.ZERO
    private var sumSq = Vec3.ZERO

    fun latest(): PoseSnapshot? = latestRef.get()

    /** Gyro in deg/s and accel in g, both in raw IMU axes. */
    fun onSample(
        gx: Double, gy: Double, gz: Double,
        ax: Double, ay: Double, az: Double,
        timestampNs: Long, arrivalNanos: Long,
    ) {
        if (resetRequested) {
            resetRequested = false
            filter = OrientationFilter()
            epoch = nextEpoch.incrementAndGet()
            prevTs = -1L
        }
        if (!calibrated) accumulateBias(gx, gy, gz, timestampNs)

        val flip = flipHorizontal
        val gyroDeg = GlassesFrame.toGl(gx - bias.x, gy - bias.y, gz - bias.z, flip)
        val accel = GlassesFrame.toGl(ax, ay, az, flip)

        val dt = if (prevTs < 0) 0.0 else (timestampNs - prevTs) / 1e9
        prevTs = timestampNs
        if (dt <= 0.0 || dt > 0.1) {
            // First sample, a timestamp regression, or a long gap: re-seed tilt from gravity
            // only if we have nothing yet; otherwise skip integration for this sample.
            if (!filter.initialized) filter.initFromAccel(accel)
        } else {
            filter.update(gyroDeg * (Math.PI / 180.0), accel, dt)
        }
        latestRef.set(PoseSnapshot(filter.q, arrivalNanos, calibrated, epoch, bias))
    }

    private fun accumulateBias(gx: Double, gy: Double, gz: Double, ts: Long) {
        if (winStartTs < 0) winStartTs = ts
        val g = Vec3(gx, gy, gz)
        sum += g
        sumSq += Vec3(gx * gx, gy * gy, gz * gz)
        n++
        if (ts - winStartTs < CALIBRATION_WINDOW_NS) return

        val mean = sum * (1.0 / n)
        val variance = sumSq * (1.0 / n) - Vec3(mean.x * mean.x, mean.y * mean.y, mean.z * mean.z)
        val maxStd = sqrt(maxOf(variance.x, variance.y, variance.z, 0.0))
        if (maxStd < STILL_STD_DEG && mean.norm() < STILL_MEAN_DEG) {
            bias = mean
            calibrated = true
        }
        // Not still: start a new window.
        winStartTs = -1L; n = 0; sum = Vec3.ZERO; sumSq = Vec3.ZERO
    }

    companion object {
        private val nextEpoch = AtomicInteger(0)
        const val CALIBRATION_WINDOW_NS = 1_000_000_000L
        /** On device, at-rest per-axis noise was ≈0.05 deg/s; head micro-motion is far larger. */
        const val STILL_STD_DEG = 0.5
        const val STILL_MEAN_DEG = 3.0
    }
}
