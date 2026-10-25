package com.gexrture.panel

import com.gexrture.pose.Quat
import com.gexrture.pose.Vec3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.tan

enum class PanelMode { FOLLOW, PINNED }

/**
 * Panel placement. Angles in degrees; yaw positive = left, pitch positive = up.
 * FOLLOW: yaw/pitch relative to the head. PINNED: yaw/pitch in the world (panel kept upright).
 */
data class PanelState(
    val mode: PanelMode = PanelMode.FOLLOW,
    val yawDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    /** Angular width of the panel; height follows 16:9. */
    val widthDeg: Double = 30.0,
    /**
     * Vertical field of view of the glasses. Default from the upstream 50° diagonal claim
     * (XRLinuxDriver) at 16:9 → 25.8° vertical. If a pinned panel slides faster or slower than
     * the head turns, this value is wrong; it is adjustable in the UI.
     */
    val vFovDeg: Double = 25.8,
)

/** Pure placement logic and MVP math; no Android dependencies so it is unit-testable. */
class PanelController {
    @Volatile var state = PanelState()
        private set

    /** World-frame epoch the PINNED placement belongs to; -1 = none yet. */
    @Volatile var trackingEpoch = -1
        private set

    @Synchronized fun moveBy(dYawDeg: Double, dPitchDeg: Double) {
        state = state.copy(
            yawDeg = wrap180(state.yawDeg + dYawDeg),
            pitchDeg = (state.pitchDeg + dPitchDeg).coerceIn(-80.0, 80.0),
        )
    }

    @Synchronized fun scaleBy(factor: Double) {
        state = state.copy(widthDeg = (state.widthDeg * factor).coerceIn(MIN_WIDTH, MAX_WIDTH))
    }

    @Synchronized fun resetSize() { state = state.copy(widthDeg = PanelState().widthDeg) }

    @Synchronized fun adjustFov(deltaDeg: Double) {
        state = state.copy(vFovDeg = (state.vFovDeg + deltaDeg).coerceIn(10.0, 60.0))
    }

    /** Keeps the panel where it currently appears, now fixed in the world. */
    @Synchronized fun pin(head: Quat, epoch: Int) {
        if (state.mode == PanelMode.PINNED) return
        val world = head.rotate(direction(state.yawDeg, state.pitchDeg))
        state = state.copy(mode = PanelMode.PINNED, yawDeg = yawOf(world), pitchDeg = pitchOf(world))
        trackingEpoch = epoch
    }

    /** Keeps the panel where it currently appears, now following the head. */
    @Synchronized fun follow(head: Quat?) {
        if (state.mode == PanelMode.FOLLOW) return
        val rel = (head ?: Quat.IDENTITY).conj().rotate(direction(state.yawDeg, state.pitchDeg))
        state = state.copy(mode = PanelMode.FOLLOW, yawDeg = yawOf(rel), pitchDeg = pitchOf(rel))
    }

    /** Puts the panel straight ahead of the current gaze. */
    @Synchronized fun recenter(head: Quat?, epoch: Int) {
        state = if (state.mode == PanelMode.PINNED && head != null) {
            val fwd = head.rotate(Vec3.FORWARD)
            trackingEpoch = epoch
            state.copy(yawDeg = yawOf(fwd), pitchDeg = pitchOf(fwd))
        } else {
            state.copy(yawDeg = 0.0, pitchDeg = 0.0)
        }
    }

    /**
     * Called per frame. A new world frame (new session after a replug, or an axis flip) makes the
     * old pinned yaw meaningless, so the panel is recentered once in the new frame.
     * @return true if it recentered
     */
    @Synchronized fun onPose(head: Quat, epoch: Int): Boolean {
        if (state.mode != PanelMode.PINNED || epoch == trackingEpoch) return false
        recenter(head, epoch)
        return true
    }

    /** Column-major MVP for a unit quad (x, y in −1..1). */
    fun mvp(head: Quat?, aspect: Double): FloatArray {
        val s = state
        val placement = yawPitchQuat(s.yawDeg, s.pitchDeg)
        val rotation = if (s.mode == PanelMode.PINNED) (head ?: Quat.IDENTITY).conj() * placement else placement
        val halfW = tan(Math.toRadians(s.widthDeg / 2))
        val halfH = halfW * 9.0 / 16.0
        val model = Mat4.multiply(
            rotation.toMat4(),
            Mat4.multiply(Mat4.translation(0f, 0f, -1f), Mat4.scale(halfW.toFloat(), halfH.toFloat(), 1f)),
        )
        val proj = Mat4.perspective(s.vFovDeg, aspect, 0.05, 100.0)
        return Mat4.multiply(proj, model)
    }

    companion object {
        const val MIN_WIDTH = 5.0
        const val MAX_WIDTH = 120.0

        /** Rotation that takes FORWARD to the given yaw (about up, + = left) then pitch (+ = up). */
        fun yawPitchQuat(yawDeg: Double, pitchDeg: Double): Quat =
            Quat.axisAngle(Vec3.UP, Math.toRadians(yawDeg)) *
                Quat.axisAngle(Vec3.RIGHT, Math.toRadians(pitchDeg))

        fun direction(yawDeg: Double, pitchDeg: Double): Vec3 = yawPitchQuat(yawDeg, pitchDeg).rotate(Vec3.FORWARD)
        fun yawOf(dir: Vec3) = Math.toDegrees(atan2(-dir.x, -dir.z))
        fun pitchOf(dir: Vec3) = Math.toDegrees(asin(dir.normalized().y.coerceIn(-1.0, 1.0)))

        /** Head yaw/pitch/roll in degrees for display and logs. */
        fun headYpr(head: Quat): Triple<Double, Double, Double> {
            val fwd = head.rotate(Vec3.FORWARD)
            val right = head.rotate(Vec3.RIGHT)
            val up = head.rotate(Vec3.UP)
            return Triple(yawOf(fwd), pitchOf(fwd), Math.toDegrees(atan2(-right.y, up.y)))
        }

        private fun wrap180(d: Double): Double {
            var a = d
            while (a > 180) a -= 360
            while (a < -180) a += 360
            return a
        }
    }
}

/** Minimal column-major 4×4 helpers (android.opengl.Matrix is unavailable in JVM unit tests). */
object Mat4 {
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    fun translation(x: Float, y: Float, z: Float) = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f,
    )

    fun scale(x: Float, y: Float, z: Float) = floatArrayOf(
        x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f, 0f, 0f, 0f, 1f,
    )

    fun perspective(vFovDeg: Double, aspect: Double, near: Double, far: Double): FloatArray {
        val f = 1.0 / tan(Math.toRadians(vFovDeg) / 2)
        return floatArrayOf(
            (f / aspect).toFloat(), 0f, 0f, 0f,
            0f, f.toFloat(), 0f, 0f,
            0f, 0f, ((far + near) / (near - far)).toFloat(), -1f,
            0f, 0f, (2 * far * near / (near - far)).toFloat(), 0f,
        )
    }

    /** Transforms (x, y, z, 1) and returns NDC (x/w, y/w, z/w), or null if behind the viewer. */
    fun projectPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
        val px = m[0] * x + m[4] * y + m[8] * z + m[12]
        val py = m[1] * x + m[5] * y + m[9] * z + m[13]
        val pz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val pw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (pw <= 0f) return null
        return floatArrayOf(px / pw, py / pw, pz / pw)
    }
}
