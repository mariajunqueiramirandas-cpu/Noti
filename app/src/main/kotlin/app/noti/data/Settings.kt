package app.noti.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val label: String) { System("Follow system"), Light("Light"), Dark("Dark") }

/** The engine's predictor models (InkLayer.setPredictor). */
enum class Predictor(val label: String, val engineId: Int) {
    Off("Off", 0), Linear("Linear", 1), Polynomial("Polynomial", 2), Kalman("Kalman", 3),
}

enum class Tool(val label: String, val available: Boolean) {
    Pen("Pen", true), Highlighter("Highlighter", true), Eraser("Eraser", false), Lasso("Lasso", false),
}

/** The three quick color slots and three quick width slots of a drawing tool. */
data class ToolSlots(
    val colors: List<Long>,
    val widths: List<Float>,
    val color: Int = 0,
    val width: Int = 1,
) {
    val currentColor get() = colors[color]
    val currentWidth get() = widths[width]

    fun encode() = (colors.map { it.toString(16) } + widths.map { it.toString() } + listOf("$color", "$width"))
        .joinToString(",")

    companion object {
        fun decode(s: String?, default: ToolSlots): ToolSlots = runCatching {
            val p = s!!.split(",")
            ToolSlots(
                colors = p.subList(0, 3).map { it.toLong(16) },
                widths = p.subList(3, 6).map { it.toFloat() },
                color = p[6].toInt().coerceIn(0, 2),
                width = p[7].toInt().coerceIn(0, 2),
            )
        }.getOrDefault(default)
    }
}

/** App settings in SharedPreferences, exposed as Compose state. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var theme by pref(ThemeMode.System, { enumOr(prefs.getString("theme", null), it) }) { putString("theme", it.name) }

    /** Off = constant width, whatever the pressure. */
    var pressureOn by pref(true, { prefs.getBoolean("pressureOn", it) }) { putBoolean("pressureOn", it) }

    /** 0 = light touch gives full width quickly … 1 = needs a firm press. Maps to the curve's gamma. */
    var pressureFirmness by pref(0.4f, { prefs.getFloat("pressureFirmness", it) }) { putFloat("pressureFirmness", it) }

    /** Plan §5.4 feel slider: the LCD response time the predictor covers, in ms. */
    var inkFeelMs by pref(5f, { prefs.getFloat("inkFeelMs", it) }) { putFloat("inkFeelMs", it) }

    var predictor by pref(Predictor.Kalman, { enumOr(prefs.getString("predictor", null), it) }) {
        putString("predictor", it.name)
    }

    var defaultTemplate by pref(PaperTemplate.Lined, { enumOr(prefs.getString("defTemplate", null), it) }) {
        putString("defTemplate", it.name)
    }
    var defaultColor by pref(PaperColor.White, { enumOr(prefs.getString("defColor", null), it) }) {
        putString("defColor", it.name)
    }

    /** Ask before leaving a notebook, since ink isn't saved yet. */
    var confirmLeave by pref(true, { prefs.getBoolean("confirmLeave", it) }) { putBoolean("confirmLeave", it) }

    /** A single deliberate finger draws. Palms never do, whatever this says. */
    var fingerDrawing by pref(false, { prefs.getBoolean("fingerDrawing", it) }) { putBoolean("fingerDrawing", it) }

    /**
     * The palm filter's learned model (hand position, handedness, contact sizes, timing),
     * so it doesn't start from scratch every time a canvas opens. Null until learned.
     */
    var palmModel by pref(null as FloatArray?, { decodeFloats(prefs.getString("palmModel", null)) ?: it }) {
        putString("palmModel", it?.joinToString(","))
    }

    var tool by pref(Tool.Pen, { enumOr(prefs.getString("tool", null), it) }) { putString("tool", it.name) }

    var penSlots by pref(DEFAULT_PEN, { ToolSlots.decode(prefs.getString("penSlots", null), it) }) {
        putString("penSlots", it.encode())
    }
    var highlighterSlots by pref(DEFAULT_HIGHLIGHTER, { ToolSlots.decode(prefs.getString("hlSlots", null), it) }) {
        putString("hlSlots", it.encode())
    }

    /** Pressure-curve gamma for the engine: 0.4 (light) … 1.4 (firm). */
    val pressureGamma get() = 0.4f + pressureFirmness * 1.0f

    private fun <T> pref(default: T, read: (T) -> T, write: android.content.SharedPreferences.Editor.(T) -> Unit) =
        PrefState(read(default)) { v -> prefs.edit().apply { write(v) }.apply() }

    companion object {
        val DEFAULT_PEN = ToolSlots(
            colors = listOf(0xFF1E1F24, 0xFF2D5BD3, 0xFFD23B3B),
            widths = listOf(3f, 5f, 9f),
        )
        val DEFAULT_HIGHLIGHTER = ToolSlots(
            colors = listOf(0xFFFFE14D, 0xFF8BE38B, 0xFFFF9ACB),
            widths = listOf(18f, 28f, 40f),
        )
    }
}

private fun decodeFloats(s: String?): FloatArray? =
    s?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()?.takeIf { it.isNotEmpty() }

/** A Compose state that also writes through to preferences. */
class PrefState<T>(initial: T, private val persist: (T) -> Unit) {
    private var state by mutableStateOf(initial)
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = state
    operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
        state = value
        persist(value)
    }
}
