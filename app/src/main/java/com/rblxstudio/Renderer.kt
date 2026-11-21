package com.rblxstudio

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class Renderer : GLSurfaceView.Renderer {
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private var prog = 0
    private var aP = 0
    private var aN = 0
    private var uVP = 0
    private var uModel = 0
    private var uColor = 0
    private var uLight = 0
    private var uGrid = 0
    private var uTex = 0
    private var uUseTex = 0
    private var uHalf = 0
    private var uTile = 0
    private var curTex = 0
    private val texIds = HashMap<String, Int>()
    private val texAsked = HashSet<String>()
    private val texPending = ConcurrentHashMap<String, Bitmap>()
    private lateinit var cube: FloatBuffer
    private lateinit var lines: FloatBuffer
    private lateinit var ring: FloatBuffer
    private var ringCount = 0
    private val yellow = floatArrayOf(1f, 0.9f, 0.2f)

    companion object {
        private val snap = FloatArray(16)

        // posição na tela (px do viewport) de um ponto do mundo; null se estiver atrás da câmera
        fun project(x: Float, y: Float, z: Float, w: Int, h: Int): FloatArray? {
            val m = FloatArray(16)
            synchronized(snap) { System.arraycopy(snap, 0, m, 0, 16) }
            val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
            val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
            val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
            if (cw <= 0.01f) return null
            return floatArrayOf((cx / cw * 0.5f + 0.5f) * w, (1f - (cy / cw * 0.5f + 0.5f)) * h)
        }
    }
    private var lastNs = 0L

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun makeBuf(d: List<Float>): FloatBuffer {
        val b = ByteBuffer.allocateDirect(d.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(d.toFloatArray())
        b.position(0)
        return b
    }

    private fun buildMeshes() {
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
        cube = makeBuf(d)

        val ld = ArrayList<Float>()
        for (i in 0..7) {
            for (b in 0..2) {
                if ((i and (1 shl b)) == 0) {
                    val j = i or (1 shl b)
                    for (cc in intArrayOf(i, j)) {
                        ld.add(if ((cc and 1) != 0) 1f else -1f)
                        ld.add(if ((cc and 2) != 0) 1f else -1f)
                        ld.add(if ((cc and 4) != 0) 1f else -1f)
                        ld.add(0f); ld.add(1f); ld.add(0f)
                    }
                }
            }
        }
        lines = makeBuf(ld)

        val n = 48
        val rd = ArrayList<Float>()
        fun vtx(a: Float, rad: Float) {
            rd.add(cos(a) * rad); rd.add(sin(a) * rad); rd.add(0f)
            rd.add(0f); rd.add(0f); rd.add(1f)
        }
        for (i in 0 until n) {
            val a0 = (2 * PI * i / n).toFloat()
            val a1 = (2 * PI * (i + 1) / n).toFloat()
            vtx(a0, 0.96f); vtx(a0, 1.04f); vtx(a1, 1.04f)
            vtx(a0, 0.96f); vtx(a1, 1.04f); vtx(a1, 0.96f)
        }
        ringCount = n * 6
        ring = makeBuf(rd)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.62f, 0.69f, 0.80f, 1f)
        buildMeshes()
        val vs = "uniform mat4 uVP; uniform mat4 uModel; attribute vec3 aP; attribute vec3 aN;\n" +
            "varying float vL; varying vec3 vW; varying vec3 vO; varying vec3 vN;\n" +
            "void main(){ vec4 w = uModel * vec4(aP, 1.0); vW = w.xyz; vO = aP; vN = aN;\n" +
            "vec3 n = normalize((uModel * vec4(aN, 0.0)).xyz);\n" +
            "vL = 0.45 + 0.55 * max(dot(n, normalize(vec3(0.4, 1.0, 0.6))), 0.0);\n" +
            "gl_Position = uVP * w; }"
        val fs = "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform vec3 uColor; uniform float uLight; uniform float uGrid;\n" +
            "uniform sampler2D uTex; uniform float uUseTex; uniform vec3 uHalf; uniform float uTile;\n" +
            "varying float vL; varying vec3 vW; varying vec3 vO; varying vec3 vN;\n" +
            "void main(){ vec3 c = uColor;\n" +
            "if (uGrid > 0.5) { float k = mod(floor(vW.x / 16.0) + floor(vW.z / 16.0), 2.0); c = c * (0.85 + 0.15 * k); }\n" +
            "if (uUseTex > 0.5) {\n" +
            "  vec3 an = abs(vN); vec2 uv; vec2 ext;\n" +
            "  if (an.x > 0.5) { uv = vec2(-vO.z * sign(vN.x), vO.y); ext = vec2(uHalf.z, uHalf.y); }\n" +
            "  else if (an.y > 0.5) { uv = vec2(vO.x, -vO.z * sign(vN.y)); ext = vec2(uHalf.x, uHalf.z); }\n" +
            "  else { uv = vec2(vO.x * sign(vN.z), vO.y); ext = vec2(uHalf.x, uHalf.y); }\n" +
            "  vec2 t = uv * 0.5 + 0.5;\n" +
            "  if (uTile > 0.01) { t = uv * ext / uTile + 0.5; }\n" +
            "  t.y = 1.0 - t.y;\n" +
            "  vec4 tc = texture2D(uTex, t);\n" +
            "  c = mix(c, tc.rgb, tc.a);\n" +
            "}\n" +
            "gl_FragColor = vec4(c * mix(1.0, vL, uLight), 1.0); }"
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        aP = GLES20.glGetAttribLocation(prog, "aP")
        aN = GLES20.glGetAttribLocation(prog, "aN")
        uVP = GLES20.glGetUniformLocation(prog, "uVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uLight = GLES20.glGetUniformLocation(prog, "uLight")
        uGrid = GLES20.glGetUniformLocation(prog, "uGrid")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
        uUseTex = GLES20.glGetUniformLocation(prog, "uUseTex")
        uHalf = GLES20.glGetUniformLocation(prog, "uHalf")
        uTile = GLES20.glGetUniformLocation(prog, "uTile")
        GLES20.glUseProgram(prog)
        GLES20.glUniform1i(uTex, 0)
        texIds.clear()
        texAsked.clear()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        Cam.w = w
        Cam.h = h
        Matrix.perspectiveM(proj, 0, Cam.FOV, w.toFloat() / h, 1f, 2500f)
    }

    private fun use(buf: FloatBuffer) {
        buf.position(0)
        GLES20.glVertexAttribPointer(aP, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glEnableVertexAttribArray(aP)
        buf.position(3)
        GLES20.glVertexAttribPointer(aN, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glEnableVertexAttribArray(aN)
    }

    private fun draw(m: FloatArray, r: Float, g: Float, b: Float, light: Float, grid: Float, mode: Int, count: Int) {
        GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0)
        GLES20.glUniform3f(uColor, r, g, b)
        GLES20.glUniform1f(uLight, light)
        GLES20.glUniform1f(uGrid, grid)
        GLES20.glUniform1f(uUseTex, if (curTex != 0) 1f else 0f)
        GLES20.glDrawArrays(mode, 0, count)
    }

    private fun pot(v: Int): Int {
        var n = 1
        while (n < v && n < 1024) n *= 2
        return n
    }

    private fun upload(src: Bitmap): Int {
        val w = pot(src.width)
        val h = pot(src.height)
        val bmp = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        return ids[0]
    }

    // textura de uma Part: 0 enquanto ainda está baixando
    private fun texFor(src: String): Int {
        val id = texIds[src]
        if (id != null) return id
        if (texAsked.add(src)) {
            Assets.image(src) { bmp -> if (bmp != null) texPending[src] = bmp }
        }
        return 0
    }

    private fun handleModel(h: Editor.Handle) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, h.center[0], h.center[1], h.center[2])
        if (h.ori != null) {
            Matrix.multiplyMM(tmp, 0, model, 0, h.ori, 0)
            System.arraycopy(tmp, 0, model, 0, 16)
        }
        Matrix.scaleM(model, 0, h.half[0], h.half[1], h.half[2])
    }

    private fun ringModel(sel: PartData, i: Int) {
        val rad = Editor.radius(sel) + 1.5f * Editor.k()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, sel.x, sel.y, sel.z)
        if (i == 0) Matrix.rotateM(model, 0, 90f, 0f, 1f, 0f)
        else if (i == 1) Matrix.rotateM(model, 0, -90f, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, rad, rad, rad)
    }

    // desenha uma caixa do personagem, relativa ao centro dele e girada para onde ele olha
    private fun body(ox: Float, oy: Float, oz: Float, hx: Float, hy: Float, hz: Float, col: Int,
                     ang: Float = 0f, pivotY: Float = oy) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, Play.x, Play.y, Play.z)
        Matrix.rotateM(model, 0, Play.facing, 0f, 1f, 0f)
        Matrix.translateM(model, 0, ox, pivotY, oz)
        if (ang != 0f) Matrix.rotateM(model, 0, ang, 1f, 0f, 0f)
        Matrix.translateM(model, 0, 0f, oy - pivotY, 0f)
        Matrix.scaleM(model, 0, hx, hy, hz)
        draw(model, ((col shr 16) and 255) / 255f, ((col shr 8) and 255) / 255f, (col and 255) / 255f,
            1f, 0f, GLES20.GL_TRIANGLES, 36)
    }

    // cores do corpo vêm do avatar do Roblox (Avatar.cols)
    private fun drawAvatar() {
        use(cube)
        val c = Avatar.cols
        val swing = if (Play.moving) sin(Play.walkT * 11f) * 45f else 0f
        body(0f, 0.5f, 0f, 1f, 1f, 0.5f, c[1])                              // torso
        body(0f, 1.9f, 0f, 0.6f, 0.6f, 0.6f, c[0])                         // cabeça
        body(-0.22f, 2.0f, 0.61f, 0.07f, 0.1f, 0.02f, 0x111111)            // olhos
        body(0.22f, 2.0f, 0.61f, 0.07f, 0.1f, 0.02f, 0x111111)
        body(-1.5f, 0.5f, 0f, 0.5f, 1f, 0.5f, c[2], swing, 1.5f)           // braços
        body(1.5f, 0.5f, 0f, 0.5f, 1f, 0.5f, c[3], -swing, 1.5f)
        body(-0.5f, -1.5f, 0f, 0.5f, 1f, 0.5f, c[4], -swing, -0.5f)        // pernas
        body(0.5f, -1.5f, 0f, 0.5f, 1f, 0.5f, c[5], swing, -0.5f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (Play.active) {
            val now = System.nanoTime()
            var dt = (now - lastNs) / 1.0e9f
            if (dt > 0.05f) dt = 0.05f
            if (dt < 0f) dt = 0f
            lastNs = now
            Play.update(dt)
        } else {
            lastNs = System.nanoTime()
        }
        if (texPending.isNotEmpty()) {
            for (k in texPending.keys.toList()) {
                val b = texPending.remove(k) ?: continue
                texIds[k] = upload(b)
            }
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val e = Cam.eye()
        Matrix.setLookAtM(view, 0, e[0], e[1], e[2], Cam.target[0], Cam.target[1], Cam.target[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        synchronized(snap) { System.arraycopy(vp, 0, snap, 0, 16) }
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)

        use(cube)
        for (p in Scene.parts) {
            val ts = p.props["Texture"] ?: p.props["TextureID"]
            var tid = 0
            if (!ts.isNullOrEmpty()) tid = texFor(ts)
            if (tid != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tid)
                GLES20.glUniform3f(uHalf, p.sx * 0.5f, p.sy * 0.5f, p.sz * 0.5f)
                GLES20.glUniform1f(uTile, p.props["StudsPerTile"]?.toFloatOrNull() ?: 0f)
            }
            curTex = tid
            p.model(model)
            draw(model, p.r, p.g, p.b, 1f, if (p.grid) 1f else 0f, GLES20.GL_TRIANGLES, 36)
        }
        curTex = 0

        if (Play.active) {
            drawAvatar()
            return
        }

        val sel = Scene.selected ?: return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        use(lines)
        sel.model(model, 1.01f)
        GLES20.glLineWidth(4f)
        draw(model, 0.1f, 0.65f, 1f, 0f, 0f, GLES20.GL_LINES, 24)

        when (Editor.tool) {
            Tool.MOVE, Tool.SCALE -> {
                use(cube)
                for (h in Editor.handles(sel)) {
                    handleModel(h)
                    val on = Editor.activeHandle == h.axis * 2 + (if (h.sign > 0f) 0 else 1)
                    val c = if (on) yellow else Editor.axisColors[h.axis]
                    draw(model, c[0], c[1], c[2], 0f, 0f, GLES20.GL_TRIANGLES, 36)
                }
            }
            Tool.ROTATE -> {
                use(ring)
                for (i in 0..2) {
                    ringModel(sel, i)
                    val c = if (Editor.activeHandle == i * 2) yellow else Editor.axisColors[i]
                    draw(model, c[0], c[1], c[2], 0f, 0f, GLES20.GL_TRIANGLES, ringCount)
                }
            }
            else -> {}
        }
    }
}