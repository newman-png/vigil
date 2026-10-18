package dev.vigil.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dev.vigil.core.times.MyTimesStore
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * A copy of MY TIMES ([MyTimesStore]: current times and the edit waiting for tomorrow) in a file of its own. The engine
 * state holds the live copy; this one keeps what the user typed when the state file is lost or cannot be read (a
 * damaged file, an incompatible format after an update), so a fresh state starts with the user's times.
 */
class MyTimesFile(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "state/my_times.json"))
    private val lock = Any()

    /** The saved copy, or null when there is none (never edited) or it cannot be read. */
    fun load(): MyTimesStore? = synchronized(lock) {
        val text = try {
            file.readFully().decodeToString()
        } catch (e: FileNotFoundException) {
            return null
        } catch (e: IOException) {
            Log.w(TAG, "Could not read MY TIMES: ${e.message}")
            return null
        }
        MyTimesStore.decode(text)
    }

    /** Saves [times]; a failed write is logged (the engine state still has them). */
    fun save(times: MyTimesStore) {
        synchronized(lock) {
            var out: FileOutputStream? = null
            try {
                file.baseFile.parentFile?.mkdirs()
                val stream = file.startWrite()
                out = stream
                stream.write(times.encode().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: IOException) {
                Log.w(TAG, "Could not save MY TIMES: ${e.message}")
                out?.let { file.failWrite(it) }
            }
        }
    }

    private companion object {
        const val TAG = "VigilMyTimes"
    }
}
