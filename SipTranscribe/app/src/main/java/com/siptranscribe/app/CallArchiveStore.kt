package com.siptranscribe.app

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Encrypted per-call archive on disk.
 *
 * Files live at `<filesDir>/call_archives/<callId>.json`, AES-256-GCM
 * encrypted via Jetpack Security's [EncryptedFile]. The master key is
 * stored in the Android Keystore (hardware-backed where available);
 * losing the keystore (e.g. user resets the device or wipes app data)
 * makes the files unrecoverable, which is the intended privacy
 * guarantee.
 *
 * All public functions fail soft: failures are logged and the call
 * succeeds — losing a transcript is preferable to crashing the app
 * in front of an elderly user.
 */
object CallArchiveStore {

    private const val TAG = "CallArchiveStore"
    private const val DIR = "call_archives"

    fun save(context: Context, archive: CallArchive) {
        try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, "${archive.callId}.json")
            // EncryptedFile refuses to overwrite, so we always remove the
            // existing file first. This is also how we apply updates: a
            // second call to save() simply replaces the previous archive.
            if (file.exists()) file.delete()
            encryptedFile(context, file).openFileOutput().use { out ->
                out.write(toJson(archive).toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w(TAG, "save failed for callId=${archive.callId}", e)
        }
    }

    fun load(context: Context, callId: Long): CallArchive? {
        val file = File(context.filesDir, "$DIR/$callId.json")
        if (!file.exists()) return null
        return try {
            val bytes = encryptedFile(context, file).openFileInput().use { it.readBytes() }
            fromJson(callId, JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.w(TAG, "load failed for callId=$callId", e)
            null
        }
    }

    fun delete(context: Context, callId: Long) {
        File(context.filesDir, "$DIR/$callId.json").delete()
    }

    /**
     * Drop any archive file whose callId isn't in [keepIds]. Called from
     * [CallHistory.purgeExpired] so we don't leak files when their
     * matching metadata record gets purged or corrupted.
     */
    fun purgeOrphans(context: Context, keepIds: Set<Long>) {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { f ->
            val id = f.nameWithoutExtension.toLongOrNull()
            if (id == null || id !in keepIds) {
                if (!f.delete()) Log.w(TAG, "Failed to delete orphan: ${f.name}")
            }
        }
    }

    private fun encryptedFile(context: Context, file: File): EncryptedFile {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            context, file, masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    private fun toJson(a: CallArchive): JSONObject {
        val turns = JSONArray()
        a.transcriptTurns.forEach { t ->
            turns.put(JSONObject().apply {
                put("label", t.speakerLabel)
                put("text", t.text)
            })
        }
        return JSONObject().apply {
            put("turns", turns)
            if (a.summaryText.isNullOrBlank()) put("summary", JSONObject.NULL)
            else put("summary", a.summaryText)
        }
    }

    private fun fromJson(callId: Long, o: JSONObject): CallArchive {
        val turnsArr = o.optJSONArray("turns") ?: JSONArray()
        val turns = (0 until turnsArr.length()).map { i ->
            val t = turnsArr.optJSONObject(i) ?: return@map CallArchive.TranscriptTurn("", "")
            CallArchive.TranscriptTurn(
                speakerLabel = t.optString("label", ""),
                text = t.optString("text", "")
            )
        }
        val summary = if (o.isNull("summary")) null
            else o.optString("summary").takeIf { it.isNotBlank() }
        return CallArchive(callId, turns, summary)
    }
}
