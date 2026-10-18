package dev.vigil.data

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import dev.vigil.engine.Vigil
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * EXPORT (design 2.3): the progress report (markdown) and the journal (CSV) leave the phone through the Android share
 * sheet. Both files are written to the cache (`export/`, see res/xml/file_paths.xml) and shared through VIGIL's
 * FileProvider, so the receiving app gets read access to exactly these two files.
 */
object ReportExporter {
    private const val TAG = "VigilExport"
    private const val DIR = "export"

    /** The FileProvider authority declared in the manifest. */
    fun authority(context: Context): String = "${context.packageName}.files"

    /**
     * Builds both files and opens the share sheet. The work runs off the main thread; [onDone] (main thread) says
     * whether the share sheet opened.
     */
    fun share(context: Context, onDone: ((Boolean) -> Unit)? = null) {
        val main = Handler(Looper.getMainLooper())
        thread(name = "vigil-export") {
            val uris = try {
                write(context)
            } catch (e: Exception) {
                // Storage errors, or a damaged journal the report cannot be built from.
                Log.e(TAG, "Could not build the export", e)
                null
            }
            main.post {
                val opened = uris != null && open(context, uris)
                onDone?.invoke(opened)
            }
        }
    }

    /** Writes the report and the CSV into the cache and returns their content URIs. Blocking: not on the main thread. */
    @Throws(IOException::class)
    fun write(context: Context): List<Uri> {
        val (markdown, csv) = Vigil.report()
        val dir = File(context.cacheDir, DIR)
        dir.mkdirs()
        // Only the newest export is kept.
        dir.listFiles()?.forEach { it.delete() }
        val stamp = LocalDateTime.now().format(STAMP)
        val report = File(dir, "vigil-report-$stamp.md").apply { writeText(markdown) }
        val journal = File(dir, "vigil-journal-$stamp.csv").apply { writeText(csv) }
        val authority = authority(context)
        return listOf(report, journal).map { FileProvider.getUriForFile(context, authority, it) }
    }

    private fun open(context: Context, uris: List<Uri>): Boolean {
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_SUBJECT, "VIGIL report")
            // The chooser and the receiving app read the files through these grants.
            clipData = ClipData.newRawUri("VIGIL report", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Export VIGIL report")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open the share sheet: ${e.message}")
            false
        }
    }

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
}
