package com.camhub.desktop

/** A PGM input is independent of the four remote-camera preview slots. */
sealed interface PgmSource {
    data class Camera(val slot: Int) : PgmSource {
        init { require(slot in 0..3) }
    }

    data object DesktopScreen : PgmSource
    data object None : PgmSource
}
