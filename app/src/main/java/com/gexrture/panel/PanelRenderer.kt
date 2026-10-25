package com.gexrture.panel

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.gexrture.pose.PoseTracker
import com.gexrture.pose.Quat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the panel on the glasses display every vsync. Reads the newest pose on each frame, so
 * IMU sampling (≈1 kHz) and rendering stay independent.
 *
 * Tracking loss (no new pose for [STALE_POSE_NS]) freezes the last pose and tints the panel red;
 * before gyro calibration finishes the panel is tinted yellow.
 */
class PanelRenderer(
    private val controller: PanelController,
    private val poseSource: () -> PoseTracker.PoseSnapshot?,
    /** Called on the GL thread when a new tracking epoch made a pinned panel recenter. */
    private val onAutoRecenter: () -> Unit = {},
) : GLSurfaceView.Renderer {

    data class Stats(
        val fps: Double = 0.0,
        val maxFrameIntervalMs: Double = 0.0,
        /** Time from IMU sample arrival to the frame that used it (excludes display scan-out). */
        val poseAgeAvgMs: Double = 0.0,
        val poseAgeMaxMs: Double = 0.0,
        val surfaceWidth: Int = 0,
        val surfaceHeight: Int = 0,
        val trackingState: String = "none",
        val head: Quat? = null,
    )

    @Volatile var stats = Stats()
        private set

    private var program = 0
    private var texture = 0
    private var aPos = 0
    private var aUv = 0
    private var uMvp = 0
    private var uTint = 0
    private var width = 1
    private var height = 1
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v as a triangle strip; bitmap rows go top → bottom, so v is flipped.
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        position(0)
    }

    private var lastPose: PoseTracker.PoseSnapshot? = null
    private var windowStartNs = 0L
    private var frames = 0
    private var lastFrameNs = 0L
    private var maxIntervalNs = 0L
    private var ageSumNs = 0L
    private var ageMaxNs = 0L
    private var ageCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTint = GLES20.glGetUniformLocation(program, "uTint")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val bmp = TestPattern.create()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val pose = poseSource()
        val fresh = pose != null && now - pose.arrivalNanos < STALE_POSE_NS
        if (fresh) {
            lastPose = pose
            if (controller.onPose(pose!!.q, pose.epoch)) onAutoRecenter()
            val age = now - pose.arrivalNanos
            ageSumNs += age; ageCount++; if (age > ageMaxNs) ageMaxNs = age
        }
        val head = lastPose?.q
        val tracking = when {
            pose == null && lastPose == null -> "none"
            !fresh -> "LOST (frozen)"
            !pose!!.calibrated -> "calibrating"
            else -> "ok"
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, controller.mvp(head, width.toDouble() / height), 0)
        when (tracking) {
            "LOST (frozen)" -> GLES20.glUniform3f(uTint, 1f, 0.45f, 0.45f)
            "calibrating" -> GLES20.glUniform3f(uTint, 1f, 1f, 0.55f)
            else -> GLES20.glUniform3f(uTint, 1f, 1f, 1f)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Frame statistics over ~1 s windows.
        if (lastFrameNs != 0L) maxIntervalNs = maxOf(maxIntervalNs, now - lastFrameNs)
        lastFrameNs = now
        frames++
        if (windowStartNs == 0L) windowStartNs = now
        val span = now - windowStartNs
        if (span >= 1_000_000_000L) {
            stats = Stats(
                fps = frames * 1e9 / span,
                maxFrameIntervalMs = maxIntervalNs / 1e6,
                poseAgeAvgMs = if (ageCount > 0) ageSumNs / ageCount / 1e6 else 0.0,
                poseAgeMaxMs = ageMaxNs / 1e6,
                surfaceWidth = width, surfaceHeight = height,
                trackingState = tracking,
                head = head,
            )
            windowStartNs = now; frames = 0; maxIntervalNs = 0; ageSumNs = 0; ageMaxNs = 0; ageCount = 0
        } else if (stats.trackingState != tracking || stats.head == null) {
            stats = stats.copy(trackingState = tracking, head = head)
        }
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 aPos;
            attribute vec2 aUv;
            uniform mat4 uMvp;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * vec4(aPos, 0.0, 1.0); }
        """.trimIndent())
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec3 uTint;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTex, vUv) * vec4(uTint, 1.0); }
        """.trimIndent())
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Link failed: ${GLES20.glGetProgramInfoLog(p)}")
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Compile failed: ${GLES20.glGetShaderInfoLog(s)}")
        return s
    }

    companion object {
        private const val TAG = "PanelRenderer"
        const val STALE_POSE_NS = 300_000_000L
    }
}
