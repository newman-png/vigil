package dev.vigil.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.vigil.core.machine.Effect
import java.io.File

/**
 * The user's own audio files (design M8, AUDIO > MY FILES): copied into the app's storage (`files/audio`), so they play
 * whatever happens to the original, each under an id (its file name there) and a name the user can change.
 */
object AudioFiles {
    private const val TAG = "VigilAudio"
    private const val NAMES = "audio_names"
    private const val MAX_NAME_CHARS = 60

    /** One file: [id] is what `file:<id>` plays, [name] what the user sees. */
    data class AudioFile(val id: String, val name: String, val bytes: Long)

    private fun dir(context: Context): File = File(context.filesDir, "audio").apply { mkdirs() }

    private fun names(context: Context) = context.getSharedPreferences(NAMES, Context.MODE_PRIVATE)

    /** Every file, by name. */
    fun list(context: Context): List<AudioFile> =
        (dir(context).listFiles() ?: emptyArray())
            .filter { it.isFile && Effect.Audio.valid(Effect.Audio.FILE_PREFIX + it.name) != null }
            .map { AudioFile(it.name, name(context, it.name), it.length()) }
            .sortedBy { it.name.lowercase() }

    /** The name the user sees for file [id] (its id when it has none). */
    fun name(context: Context, id: String): String = names(context).getString(id, null) ?: id

    /** The stored file [id], or null when there is none. */
    fun file(context: Context, id: String): File? {
        if (Effect.Audio.valid(Effect.Audio.FILE_PREFIX + id) == null) return null
        return File(dir(context), id).takeIf { it.isFile }
    }

    /**
     * Copies the document at [uri] (picked with the system's file picker) into the app. Blocking: run it off the main
     * thread. Returns the new file, or null when it could not be read.
     */
    fun add(context: Context, uri: Uri): AudioFile? {
        val display = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "Audio"
        val ext = display.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(5)
        val id = "a" + System.currentTimeMillis().toString(36) + if (ext.isNotEmpty()) ".$ext" else ""
        val target = File(dir(context), id)
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            val name = display.substringBeforeLast('.').trim().take(MAX_NAME_CHARS).ifEmpty { "Audio" }
            names(context).edit().putString(id, name).apply()
            AudioFile(id, name, target.length())
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy $uri: ${e.message}")
            target.delete()
            null
        }
    }

    /** Gives file [id] the name [name] (blank keeps the old one). */
    fun rename(context: Context, id: String, name: String) {
        val clean = name.trim().take(MAX_NAME_CHARS)
        if (clean.isNotEmpty()) names(context).edit().putString(id, clean).apply()
    }

    /** Deletes file [id] and its name. */
    fun delete(context: Context, id: String) {
        file(context, id)?.delete()
        names(context).edit().remove(id).apply()
    }
}
