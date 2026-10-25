package com.gexrture.panel

import com.gexrture.pose.Quat
import com.gexrture.pose.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelControllerTest {
    private val aspect = 16.0 / 9.0
    private fun center(c: PanelController, head: Quat) = Mat4.projectPoint(c.mvp(head, aspect), 0f, 0f, 0f)!!
    private fun corner(c: PanelController, head: Quat) = Mat4.projectPoint(c.mvp(head, aspect), 1f, 1f, 0f)!!

    private val someHead = (Quat.axisAngle(Vec3.UP, 0.4) * Quat.axisAngle(Vec3.RIGHT, -0.2) *
        Quat.axisAngle(Vec3(0.0, 0.0, 1.0), 0.1)).normalized()

    @Test
    fun follow_mode_ignores_head_rotation() {
        val c = PanelController()
        val a = center(c, Quat.IDENTITY)
        val b = center(c, someHead)
        assertEquals(a[0], b[0], 1e-5f); assertEquals(a[1], b[1], 1e-5f)
        assertEquals(0f, a[0], 1e-5f); assertEquals(0f, a[1], 1e-5f)
    }

    @Test
    fun pin_and_follow_do_not_jump() {
        val c = PanelController()
        c.moveBy(8.0, 4.0)
        val before = center(c, someHead)
        c.pin(someHead, 1)
        val pinned = center(c, someHead)
        // Pinned panel is upright in the world, so its centre stays put (rotation about the
        // view axis by head roll only rotates it in place).
        assertEquals(before[0], pinned[0], 2e-3f); assertEquals(before[1], pinned[1], 2e-3f)
        c.follow(someHead)
        val after = center(c, someHead)
        assertEquals(before[0], after[0], 2e-3f); assertEquals(before[1], after[1], 2e-3f)
    }

    @Test
    fun recenter_puts_pinned_panel_straight_ahead() {
        val c = PanelController()
        c.pin(Quat.IDENTITY, 1)
        val head = Quat.axisAngle(Vec3.UP, Math.toRadians(35.0))
        c.recenter(head, 1)
        val p = center(c, head)
        assertEquals(0f, p[0], 1e-4f); assertEquals(0f, p[1], 1e-4f)
    }

    @Test
    fun new_tracking_epoch_recenters_pinned_panel_once() {
        val c = PanelController()
        c.pin(Quat.IDENTITY, 1)
        c.moveBy(30.0, 0.0)
        val head = Quat.axisAngle(Vec3.UP, Math.toRadians(-20.0))
        assertTrue(c.onPose(head, 2))
        val p = center(c, head)
        assertEquals(0f, p[0], 1e-4f)
        assertTrue(!c.onPose(head, 2))
    }

    @Test
    fun aspect_ratio_is_16_9_and_scales_uniformly() {
        val c = PanelController()
        val ar1 = corner(c, Quat.IDENTITY).let { it[0] * aspect / it[1] }
        c.scaleBy(1.7)
        val ar2 = corner(c, Quat.IDENTITY).let { it[0] * aspect / it[1] }
        // In follow mode at the view centre the corner's NDC ratio reflects the panel's 16:9.
        assertEquals(16.0 / 9.0, ar1.toDouble(), 1e-3)
        assertEquals(16.0 / 9.0, ar2.toDouble(), 1e-3)
    }

    @Test
    fun size_is_clamped() {
        val c = PanelController()
        repeat(50) { c.scaleBy(2.0) }
        assertEquals(PanelController.MAX_WIDTH, c.state.widthDeg, 1e-9)
        repeat(50) { c.scaleBy(0.5) }
        assertEquals(PanelController.MIN_WIDTH, c.state.widthDeg, 1e-9)
    }
}
