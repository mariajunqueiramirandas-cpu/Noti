package app.noti.ui.study

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import app.noti.study.Progress
import app.noti.study.StudyItem
import app.noti.study.StudySource
import app.noti.study.Verdict

/** Which source the floating study sheet is currently showing. */
enum class StudySourceTab(val label: String) {
    Deck("Deck"),
    Assaad("Assaad"),
    Exams("Exams"),
}

/** What to focus when opening the editor from the Study hub or Assaad browser. */
sealed interface StudyLaunchTarget {
    data class Deck(val itemId: String? = null) : StudyLaunchTarget
    data class Assaad(val questionId: String? = null) : StudyLaunchTarget
    data class Exam(val questionKey: String) : StudyLaunchTarget
}

/** A filter choice inside the Study sheet (e.g. a saved Assaad set or an imported past exam). */
data class StudyFilterOption(val id: String, val label: String, val detail: String = "")

/**
 * Multiple choice items that are checked somewhere else (Plataforma Assaad): the sheet
 * shows them on ANY canvas, a tap picks an option, and [submit] sends it to be checked.
 * Reads are Compose state.
 */
@Stable
interface RemoteChoices {
    /** The short name of where answers are checked, for labels ("Assaad"). */
    val name: String

    /** Saved questions currently visible in the sheet (filtered by [selectedFilterId]). */
    val items: List<StudyItem>
    /** All saved questions across every set. */
    val allItems: List<StudyItem>
    /** Selectable question sets. */
    val filters: List<StudyFilterOption>
    /** Active set id, or null for all sets. */
    val selectedFilterId: String?
    fun selectFilter(id: String?)

    /** This item is checked remotely. */
    fun owns(itemId: String): Boolean

    /** Live study item for [itemId], even if outside the current set filter. */
    fun item(itemId: String): StudyItem?

    /** Live progress for [itemId], reflecting confirmed verdicts and SRS. */
    fun progressOf(itemId: String): Progress

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

/**
 * Past exam questions imported from any PDF, accessible on ANY canvas through the Study sheet.
 * Progress and XP are tracked globally in ExamStore while pinned cards live on whichever
 * canvas you print them onto.
 */
@Stable
interface ExamFeed {
    /** Questions visible under the current filter. */
    val items: List<StudyItem>
    /** Selectable imported exams. */
    val filters: List<StudyFilterOption>
    /** Active exam sourceId filter, or null for all exams. */
    val selectedFilterId: String?
    fun selectFilter(id: String?)

    fun owns(itemId: String): Boolean
    fun item(itemId: String): StudyItem?
    fun sourceOf(itemId: String): StudySource?
    fun progressOf(itemId: String): Progress

    fun hint(itemId: String)
    fun reveal(itemId: String)
    fun choose(itemId: String, index: Int)
    fun assessWritten(itemId: String, verdict: Verdict)
    fun reset(itemId: String)

    /** Opens the Study hub to import or browse past exams and lessons. */
    fun openHub()
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
