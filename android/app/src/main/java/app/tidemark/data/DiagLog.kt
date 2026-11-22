package app.tidemark.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local diagnostics log (replaces remote crash reporting). A ring buffer of
 * the last [MAX_LINES] lines in app-private storage. Lines describe what the
 * app did (service connected, surface entered by id or fallback, versions),
 * never what was on screen. The user can read, share or clear it in Settings.
 */
object DiagLog {
    private const val FILE = "diagnostics.log"
    private const val MAX_LINES = 500
    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun add(context: Context, event: String) {
        val clean = event.replace('\n', ' ').take(200)
        synchronized(lock) {
            val f = File(context.filesDir, FILE)
            val lines = if (f.exists()) f.readLines().takeLast(MAX_LINES - 1) else emptyList()
            f.writeText((lines + "${fmt.format(Date())}  $clean").joinToString("\n") + "\n")
        }
    }

    fun read(context: Context): List<String> = synchronized(lock) {
        val f = File(context.filesDir, FILE)
        if (f.exists()) f.readLines() else emptyList()
    }

    fun clear(context: Context) = synchronized(lock) { File(context.filesDir, FILE).delete(); Unit }
}
