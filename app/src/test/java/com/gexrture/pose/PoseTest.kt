package com.gexrture.pose

import com.gexrture.panel.Mat4
import com.gexrture.panel.PanelController
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseTest {

    private fun assertVec(expected: Vec3, actual: Vec3, tol: Double = 1e-6) {
        assertEquals(expected.x, actual.x, tol); assertEquals(expected.y, actual.y, tol); assertEquals(expected.z, actual.z, tol)
    }

    @Test
    fun positive_yaw_turns_forward_to_the_left() {
        val q = Quat.axisAngle(Vec3.UP, Math.toRadians(90.0))
        assertVec(Vec3(-1.0, 0.0, 0.0), q.rotate(Vec3.FORWARD))
    }

    @Test
    fun filter_integrates_yaw_rate() {
        val f = OrientationFilter(kp = 1.0, ki = 0.0)
        val up = Vec3(0.0, 1.0, 0.0)
        f.initFromAccel(up)
        repeat(1000) { f.update(Vec3(0.0, Math.toRadians(90.0), 0.0), up, 0.001) }
        assertEquals(90.0, PanelController.headYpr(f.q).first, 0.5)
    }

    @Test
    fun filter_converges_tilt_to_gravity() {
        val f = OrientationFilter(kp = 1.0, ki = 0.0)
        f.initFromAccel(Vec3.UP) // wrong: the real head is pitched down 30°
        val trueHead = Quat.axisAngle(Vec3.RIGHT, Math.toRadians(-30.0))
        val measured = trueHead.conj().rotate(Vec3.UP)
        repeat(10_000) { f.update(Vec3.ZERO, measured, 0.001) } // 10 s
        assertEquals(-30.0, PanelController.headYpr(f.q).second, 1.0)
    }

    // ── Raw IMU axes, using conventions measured on the owner's a01+ ──

    private fun feedStill(t: PoseTracker, startTs: Long, seconds: Double, accelRaw: Vec3 = Vec3(0.0, 0.0, 1.0)): Long {
        var ts = startTs
        repeat((seconds * 1000).toInt()) {
            t.onSample(0.0, 0.0, 0.0, accelRaw.x, accelRaw.y, accelRaw.z, ts, 0)
            ts += 1_000_000
        }
        return ts
    }

    @Test
    fun tracker_calibrates_only_when_still() {
        val t = PoseTracker()
        var ts = 0L
        repeat(1200) { i ->
            val wobble = if (i % 2 == 0) 5.0 else -5.0 // head micro-motion
            t.onSample(wobble, 0.0, 0.0, 0.0, 0.0, 1.0, ts, 0); ts += 1_000_000
        }
        assertFalse(t.latest()!!.calibrated)
        feedStill(t, ts, 2.1)
        assertTrue(t.latest()!!.calibrated)
    }

    @Test
    fun worn_gravity_reading_means_head_slightly_down() {
        // Typical worn reading from owner logs: accel ≈ (0, 0.16, 0.99) g.
        val t = PoseTracker()
        feedStill(t, 0, 0.01, Vec3(0.0, 0.16, 0.99))
        val pitch = PanelController.headYpr(t.latest()!!.q).second
        assertEquals(-9.2, pitch, 0.5)
    }

    @Test
    fun turning_right_moves_pinned_panel_left_in_view() {
        val t = PoseTracker()
        var ts = feedStill(t, 0, 1.2)
        val c = PanelController()
        val start = t.latest()!!
        c.pin(start.q, start.epoch)
        // Owner's turn right produced negative raw Z (deg/s).
        repeat(500) { t.onSample(0.0, 0.0, -90.0, 0.0, 0.0, 1.0, ts, 0); ts += 1_000_000 }
        val head = t.latest()!!.q
        assertEquals(-45.0, PanelController.headYpr(head).first, 1.0)

        val ndc = Mat4.projectPoint(c.mvp(head, 16.0 / 9.0), 0f, 0f, 0f)
        assertNotNull(ndc) // 45° is outside the ~22° half-FOV but still in front of the viewer
        assertTrue("panel should appear to the left", ndc!![0] < -1f)
    }

    @Test
    fun nodding_up_moves_pinned_panel_down_in_view() {
        // ASSUMPTION (see GlassesFrame): the owner nodded UP when raw X went negative.
        val t = PoseTracker()
        var ts = feedStill(t, 0, 1.2)
        val c = PanelController()
        val start = t.latest()!!
        c.pin(start.q, start.epoch)
        repeat(100) { t.onSample(-100.0, 0.0, 0.0, 0.0, 0.0, 1.0, ts, 0); ts += 1_000_000 } // −10°
        val head = t.latest()!!.q
        assertEquals(10.0, PanelController.headYpr(head).second, 1.0)
        val ndc = Mat4.projectPoint(c.mvp(head, 16.0 / 9.0), 0f, 0f, 0f)!!
        assertTrue("panel should appear lower", ndc[1] < 0f)
    }

    @Test
    fun flip_reverses_pitch_but_not_yaw_and_changes_epoch() {
        val t = PoseTracker()
        var ts = feedStill(t, 0, 1.2)
        val e0 = t.latest()!!.epoch
        t.flipHorizontal = true
        ts = feedStill(t, ts, 0.01)
        assertTrue(t.latest()!!.epoch != e0)
        repeat(100) { t.onSample(-100.0, 0.0, -100.0, 0.0, 0.0, 1.0, ts, 0); ts += 1_000_000 }
        val (yaw, pitch, _) = PanelController.headYpr(t.latest()!!.q)
        assertTrue(yaw < -5)   // yaw sign unchanged by the flip
        assertTrue(pitch < -5) // pitch sign reversed by the flip
    }

    @Test
    fun pinned_offset_matches_projection_geometry() {
        val c = PanelController()
        c.pin(Quat.IDENTITY, 1)
        val head = Quat.axisAngle(Vec3.UP, Math.toRadians(10.0)) // look 10° left
        val ndc = Mat4.projectPoint(c.mvp(head, 16.0 / 9.0), 0f, 0f, 0f)!!
        val vHalf = Math.toRadians(c.state.vFovDeg / 2)
        val expectedX = tan(Math.toRadians(10.0)) / (tan(vHalf) * 16.0 / 9.0)
        assertEquals(expectedX, ndc[0].toDouble(), 1e-4)
    }
}
