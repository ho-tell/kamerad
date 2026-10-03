package at.tellioglu.kamerad.gopro

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A short history of what happened with the camera (connecting, losing it, rejected commands, battery
 * levels, ...), kept on the Karoo so it can be read later: in the app (About, Event log) or as the text file
 * `kamerad-events.txt` in the app's folder. Only the last [MAX_LINES] events are kept. No personal data.
 */
object EventLog {
    private const val FILE_NAME = "kamerad-events.txt"
    private const val MAX_LINES = 400

    private val writer = Executors.newSingleThreadExecutor()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    private var file: File? = null

    private val _entries = MutableStateFlow<List<String>>(emptyList())

    /** All kept events, oldest first. */
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    /** Where the history is saved (readable with `adb pull`), or null before [init]. */
    val path: String? get() = file?.absolutePath

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        // App-specific external storage needs no permission and can be read over adb, also with a release build
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, FILE_NAME)
        if (f.exists()) {
            try {
                f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
            } catch (_: IOException) {
                // start with an empty history
            }
        }
        file = f
        _entries.value = lines.toList()
    }

    @Synchronized
    fun add(message: String) {
        val line = "${format.format(Date())}  $message"
        lines.addLast(line)
        var trimmed = false
        while (lines.size > MAX_LINES) {
            lines.removeFirst()
            trimmed = true
        }
        _entries.value = lines.toList()
        val all = if (trimmed) lines.toList() else null
        val target = file ?: return
        writer.execute {
            try {
                if (all != null) target.writeText(all.joinToString("\n", postfix = "\n")) else target.appendText(line + "\n")
            } catch (_: IOException) {
                // the history is a convenience: never fail because of it
            }
        }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        _entries.value = emptyList()
        val target = file ?: return
        writer.execute {
            try {
                target.writeText("")
            } catch (_: IOException) {
                // see above
            }
        }
    }
}
