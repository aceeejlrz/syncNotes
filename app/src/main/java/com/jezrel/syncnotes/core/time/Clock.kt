package com.jezrel.syncnotes.core.time

/** Injectable clock so tests can control time. See ARCHITECTURE.md §5.2. */
interface Clock {
    fun nowMillis(): Long
}

class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
