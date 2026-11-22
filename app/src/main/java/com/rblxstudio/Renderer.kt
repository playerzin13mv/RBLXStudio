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
    private var uFace = 0
    private var aT = 0
    private var uEye = 0
    private var uFog = 0
    private var uSpec = 0
    private var uStud = 0
    // céu
    private var skyProg = 0
    private var skyA = 0
    private var skyUF = 0
    private var skyUR = 0
    private var skyUU = 0
    private var skyUTh = 0
    private var skyUAsp = 0
    private var skyUTime = 0
    private var skyUQ = 0
    private var skyUOct = 0
    private lateinit var skyBuf: FloatBuffer
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
        val vs = "uniform mat4 uVP; uniform mat4 uModel; attribute vec3 aP; attribute vec3 aN; attribute vec2 aT;\n" +
            "varying vec3 vL; varying vec3 vW; varying vec3 vO; varying vec3 vN; varying vec2 vT; varying vec3 vWN;\n" +
            "void main(){ vec4 w = uModel * vec4(aP, 1.0); vW = w.xyz; vO = aP; vN = aN; vT = aT;\n" +
            "vec3 n = normalize((uModel * vec4(aN, 0.0)).xyz); vWN = n;\n" +
            "float dif = max(dot(n, normalize(vec3(0.45, 0.85, 0.35))), 0.0);\n" +
            "vec3 amb = mix(vec3(0.30, 0.31, 0.34), vec3(0.46, 0.52, 0.64), n.y * 0.5 + 0.5);\n" +
            "vL = (amb + vec3(1.0, 0.93, 0.80) * dif * 0.66) * 0.95;\n" +
            "gl_Position = uVP * w; }"
        val fs = "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform vec3 uColor; uniform float uLight; uniform float uGrid; uniform vec3 uEye; uniform float uFog; uniform float uSpec; uniform float uStud;\n" +
            "uniform sampler2D uTex; uniform float uUseTex; uniform vec3 uHalf; uniform float uTile; uniform vec4 uFace[6];\n" +
            "varying vec3 vL; varying vec3 vW; varying vec3 vO; varying vec3 vN; varying vec2 vT; varying vec3 vWN;\n" +
            "void main(){ vec3 c = uColor;\n" +
            "if (uUseTex > 3.5) { float dd = length(vO.xz); float al = (1.0 - smoothstep(0.25, 1.0, dd)) * uColor.r; gl_FragColor = vec4(0.0, 0.0, 0.0, al); return; }\n" +
            "if (uUseTex > 2.5) { vec4 mt = texture2D(uTex, vT); gl_FragColor = vec4(mt.rgb * mix(vec3(1.0), vL, uLight), 1.0); return; }\n" +
            "if (uUseTex > 1.5) {\n" +
            "  vec3 an2 = abs(vN); vec2 uv2; vec4 r;\n" +
            "  if (an2.x > 0.5) { uv2 = vec2(-vO.z * sign(vN.x), vO.y); if (vN.x > 0.0) r = uFace[0]; else r = uFace[1]; }\n" +
            "  else if (an2.y > 0.5) { uv2 = vec2(vO.x, -vO.z * sign(vN.y)); if (vN.y > 0.0) r = uFace[2]; else r = uFace[3]; }\n" +
            "  else { uv2 = vec2(vO.x * sign(vN.z), vO.y); if (vN.z > 0.0) r = uFace[4]; else r = uFace[5]; }\n" +
            "  if (r.z <= 0.0) discard;\n" +
            "  vec2 t2 = uv2 * 0.5 + 0.5; t2.y = 1.0 - t2.y;\n" +
            "  vec4 tc2 = texture2D(uTex, r.xy + t2 * r.zw);\n" +
            "  if (tc2.a < 0.05) discard;\n" +
            "  gl_FragColor = vec4(tc2.rgb * mix(vec3(1.0), vL, uLight), tc2.a); return;\n" +
            "}\n" +
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
            "float fdist = distance(vW, uEye);\n" +
            "if (uStud > 0.5 && vWN.y > 0.9) {\n" +
            "  vec2 sf = fract(vW.xz) - 0.5; float sr = length(sf);\n" +
            "  float sk = 1.0 - smoothstep(25.0, 80.0, fdist);\n" +
            "  float top = 1.0 - smoothstep(0.30, 0.36, sr);\n" +
            "  float rim = smoothstep(0.30, 0.36, sr) * (1.0 - smoothstep(0.36, 0.44, sr));\n" +
            "  c = c * (1.0 + sk * (0.10 * top - 0.14 * rim));\n" +
            "}\n" +
            "vec3 outc = c * mix(vec3(1.0), vL, uLight);\n" +
            "if (uSpec > 0.5 && uLight > 0.5) {\n" +
            "  vec3 V = normalize(uEye - vW); vec3 H = normalize(normalize(vec3(0.45, 0.85, 0.35)) + V);\n" +
            "  outc += vec3(1.0, 0.97, 0.9) * pow(max(dot(normalize(vWN), H), 0.0), 48.0) * 0.10;\n" +
            "}\n" +
            "float ff = smoothstep(260.0, 1100.0, fdist) * uFog;\n" +
            "outc = mix(outc, vec3(0.74, 0.86, 0.97), ff);\n" +
            "gl_FragColor = vec4(outc, 1.0); }"
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        aP = GLES20.glGetAttribLocation(prog, "aP")
        aN = GLES20.glGetAttribLocation(prog, "aN")
        aT = GLES20.glGetAttribLocation(prog, "aT")
        uFace = GLES20.glGetUniformLocation(prog, "uFace[0]")
        uVP = GLES20.glGetUniformLocation(prog, "uVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uLight = GLES20.glGetUniformLocation(prog, "uLight")
        uGrid = GLES20.glGetUniformLocation(prog, "uGrid")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
        uUseTex = GLES20.glGetUniformLocation(prog, "uUseTex")
        uHalf = GLES20.glGetUniformLocation(prog, "uHalf")
        uTile = GLES20.glGetUniformLocation(prog, "uTile")
        uEye = GLES20.glGetUniformLocation(prog, "uEye")
        uFog = GLES20.glGetUniformLocation(prog, "uFog")
        uSpec = GLES20.glGetUniformLocation(prog, "uSpec")
        uStud = GLES20.glGetUniformLocation(prog, "uStud")
        GLES20.glUseProgram(prog)
        GLES20.glUniform1i(uTex, 0)
        buildSky()
        texIds.clear()
        texAsked.clear()
        avVer = -1
        shirtTex = 0; pantsTex = 0; tshirtTex = 0; faceTex = 0
        accGl.clear()
    }

    // ---------- céu: gradiente, sol e nuvens (calculado por pixel, sem imagens) ----------
    private fun buildSky() {
        val vs = "attribute vec2 aPos; varying vec2 vP;\n" +
            "void main(){ vP = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }"
        val fs = "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "uniform vec3 uF; uniform vec3 uR; uniform vec3 uU; uniform float uTh; uniform float uAsp;\n" +
            "uniform float uTime; uniform float uQ; uniform float uOct;\n" +
            "varying vec2 vP;\n" +
            "float hsh(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n" +
            "float nz(vec2 p){ vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);\n" +
            "  return mix(mix(hsh(i), hsh(i + vec2(1.0, 0.0)), f.x), mix(hsh(i + vec2(0.0, 1.0)), hsh(i + vec2(1.0, 1.0)), f.x), f.y); }\n" +
            "void main(){\n" +
            "  vec3 d = normalize(uF + uR * (vP.x * uTh * uAsp) + uU * (vP.y * uTh));\n" +
            "  vec3 hor = vec3(0.74, 0.86, 0.97); vec3 zen = vec3(0.14, 0.40, 0.84);\n" +
            "  vec3 col = mix(hor, zen, pow(clamp(d.y, 0.0, 1.0), 0.5));\n" +
            "  if (d.y < 0.0) col = mix(hor, vec3(0.60, 0.70, 0.84), clamp(-d.y * 3.0, 0.0, 1.0));\n" +
            "  vec3 sd = normalize(vec3(0.45, 0.85, 0.35));\n" +
            "  float s = max(dot(d, sd), 0.0);\n" +
            "  if (uQ > 1.5) {\n" +
            "    col += vec3(1.0, 0.82, 0.55) * pow(s, 6.0) * 0.22;\n" +
            "    col += vec3(1.0, 0.92, 0.75) * pow(s, 64.0) * 0.35;\n" +
            "    col = mix(col, vec3(1.0, 0.98, 0.90), smoothstep(0.9992, 0.9997, s));\n" +
            "  }\n" +
            "  if (uQ > 2.5 && d.y > 0.01) {\n" +
            "    vec2 p = d.xz / (d.y + 0.18) * 1.5 + vec2(uTime * 0.02, uTime * 0.008);\n" +
            "    float c = 0.0; float a = 0.55; vec2 q = p;\n" +
            "    for (int i = 0; i < 4; i++) { if (float(i) >= uOct) break; c += nz(q) * a; q = q * 2.03 + vec2(1.7, 9.2); a *= 0.5; }\n" +
            "    c = smoothstep(0.45, 0.85, c);\n" +
            "    float fade = smoothstep(0.01, 0.22, d.y);\n" +
            "    vec3 cc = mix(vec3(0.80, 0.84, 0.92), vec3(1.0), c);\n" +
            "    col = mix(col, cc, c * fade * 0.92);\n" +
            "  }\n" +
            "  gl_FragColor = vec4(col, 1.0);\n" +
            "}"
        skyProg = GLES20.glCreateProgram()
        GLES20.glAttachShader(skyProg, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(skyProg, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(skyProg)
        skyA = GLES20.glGetAttribLocation(skyProg, "aPos")
        skyUF = GLES20.glGetUniformLocation(skyProg, "uF")
        skyUR = GLES20.glGetUniformLocation(skyProg, "uR")
        skyUU = GLES20.glGetUniformLocation(skyProg, "uU")
        skyUTh = GLES20.glGetUniformLocation(skyProg, "uTh")
        skyUAsp = GLES20.glGetUniformLocation(skyProg, "uAsp")
        skyUTime = GLES20.glGetUniformLocation(skyProg, "uTime")
        skyUQ = GLES20.glGetUniformLocation(skyProg, "uQ")
        skyUOct = GLES20.glGetUniformLocation(skyProg, "uOct")
        val q = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
        val b = ByteBuffer.allocateDirect(q.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(q)
        b.position(0)
        skyBuf = b
    }

    private fun drawSky() {
        if (skyProg == 0) return
        val b = Cam.basis()
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(skyProg)
        GLES20.glUniform3f(skyUF, b[0][0], b[0][1], b[0][2])
        GLES20.glUniform3f(skyUR, b[1][0], b[1][1], b[1][2])
        GLES20.glUniform3f(skyUU, b[2][0], b[2][1], b[2][2])
        GLES20.glUniform1f(skyUTh, Math.tan(Math.toRadians((Cam.FOV / 2f).toDouble())).toFloat())
        GLES20.glUniform1f(skyUAsp, Cam.w.toFloat() / Cam.h.toFloat())
        GLES20.glUniform1f(skyUTime, ((System.nanoTime() / 1000000L) % 600000L).toFloat() / 1000f)
        val ql = Settings.level()
        GLES20.glUniform1f(skyUQ, ql.toFloat())
        GLES20.glUniform1f(skyUOct, if (ql >= 9) 4f else if (ql >= 6) 3f else 2f)
        skyBuf.position(0)
        GLES20.glVertexAttribPointer(skyA, 2, GLES20.GL_FLOAT, false, 8, skyBuf)
        GLES20.glEnableVertexAttribArray(skyA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(skyA)
        GLES20.glUseProgram(prog)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    // sombra redonda embaixo do personagem
    private fun drawBlob() {
        val feet = Play.y - Play.HY
        var top = -1e9f
        for (p in Scene.parts) {
            if (!p.canCollide) continue
            if (abs(Play.x - p.x) > p.halfExtent(0) || abs(Play.z - p.z) > p.halfExtent(2)) continue
            val t = p.y + p.halfExtent(1)
            if (t <= feet + 0.6f && t > top) top = t
        }
        if (top < -1e8f) return
        val a = (0.55f * (1f - (feet - top) / 40f)).coerceIn(0f, 0.55f)
        if (a <= 0.01f) return
        use(cube)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, Play.x, top + 0.04f, Play.z)
        Matrix.scaleM(model, 0, 2.3f, 0.02f, 2.3f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3f(uColor, a, 0f, 0f)
        GLES20.glUniform1f(uLight, 0f)
        GLES20.glUniform1f(uGrid, 0f)
        GLES20.glUniform1f(uUseTex, 4f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUniform1f(uUseTex, 0f)
    }

    // Tool na mão direita (o Handle vira uma caixa presa ao braço)
    private fun drawTool(tool: Inst) {
        var handle: PartData? = null
        for (c in tool.children) {
            if (c is PartData && c.name == "Handle") {
                handle = c
                break
            }
        }
        val h = handle ?: return
        val rig = Rig.current()
        if (!partMatrix(rig.handIdx, pm)) return
        Matrix.translateM(pm, 0, 0f, rig.handDy, 0f)
        Matrix.rotateM(pm, 0, 90f, 1f, 0f, 0f)
        Matrix.scaleM(pm, 0, h.sx / 2f, h.sy / 2f, h.sz / 2f)
        use(cube)
        curTex = 0
        draw(pm, h.r, h.g, h.b, 1f, 0f, GLES20.GL_TRIANGLES, 36)
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

    // ---------- avatar: roupas 2D e acessórios 3D ----------
    private class AccGl(val acc: Acc, val buf: FloatBuffer, val count: Int, val tex: Int)

    private var avVer = -1
    private var shirtTex = 0
    private var pantsTex = 0
    private var tshirtTex = 0
    private var faceTex = 0
    private val accGl = ArrayList<AccGl>()
    private val faceArr = FloatArray(24)
    private val pm = FloatArray(16)
    private val pm2 = FloatArray(16)

    private fun delTex(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    // reenvia as texturas do avatar para a GPU quando o Avatar mudou
    private fun syncAvatar() {
        val v = Avatar.version
        if (v == avVer) return
        avVer = v
        delTex(shirtTex); delTex(pantsTex); delTex(tshirtTex); delTex(faceTex)
        for (g in accGl) delTex(g.tex)
        accGl.clear()
        val sh = Avatar.shirt
        val pa = Avatar.pants
        val ts = Avatar.tshirt
        val fc = Avatar.face
        faceTex = if (fc != null) upload(fc) else 0
        shirtTex = if (sh != null) upload(sh) else 0
        pantsTex = if (pa != null) upload(pa) else 0
        tshirtTex = if (ts != null) upload(ts) else 0
        for (a in Avatar.accs) {
            val fb = ByteBuffer.allocateDirect(a.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(a.data)
            fb.position(0)
            val t = a.tex
            accGl.add(AccGl(a, fb, a.data.size / 8, if (t != null) upload(t) else 0))
        }
    }

    // ---------- corpo (rig R6 ou R15, animado por Anim) ----------
    private val rigW = Array(Rig.MAX) { FloatArray(16) }
    private val rigDone = BooleanArray(Rig.MAX)
    private val rigRoot = FloatArray(16)

    // matriz da peça i no espaço do personagem (cadeia de juntas, sem a posição do personagem)
    private fun rigWorld(rig: Rig, i: Int): FloatArray {
        val w = rigW[i]
        if (rigDone[i]) return w
        val p = rig.parts[i]
        if (p.parent >= 0) System.arraycopy(rigWorld(rig, p.parent), 0, w, 0, 16) else Matrix.setIdentityM(w, 0)
        val ax = Anim.ang[i * 3]
        val ay = Anim.ang[i * 3 + 1]
        val az = Anim.ang[i * 3 + 2]
        if (ax != 0f || ay != 0f || az != 0f) {
            Matrix.translateM(w, 0, p.jx, p.jy, p.jz)
            if (ay != 0f) Matrix.rotateM(w, 0, ay, 0f, 1f, 0f)
            if (az != 0f) Matrix.rotateM(w, 0, az, 0f, 0f, 1f)
            if (ax != 0f) Matrix.rotateM(w, 0, ax, 1f, 0f, 0f)
            Matrix.translateM(w, 0, -p.jx, -p.jy, -p.jz)
        }
        rigDone[i] = true
        return w
    }

    // centro da peça i no mundo (sem escala); na morte usa os pedaços soltos
    private fun partMatrix(i: Int, out: FloatArray): Boolean {
        val rig = Rig.current()
        if (i < 0 || i >= rig.parts.size) return false
        Matrix.setIdentityM(out, 0)
        if (Play.dead) {
            val fr = Play.frags.firstOrNull { it.part == i } ?: return false
            Matrix.translateM(out, 0, fr.px, fr.py, fr.pz)
            Matrix.rotateM(out, 0, fr.ry, 0f, 1f, 0f)
            Matrix.rotateM(out, 0, fr.rx, 1f, 0f, 0f)
            Matrix.rotateM(out, 0, fr.rz, 0f, 0f, 1f)
            return true
        }
        val p = rig.parts[i]
        val w = rigWorld(rig, i)
        Matrix.setIdentityM(rigRoot, 0)
        Matrix.translateM(rigRoot, 0, Play.x, Play.y, Play.z)
        Matrix.rotateM(rigRoot, 0, Play.facing, 0f, 1f, 0f)
        Matrix.multiplyMM(out, 0, rigRoot, 0, w, 0)
        Matrix.translateM(out, 0, p.cx, p.cy, p.cz)
        return true
    }

    private fun colOf(i: Int): Int {
        val rig = Rig.current()
        val role = if (i >= 0 && i < rig.parts.size) rig.parts[i].role else 0
        val c = Avatar.cols
        return when (role) {
            0 -> c[1]
            1 -> c[0]
            2 -> c[3]
            3 -> c[2]
            4 -> c[5]
            else -> c[4]
        }
    }

    private fun drawBox(base: FloatArray, ox: Float, oy: Float, oz: Float, hx: Float, hy: Float, hz: Float, col: Int) {
        System.arraycopy(base, 0, pm2, 0, 16)
        Matrix.translateM(pm2, 0, ox, oy, oz)
        Matrix.scaleM(pm2, 0, hx, hy, hz)
        draw(pm2, ((col shr 16) and 255) / 255f, ((col shr 8) and 255) / 255f, (col and 255) / 255f,
            1f, 0f, GLES20.GL_TRIANGLES, 36)
    }

    // rect do template (585x559) -> índice de face
    private fun face(i: Int, x: Int, y: Int, w: Int, h: Int) {
        val inset = 0.6f
        faceArr[i * 4] = (x + inset) / 585f
        faceArr[i * 4 + 1] = (y + inset) / 559f
        faceArr[i * 4 + 2] = (w - 2 * inset) / 585f
        faceArr[i * 4 + 3] = (h - 2 * inset) / 559f
    }

    // faces: 0 +x (esquerda do avatar), 1 -x (direita), 2 +y, 3 -y, 4 +z (frente), 5 -z (costas)
    private fun layout(part: Int): Boolean {
        java.util.Arrays.fill(faceArr, 0f)
        when (part) {
            0 -> {
                face(0, 361, 74, 64, 128); face(1, 165, 74, 64, 128)
                face(2, 231, 8, 128, 64); face(3, 231, 204, 128, 64)
                face(4, 231, 74, 128, 128); face(5, 427, 74, 128, 128)
            }
            2, 4 -> {   // lado direito do avatar (-x)
                face(1, 19, 355, 64, 128); face(0, 154, 355, 64, 128)
                face(4, 86, 355, 64, 128); face(5, 221, 355, 64, 128)
                face(2, 217, 289, 64, 64); face(3, 217, 483, 64, 64)
            }
            3, 5 -> {   // lado esquerdo do avatar (+x)
                face(1, 308, 355, 64, 128); face(0, 442, 355, 64, 128)
                face(4, 375, 355, 64, 128); face(5, 509, 355, 64, 128)
                face(2, 308, 289, 64, 64); face(3, 308, 483, 64, 64)
            }
            else -> return false
        }
        return true
    }

    // peças do R15 usam só uma fatia (de cima para baixo) do desenho da roupa do R6
    private fun sliceFaces(rp: RigPart) {
        if (rp.t0 <= 0f && rp.t1 >= 1f) return
        for (k in 0..5) {
            if (faceArr[k * 4 + 2] <= 0f) continue
            if (k == 2) {
                if (rp.t0 > 0f) faceArr[k * 4 + 2] = 0f
                continue
            }
            if (k == 3) {
                if (rp.t1 < 1f) faceArr[k * 4 + 2] = 0f
                continue
            }
            val y = faceArr[k * 4 + 1]
            val hh = faceArr[k * 4 + 3]
            faceArr[k * 4 + 1] = y + hh * rp.t0
            faceArr[k * 4 + 3] = hh * (rp.t1 - rp.t0)
        }
    }

    private fun overlay(base: FloatArray, rp: RigPart, tex: Int, grow: Float, whole: Boolean) {
        if (tex == 0) return
        if (whole) {
            java.util.Arrays.fill(faceArr, 0f)
            faceArr[16] = 0f; faceArr[17] = 0f; faceArr[18] = 1f; faceArr[19] = 1f   // só a frente
        } else if (!layout(rp.role)) return
        sliceFaces(rp)
        val h = floatArrayOf(rp.hx, rp.hy, rp.hz)
        System.arraycopy(base, 0, pm2, 0, 16)
        Matrix.translateM(pm2, 0, 0f, 0f, 0f)
        Matrix.scaleM(pm2, 0, h[0] * grow, h[1] * grow, h[2] * grow)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform4fv(uFace, 6, faceArr, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, pm2, 0)
        GLES20.glUniform3f(uColor, 1f, 1f, 1f)
        GLES20.glUniform1f(uLight, 1f)
        GLES20.glUniform1f(uGrid, 0f)
        GLES20.glUniform1f(uUseTex, 2f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    private fun useMesh(buf: FloatBuffer) {
        buf.position(0)
        GLES20.glVertexAttribPointer(aP, 3, GLES20.GL_FLOAT, false, 32, buf)
        GLES20.glEnableVertexAttribArray(aP)
        buf.position(3)
        GLES20.glVertexAttribPointer(aN, 3, GLES20.GL_FLOAT, false, 32, buf)
        GLES20.glEnableVertexAttribArray(aN)
        buf.position(6)
        GLES20.glVertexAttribPointer(aT, 2, GLES20.GL_FLOAT, false, 32, buf)
        GLES20.glEnableVertexAttribArray(aT)
    }

    private val pm3 = FloatArray(16)

    private fun drawAccessory(g: AccGl) {
        val a = g.acc
        val inv = a.attInv
        val pl = a.place
        if (inv != null && pl != null) {
            // encaixe pelo Attachment do próprio item: corpo -> ponto do Roblox -> inverso do Attachment do Handle
            if (!partMatrix(pl[0].toInt(), pm)) return
            Matrix.rotateM(pm, 0, 180f, 0f, 1f, 0f)   // o Roblox olha para -Z; aqui o avatar olha para +Z
            val dy = if (pl[0].toInt() == 0) Rig.current().torsoDy else 0f
            Matrix.translateM(pm, 0, pl[1], pl[2] - dy, pl[3])
            Matrix.multiplyMM(pm3, 0, pm, 0, inv, 0)
            useMesh(g.buf)
            GLES20.glUniformMatrix4fv(uModel, 1, false, pm3, 0)
            drawAccBody(g)
            return
        }
        val head = a.type == "Hat" || a.type == "Hair" || a.type == "Face"
        val anchor = if (head) 1 else 0
        if (!partMatrix(anchor, pm)) return
        val w = a.max[0] - a.min[0]
        val h = a.max[1] - a.min[1]
        val d = a.max[2] - a.min[2]
        var px = 0f
        var py = 0f
        var pz = 0f
        when (a.type) {
            "Head" -> py = 0f
            "Hat" -> py = 0.4f + h / 2f
            "Hair" -> py = 0.8f - h / 2f
            "Face" -> { py = 0.05f; pz = 0.62f - d / 2f }
            "Neck" -> py = 1.1f
            "Shoulder" -> py = 1.0f
            "Front" -> { py = 0.1f; pz = 0.55f - d / 2f }
            "Back" -> { py = 0.2f; pz = -0.55f + d / 2f }
            "Waist" -> py = -0.9f
        }
        if (anchor == 0) py -= Rig.current().torsoDy
        Matrix.translateM(pm, 0, px, py, pz)
        Matrix.rotateM(pm, 0, 180f, 0f, 1f, 0f)   // o Roblox olha para -Z; aqui o avatar olha para +Z
        if (a.type == "Head") { val sc = 1.2f / maxOf(h, 0.01f); Matrix.scaleM(pm, 0, sc, sc, sc) }
        Matrix.translateM(pm, 0,
            -(a.min[0] + a.max[0]) / 2f, -(a.min[1] + a.max[1]) / 2f, -(a.min[2] + a.max[2]) / 2f)
        useMesh(g.buf)
        GLES20.glUniformMatrix4fv(uModel, 1, false, pm, 0)
        drawAccBody(g)
    }

    private fun drawAccBody(g: AccGl) {
        if (g.tex != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, g.tex)
            GLES20.glUniform3f(uColor, 1f, 1f, 1f)
            GLES20.glUniform1f(uUseTex, 3f)
        } else {
            GLES20.glUniform3f(uColor, 0.62f, 0.62f, 0.65f)
            GLES20.glUniform1f(uUseTex, 0f)
        }
        GLES20.glUniform1f(uLight, 1f)
        GLES20.glUniform1f(uGrid, 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, g.count)
        GLES20.glDisableVertexAttribArray(aT)
    }

    // corpo em blocos + roupas + acessórios; na morte desenha os pedaços soltos (Lego)
    private fun drawAvatar() {
        syncAvatar()
        use(cube)
        curTex = 0
        val hasHead = accGl.any { it.acc.type == "Head" }
        val rig = Rig.current()
        java.util.Arrays.fill(rigDone, false)
        for (i in rig.parts.indices) {
            if (!partMatrix(i, pm)) continue
            val o = rig.parts[i]
            if (i == 1 && hasHead) continue   // cabeça de malha substitui o cubo
            drawBox(pm, 0f, 0f, 0f, o.hx, o.hy, o.hz, colOf(i))
            if (i == 1 && faceTex == 0) {   // olhos padrão (sem rosto do avatar)
                drawBox(pm, -0.22f, 0.1f, 0.61f, 0.07f, 0.1f, 0.02f, 0x111111)
                drawBox(pm, 0.22f, 0.1f, 0.61f, 0.07f, 0.1f, 0.02f, 0x111111)
            }
        }
        // camadas de roupa (alpha), na ordem: calça, camisa, camiseta
        if (pantsTex != 0 || shirtTex != 0 || tshirtTex != 0 || (faceTex != 0 && !hasHead)) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glDepthMask(false)
            use(cube)
            for (i in rig.parts.indices) {
                val rp = rig.parts[i]
                if ((rp.role == 0 || rp.role == 4 || rp.role == 5) && partMatrix(i, pm)) overlay(pm, rp, pantsTex, 1.012f, false)
            }
            for (i in rig.parts.indices) {
                val rp = rig.parts[i]
                if ((rp.role == 0 || rp.role == 2 || rp.role == 3) && partMatrix(i, pm)) overlay(pm, rp, shirtTex, 1.02f, false)
            }
            for (i in rig.parts.indices) {
                val rp = rig.parts[i]
                if (rp.role == 0 && partMatrix(i, pm)) overlay(pm, rp, tshirtTex, 1.03f, true)
            }
            if (!hasHead && partMatrix(1, pm)) overlay(pm, rig.parts[1], faceTex, 1.02f, true)
            GLES20.glDepthMask(true)
            GLES20.glDepthFunc(GLES20.GL_LESS)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        for (g in accGl) drawAccessory(g)
        GLES20.glUniform1f(uUseTex, 0f)
        use(cube)
        val eq = Play.equipped
        if (eq != null && !Play.dead) drawTool(eq)
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
        drawSky()
        val e = Cam.eye()
        var tx = Cam.target[0]
        var ty = Cam.target[1]
        var tz = Cam.target[2]
        if (Cam.shoulder != 0f && !(Play.active && Play.firstPerson())) {
            val rt = Cam.basis()[1]
            val sh = Cam.shoulder
            e[0] += rt[0] * sh; e[1] += rt[1] * sh; e[2] += rt[2] * sh
            tx += rt[0] * sh; ty += rt[1] * sh; tz += rt[2] * sh
        }
        Matrix.setLookAtM(view, 0, e[0], e[1], e[2], tx, ty, tz, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        synchronized(snap) { System.arraycopy(vp, 0, snap, 0, 16) }
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)
        val ql = Settings.level()
        GLES20.glUniform3f(uEye, e[0], e[1], e[2])
        GLES20.glUniform1f(uFog, if (ql >= 5) 1f else 0f)
        GLES20.glUniform1f(uSpec, if (ql >= 7) 1f else 0f)

        use(cube)
        for (p in Scene.parts) {
            GLES20.glUniform1f(uStud, if (ql >= 5 && p.props["Studs"] == "true") 1f else 0f)
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
        GLES20.glUniform1f(uStud, 0f)

        if (Play.active) {
            if (!Play.firstPerson()) {
                if (!Play.dead && ql >= 3) drawBlob()
                drawAvatar()
            }
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