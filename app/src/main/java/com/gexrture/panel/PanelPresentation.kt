package com.gexrture.panel

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Display

/** Full-screen GL surface on the glasses display. Android dismisses it if the display goes away. */
class PanelPresentation(
    outerContext: Context,
    display: Display,
    private val renderer: PanelRenderer,
) : Presentation(outerContext, display) {

    private var glView: GLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        glView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView!!)
    }

    override fun onStop() {
        glView?.onPause()
        super.onStop()
    }
}
