package io.github.jqssun.airplay.ui

import io.github.jqssun.airplay.Pref

// Pure helpers behind the TV screens, kept free of Compose and Android so they can be unit tested.

/** Podcast episodes and audiobook chapters run longer than this; songs almost never do. */
const val LONG_FORM_MS = 20 * 60 * 1000L

/**
 * Whether next/previous should be drawn as skip back/forward. The sender decides what the DACP
 * commands do, and podcast apps skip within the episode instead of changing track.
 */
fun isLongForm(durationMs: Long): Boolean = durationMs > LONG_FORM_MS

/** Playback position as m:ss, or h:mm:ss from an hour up; negative input reads as zero. */
fun formatTime(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

/** Fraction of [durationMs] played, 0 when the duration is unknown. */
fun progressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** The option after [current] in [options], wrapping; an unknown current value starts at the first. */
fun <T> nextOption(options: List<T>, current: T): T {
    require(options.isNotEmpty())
    val i = options.indexOf(current)
    return options[if (i < 0) 0 else (i + 1) % options.size]
}

/**
 * Parses [text] for [pref] and returns the value only if the pref would accept it, so an editor
 * dialog can refuse bad input instead of silently storing the default.
 */
fun <T : Any> parseFor(pref: Pref<T>, text: String): T? =
    pref.parse(text.trim())?.takeIf(pref::valid)

/**
 * A play/pause the viewer asked for. The sender confirms seconds later, and the service reports
 * "playing" on any progress update meanwhile, a pause included, so the raw state flicks back and
 * forth. The request is shown until the sender has agreed with it for [settleMs] without a break,
 * or [maxMs] has passed (a sender that ignores the command must not leave the icon wrong).
 */
class PlayRequest(
    val playing: Boolean,
    private val at: Long,
    private val settleMs: Long = 2_000,
    private val maxMs: Long = 15_000,
) {
    private var agreedSince = -1L

    /** Feed the sender's current report; true once the request can be dropped. */
    fun settled(reported: Boolean, now: Long): Boolean {
        if (now - at >= maxMs) return true
        if (reported != playing) {
            agreedSince = -1
            return false
        }
        if (agreedSince < 0) agreedSince = now
        return now - agreedSince >= settleMs
    }
}
