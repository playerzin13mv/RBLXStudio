package com.rblxstudio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

// Animações do personagem (R6 e R15): parado, andar, pular, cair e voar.
// Cada peça gira em volta da sua junta; ang[i*3] = X, [i*3+1] = Y, [i*3+2] = Z (graus).
// X positivo: a ponta do membro vai para trás (o personagem olha para +Z). Z positivo: a ponta vai para +X.
object Anim {
    val ang = FloatArray(Rig.MAX * 3)
    private val goal = FloatArray(Rig.MAX * 3)
    private var idleT = 0f
    private var lastRig: Rig? = null

    // estado atual (para scripts e para depuração)
    @Volatile var state = "Idle"

    private fun g(i: Int, x: Float, y: Float = 0f, z: Float = 0f) {
        goal[i * 3] = x
        goal[i * 3 + 1] = y
        goal[i * 3 + 2] = z
    }

    fun reset() {
        java.util.Arrays.fill(ang, 0f)
        java.util.Arrays.fill(goal, 0f)
        idleT = 0f
        state = "Idle"
    }

    fun update(dt: Float) {
        val rig = Rig.current()
        if (rig !== lastRig) {
            lastRig = rig
            java.util.Arrays.fill(ang, 0f)
        }
        java.util.Arrays.fill(goal, 0f)
        idleT += dt
        val r15 = rig.parts.size > 6
        val phase = Play.walkT * 11f
        val s = sin(phase)
        val c = cos(phase)
        val air = Play.airTime > 0.1f && !Play.flying

        if (Play.flying) {
            state = "Fly"
            val lean = if (Play.moving) 55f else 15f
            g(rig.leanIdx, lean)
            g(Rig.H, -lean * 0.8f)
            g(Rig.RA, -50f, 0f, -10f)
            g(Rig.LA, -50f, 0f, 10f)
            val legs = if (r15) 5f else lean * 0.9f
            g(Rig.RL, legs)
            g(Rig.LL, legs * 0.6f)
            if (r15) {
                g(Rig.RLA, -15f)
                g(Rig.LLA, -15f)
                g(Rig.RLL, 12f)
                g(Rig.LLL, 8f)
            }
        } else if (air) {
            if (Play.vy > 1f) {
                state = "Jump"
                g(Rig.RA, -150f, 0f, -15f)
                g(Rig.LA, -150f, 0f, 15f)
                g(Rig.RL, -8f)
                g(Rig.LL, 8f)
                if (r15) {
                    g(Rig.RLA, -10f)
                    g(Rig.LLA, -10f)
                    g(Rig.RLL, 14f)
                    g(Rig.LLL, 14f)
                }
            } else {
                state = "Fall"
                g(Rig.RA, -115f, 0f, -35f)
                g(Rig.LA, -115f, 0f, 35f)
                g(Rig.RL, -14f)
                g(Rig.LL, 14f)
                if (r15) {
                    g(Rig.RLA, -8f)
                    g(Rig.LLA, -8f)
                    g(Rig.RLL, 8f)
                    g(Rig.LLL, 8f)
                }
            }
        } else if (Play.moving) {
            state = "Walk"
            val a = 45f
            g(Rig.RA, s * a)
            g(Rig.LA, -s * a)
            g(Rig.RL, -s * a)
            g(Rig.LL, s * a)
            g(Rig.H, 0f, -s * 4f)
            if (r15) {
                g(Rig.RLA, -(10f + 30f * max(0f, -s)))
                g(Rig.LLA, -(10f + 30f * max(0f, s)))
                val kr = 50f * max(0f, c)
                val kl = 50f * max(0f, -c)
                g(Rig.RLL, kr)
                g(Rig.LLL, kl)
                g(Rig.RF, -(-s * a + kr) * 0.5f)
                g(Rig.LF, -(s * a + kl) * 0.5f)
                g(Rig.T, 0f, s * 5f)
            }
        } else {
            state = "Idle"
            val sway = 3f + 1.5f * sin(idleT * 1.6f)
            g(Rig.RA, 0f, 0f, -sway)
            g(Rig.LA, 0f, 0f, sway)
            g(Rig.H, 1.5f * sin(idleT * 1.1f))
            if (r15) {
                g(Rig.RLA, -4f)
                g(Rig.LLA, -4f)
            }
        }

        // Tool na mão: braço direito levantado, golpe ao ativar
        if (Play.equipped != null && !Play.flying) {
            g(Rig.RA, -90f + 70f * sin(PI.toFloat() * (1f - Play.toolSwing)))
            if (r15) g(Rig.RLA, -12f)
        }

        val k = 1f - exp(-20f * dt)
        for (i in ang.indices) ang[i] += (goal[i] - ang[i]) * k
    }
}
