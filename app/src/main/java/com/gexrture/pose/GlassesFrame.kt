package com.gexrture.pose

/**
 * Converts XBX IMU axes to the GL frame (x right, y up, z back).
 *
 * MEASURED ON DEVICE (2026-10-25, see project.md): the IMU frame is right-handed with
 * X = left, Y = back, Z = up. Evidence: gyro-integrated rotations matched the accelerometer's
 * gravity change for both a pitch move (ΔX −26.0° vs −25.3°) and a roll move (ΔY +53.3° vs +55°),
 * and turning right gave negative Z.
 * X = left (rather than right) rests on the owner having nodded UP when X went negative. If
 * nod/tilt move the panel the wrong way, [flipHorizontal] = 180° about up (yaw unaffected).
 */
object GlassesFrame {
    fun toGl(x: Double, y: Double, z: Double, flipHorizontal: Boolean): Vec3 =
        if (!flipHorizontal) Vec3(-x, z, y) else Vec3(x, z, -y)
}
