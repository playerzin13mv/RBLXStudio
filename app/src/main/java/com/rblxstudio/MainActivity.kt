package com.rblxstudio

import android.app.Activity
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var gl: GLSurfaceView
    private val renderer = Renderer()
    private var lx = 0f
    private var ly = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(2)
        gl.setRenderer(renderer)

        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                renderer.dist = (renderer.dist / d.scaleFactor).coerceIn(5f, 150f)
                return true
            }
        })
        gl.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = if (e.actionIndex == 0) 1 else 0
                    lx = e.getX(i); ly = e.getY(i)
                }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scale.isInProgress) {
                    renderer.yaw -= (e.x - lx) * 0.01f
                    renderer.pitch = (renderer.pitch + (e.y - ly) * 0.01f).coerceIn(0.05f, 1.5f)
                    lx = e.x; ly = e.y
                }
            }
            true
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xDD1E1E1E.toInt())
            setPadding(24, 8, 24, 8)
        }
        bar.addView(TextView(this).apply {
            text = "RBLXStudio"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 32, 0)
        })
        bar.addView(Button(this).apply {
            text = "+ Part"
            setOnClickListener {
                renderer.parts.add(
                    PartData(
                        Random.nextFloat() * 16f - 8f, 1f, Random.nextFloat() * 16f - 8f,
                        2f, 0.5f, 1f,
                        Random.nextFloat(), Random.nextFloat(), Random.nextFloat()
                    )
                )
            }
        })
        bar.addView(Button(this).apply {
            text = "Limpar"
            setOnClickListener { renderer.parts.clear() }
        })

        val root = FrameLayout(this)
        root.addView(gl)
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        setContentView(root)
    }

    override fun onResume() { super.onResume(); gl.onResume() }
    override fun onPause() { super.onPause(); gl.onPause() }
}
