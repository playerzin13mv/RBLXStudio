package com.rblxstudio

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PartData(
    val x: Float, val y: Float, val z: Float,
    val sx: Float, val sy: Float, val sz: Float,
    val r: Float, val g: Float, val b: Float
)

class Renderer : GLSurfaceView.Renderer {
    val parts = CopyOnWriteArrayList<PartData>()
    private val ground = PartData(0f, -0.5f, 0f, 60f, 0.5f, 60f, 0.35f, 0.55f, 0.3f)
    @Volatile var yaw = 0.6f
    @Volatile var pitch = 0.5f
    @Volatile var dist = 30f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private var prog = 0
    private var aP = 0
    private var aN = 0
    private var uMVP = 0
    private var uColor = 0
    private lateinit var buf: FloatBuffer

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun buildCube() {
        val d = ArrayList<Float>()
        val ns = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f)
        )
        for (n in ns) {
            val a = if (abs(n[1]) > 0.5f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            val u = cross(n, a)
            val v = cross(n, u)
            fun c(su: Float, sv: Float) {
                for (i in 0..2) d.add(n[i] + su * u[i] + sv * v[i])
                for (i in 0..2) d.add(n[i])
            }
            c(-1f, -1f); c(1f, -1f); c(1f, 1f)
            c(-1f, -1f); c(1f, 1f); c(-1f, 1f)
        }
        buf = ByteBuffer.allocateDirect(d.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(d.toFloatArray())
        buf.position(0)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.53f, 0.75f, 0.92f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        buildCube()
        val vs = "uniform mat4 uMVP; attribute vec3 aP; attribute vec3 aN; varying float vL;\n" +
            "void main(){ vL = 0.4 + 0.6 * max(dot(aN, normalize(vec3(0.4,1.0,0.6))), 0.0);\n" +
            "gl_Position = uMVP * vec4(aP, 1.0); }"
        val fs = "precision mediump float; uniform vec3 uColor; varying float vL;\n" +
            "void main(){ gl_FragColor = vec4(uColor * vL, 1.0); }"
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        aP = GLES20.glGetAttribLocation(prog, "aP")
        aN = GLES20.glGetAttribLocation(prog, "aN")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        Matrix.perspectiveM(proj, 0, 60f, w.toFloat() / h, 0.5f, 500f)
    }

    private fun drawPart(p: PartData) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, p.x, p.y, p.z)
        Matrix.scaleM(model, 0, p.sx, p.sy, p.sz)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniform3f(uColor, p.r, p.g, p.b)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val ex = dist * cos(pitch) * sin(yaw)
        val ey = 2f + dist * sin(pitch)
        val ez = dist * cos(pitch) * cos(yaw)
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, 2f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        GLES20.glUseProgram(prog)
        buf.position(0)
        GLES20.glVertexAttribPointer(aP, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glEnableVertexAttribArray(aP)
        buf.position(3)
        GLES20.glVertexAttribPointer(aN, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glEnableVertexAttribArray(aN)
        drawPart(ground)
        for (p in parts) drawPart(p)
    }
}
