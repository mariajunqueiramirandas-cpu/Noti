package app.noti.ui.study

import android.graphics.Bitmap
import androidx.compose.runtime.Stable

/**
 * Multiple choice items that are checked somewhere else (Plataforma Assaad): the sheet
 * shows them like any other item, a tap picks an option, and [submit] sends it to be
 * checked. The result comes back into the board's [app.noti.study.StudyStore] as the
 * chosen option and the right one, so the card looks like every other answered card.
 * Reads are Compose state.
 */
@Stable
interface RemoteChoices {
    /** The short name of where answers are checked, for labels ("Assaad"). */
    val name: String

    /** This item is checked remotely. */
    fun owns(itemId: String): Boolean

    /** What's happening with this item's answer, or null before an option is picked. */
    fun state(itemId: String): RemoteState?

    /** The option picked and not yet checked, or -1. */
    fun picked(itemId: String): Int

    fun pick(itemId: String, index: Int)
    /** Sends this one answer now. Answers go one at a time, each by its own tap; nothing queues up. */
    fun submit(itemId: String)
    /** Not accepted: try this one again. Unknown: the person checked, another attempt may go. */
    fun retry(itemId: String)

    /** Connected right now, with no other answer on its way. */
    val canSubmit: Boolean
    /** Another answer is on its way. */
    val sending: Boolean

    /** Somewhere to look when the answer can't go yet (the platform's own page). */
    fun openPlatform()
    /** Saved sets and connection. */
    fun openConnection()

    /** A line about the connection when answers can't go right now, or null when all is well. */
    val connectionNote: String?

    /** An image URL's picture when it's ready; asking starts loading it. */
    fun figure(url: String): Bitmap?
}

data class RemoteState(val phase: Phase, val message: String) {
    enum class Phase {
        /** Picked, not sent. */
        Draft,
        /** Sent, waiting for the result. */
        Checking,
        /** Checked: the card shows right or wrong. */
        Done,
        /** Not accepted (signed out, paused, a pause requested); resumes after a look. */
        Blocked,
        /** Sent, but no result came back: check before another attempt. */
        Unknown,
    }

    val locked get() = phase == Phase.Checking || phase == Phase.Done || phase == Phase.Unknown
}
