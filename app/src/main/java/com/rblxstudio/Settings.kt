package com.rblxstudio

import android.content.Context

// Configurações do jogo (menu de pausa): Shift Lock, câmera e tipo de movimento
object Settings {
    const val CAM_FOLLOW = 0   // câmera segue o personagem
    const val CAM_CLASSIC = 1  // câmera livre
    const val MOVE_JOYSTICK = 0
    const val MOVE_DPAD = 1
    const val MOVE_TAP = 2

    @Volatile var shiftLockSwitch = true   // mostra o botão de Shift Lock
    @Volatile var camMode = CAM_CLASSIC
    @Volatile var moveMode = MOVE_JOYSTICK
    @Volatile var firstPerson = false

    private const val PREFS = "game_settings"

    fun load(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        shiftLockSwitch = p.getBoolean("shiftLockSwitch", true)
        camMode = p.getInt("camMode", CAM_CLASSIC)
        moveMode = p.getInt("moveMode", MOVE_JOYSTICK)
        firstPerson = p.getBoolean("firstPerson", false)
    }

    fun save(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("shiftLockSwitch", shiftLockSwitch)
            .putInt("camMode", camMode)
            .putInt("moveMode", moveMode)
            .putBoolean("firstPerson", firstPerson)
            .apply()
    }
}
