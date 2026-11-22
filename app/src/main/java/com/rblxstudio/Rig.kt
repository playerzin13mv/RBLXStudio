package com.rblxstudio

// Uma peça do corpo do personagem (rig R6 ou R15).
// role: quem dá a cor e a roupa: 0 torso, 1 cabeça, 2 braço dir, 3 braço esq, 4 perna dir, 5 perna esq
// c* = centro em repouso, h* = meio-tamanho, j* = junta (pivô) em repouso, t0..t1 = fatia da roupa (fração a partir do topo)
class RigPart(
    val name: String,
    val parent: Int,
    val role: Int,
    val cx: Float, val cy: Float, val cz: Float,
    val hx: Float, val hy: Float, val hz: Float,
    val jx: Float, val jy: Float, val jz: Float,
    val t0: Float, val t1: Float
)

class Rig(
    val name: String,
    val parts: Array<RigPart>,
    val torsoDy: Float,     // quanto o centro do torso fica acima do centro do torso R6 (para encaixar acessórios)
    val leanIdx: Int,       // peça que inclina o corpo (voar)
    val handIdx: Int,       // peça que segura a Tool
    val handDy: Float       // da peça até a palma da mão
) {
    companion object {
        const val MAX = 15

        // índices fixos (os 6 primeiros valem para os dois rigs)
        const val T = 0; const val H = 1; const val RA = 2; const val LA = 3; const val RL = 4; const val LL = 5
        // só no R15
        const val LT = 6; const val RLA = 7; const val RH = 8; const val LLA = 9; const val LH = 10
        const val RLL = 11; const val RF = 12; const val LLL = 13; const val LF = 14

        private fun p(n: String, par: Int, role: Int, cx: Float, cy: Float, cz: Float,
                      hx: Float, hy: Float, hz: Float, jx: Float, jy: Float, jz: Float,
                      t0: Float = 0f, t1: Float = 1f) =
            RigPart(n, par, role, cx, cy, cz, hx, hy, hz, jx, jy, jz, t0, t1)

        // R6: 6 blocos (torso 2x2x1, cabeça, 2 braços e 2 pernas 1x2x1)
        val R6 = Rig("R6", arrayOf(
            p("Torso", -1, 0, 0f, 0.5f, 0f, 1f, 1f, 0.5f, 0f, -0.5f, 0f),
            p("Head", 0, 1, 0f, 1.9f, 0f, 0.6f, 0.6f, 0.6f, 0f, 1.5f, 0f),
            p("Right Arm", 0, 2, -1.5f, 0.5f, 0f, 0.5f, 1f, 0.5f, -1.5f, 1.5f, 0f),
            p("Left Arm", 0, 3, 1.5f, 0.5f, 0f, 0.5f, 1f, 0.5f, 1.5f, 1.5f, 0f),
            p("Right Leg", -1, 4, -0.5f, -1.5f, 0f, 0.5f, 1f, 0.5f, -0.5f, -0.5f, 0f),
            p("Left Leg", -1, 5, 0.5f, -1.5f, 0f, 0.5f, 1f, 0.5f, 0.5f, -0.5f, 0f)
        ), 0f, 0, 2, -1.0f)

        // R15: 15 peças com cotovelo, joelho e pulso (proporções ajustadas para caber na mesma caixa de colisão do R6)
        val R15 = Rig("R15", arrayOf(
            p("UpperTorso", 6, 0, 0f, 0.7f, 0f, 1f, 0.8f, 0.5f, 0f, -0.1f, 0f, 0f, 0.8f),
            p("Head", 0, 1, 0f, 1.9f, 0f, 0.6f, 0.6f, 0.6f, 0f, 1.5f, 0f),
            p("RightUpperArm", 0, 2, -1.5f, 1.05f, 0f, 0.5f, 0.45f, 0.5f, -1.5f, 1.35f, 0f, 0f, 0.45f),
            p("LeftUpperArm", 0, 3, 1.5f, 1.05f, 0f, 0.5f, 0.45f, 0.5f, 1.5f, 1.35f, 0f, 0f, 0.45f),
            p("RightUpperLeg", 6, 4, -0.5f, -1.0f, 0f, 0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0f, 0f, 0.5f),
            p("LeftUpperLeg", 6, 5, 0.5f, -1.0f, 0f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0f, 0f, 0.5f),
            p("LowerTorso", -1, 0, 0f, -0.3f, 0f, 1f, 0.2f, 0.5f, 0f, -0.3f, 0f, 0.8f, 1f),
            p("RightLowerArm", 2, 2, -1.5f, 0.2f, 0f, 0.5f, 0.4f, 0.5f, -1.5f, 0.6f, 0f, 0.45f, 0.85f),
            p("RightHand", 7, 2, -1.5f, -0.35f, 0f, 0.5f, 0.15f, 0.5f, -1.5f, -0.2f, 0f, 0.85f, 1f),
            p("LeftLowerArm", 3, 3, 1.5f, 0.2f, 0f, 0.5f, 0.4f, 0.5f, 1.5f, 0.6f, 0f, 0.45f, 0.85f),
            p("LeftHand", 9, 3, 1.5f, -0.35f, 0f, 0.5f, 0.15f, 0.5f, 1.5f, -0.2f, 0f, 0.85f, 1f),
            p("RightLowerLeg", 4, 4, -0.5f, -1.9f, 0f, 0.5f, 0.4f, 0.5f, -0.5f, -1.5f, 0f, 0.5f, 0.9f),
            p("RightFoot", 11, 4, -0.5f, -2.4f, 0f, 0.5f, 0.1f, 0.5f, -0.5f, -2.3f, 0f, 0.9f, 1f),
            p("LeftLowerLeg", 5, 5, 0.5f, -1.9f, 0f, 0.5f, 0.4f, 0.5f, 0.5f, -1.5f, 0f, 0.5f, 0.9f),
            p("LeftFoot", 13, 5, 0.5f, -2.4f, 0f, 0.5f, 0.1f, 0.5f, 0.5f, -2.3f, 0f, 0.9f, 1f)
        ), 0.2f, 6, 8, -0.15f)

        fun current(): Rig = if (Settings.rigR15) R15 else R6
    }
}
