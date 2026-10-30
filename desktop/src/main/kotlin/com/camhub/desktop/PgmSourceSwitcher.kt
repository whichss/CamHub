package com.camhub.desktop

/** Keeps preview selection separate from the source currently on program output. */
class PgmSourceSwitcher(
    program: PgmSource = PgmSource.Camera(0),
    preview: PgmSource = PgmSource.Camera(0)
) {
    @Volatile
    var program: PgmSource = program
        private set

    @Volatile
    var preview: PgmSource = preview
        private set

    @Synchronized
    fun selectPreview(source: PgmSource) {
        preview = source
    }

    /** Takes the preview source to program; returns null when there is no preview source. */
    @Synchronized
    fun cut(): PgmSource? {
        if (preview == PgmSource.None) return null
        program = preview
        return program
    }

    @Synchronized
    fun clear(source: PgmSource) {
        if (preview == source) preview = PgmSource.None
        if (program == source) program = PgmSource.None
    }
}
