package com.siptranscribe.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for [ContactSuggestion] records.
 *
 * Backed by plain SharedPreferences JSON — the data is metadata (phone
 * number + a name string) which already lives unencrypted in the call
 * history. The actual call content is stored separately by
 * [CallArchiveStore] with encryption.
 *
 * Phone numbers are matched loosely (last 7 digits) so the same person's
 * landline and mobile aren't treated as separate suggestions when they
 * already match the same Contacts.PhoneLookup heuristic Android applies.
 */
object ContactSuggestionStore {

    private const val TAG = "ContactSuggestionStore"
    private const val PREFS = "contact_suggestions"
    private const val KEY = "items"

    /** Calls shorter than this are mostly hangups/voicemail/spam; ignore
     *  them as suggestion sources so the caregiver isn't flooded. */
    private const val MIN_CALL_DURATION_SECONDS = 30

    /**
     * Records a new suggestion or reinforces an existing one. Returns true
     * if the store was modified.
     *
     * Skipped when:
     *  - phone number or name is blank
     *  - call too short ([MIN_CALL_DURATION_SECONDS])
     *  - a system contact already exists for this number (we auto-mark
     *    any matching pending suggestion as accepted at the same time)
     */
    fun maybeRecord(
        context: Context,
        phoneNumber: String,
        suggestedName: String,
        callDurationSeconds: Int
    ): Boolean {
        val number = phoneNumber.trim()
        val name = suggestedName.trim()
        if (number.isBlank() || name.isBlank()) return false
        if (callDurationSeconds < MIN_CALL_DURATION_SECONDS) return false

        if (ContactsLookup.displayNameForNumber(context, number) != null) {
            // Already a known contact — no new suggestion needed, and any
            // earlier pending suggestion for this number is now obsolete.
            markStatus(context, number, ContactSuggestion.Status.ACCEPTED)
            return false
        }

        val list = load(context)
        val now = System.currentTimeMillis()
        val existing = list.find { sameNumber(it.phoneNumber, number) }
        val nextList = if (existing != null) {
            val merged = existing.copy(
                // Keep the longest variant we've seen — "Maria Schmidt" is
                // more useful than "Maria" if both occurred.
                suggestedName = if (name.length > existing.suggestedName.length)
                    name else existing.suggestedName,
                lastSeenAt = now,
                callCount = existing.callCount + 1,
                totalDurationSeconds = existing.totalDurationSeconds + callDurationSeconds,
                // DISMISSED is sticky — caregiver said no, don't re-surface.
                status = if (existing.status == ContactSuggestion.Status.DISMISSED)
                    ContactSuggestion.Status.DISMISSED
                else
                    ContactSuggestion.Status.PENDING
            )
            list.map { if (sameNumber(it.phoneNumber, number)) merged else it }
        } else {
            list + ContactSuggestion(
                phoneNumber = number,
                suggestedName = name,
                firstSeenAt = now,
                lastSeenAt = now,
                callCount = 1,
                totalDurationSeconds = callDurationSeconds,
                status = ContactSuggestion.Status.PENDING
            )
        }
        save(context, nextList)
        return true
    }

    fun loadPending(context: Context): List<ContactSuggestion> =
        load(context)
            .filter { it.status == ContactSuggestion.Status.PENDING }
            .sortedByDescending { it.lastSeenAt }

    fun markStatus(context: Context, phoneNumber: String, status: ContactSuggestion.Status) {
        val list = load(context)
        val updated = list.map {
            if (sameNumber(it.phoneNumber, phoneNumber)) it.copy(status = status) else it
        }
        if (updated != list) save(context, updated)
    }

    /**
     * Walk the pending list and promote anything that now matches a real
     * system contact to ACCEPTED. Call this after the caregiver returns
     * from the contacts app — handles the "added via Intent → no result
     * code came back" case without trying to interpret activity results.
     */
    fun reconcileWithContacts(context: Context) {
        if (!ContactsLookup.hasPermission(context)) return
        val list = load(context)
        val updated = list.map { s ->
            if (s.status == ContactSuggestion.Status.PENDING &&
                ContactsLookup.displayNameForNumber(context, s.phoneNumber) != null
            ) {
                s.copy(status = ContactSuggestion.Status.ACCEPTED)
            } else s
        }
        if (updated != list) save(context, updated)
    }

    fun load(context: Context): List<ContactSuggestion> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed", e)
            emptyList()
        }
    }

    private fun save(context: Context, items: List<ContactSuggestion>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Loose phone-number equality: compares the last 7 digits, matching
     * Android's `PhoneLookup` behaviour. Same trick handles "+4930123456",
     * "030123456", and "0049 30 123456" as the same number.
     */
    private fun sameNumber(a: String, b: String): Boolean {
        val tailA = a.filter { it.isDigit() }.takeLast(7)
        val tailB = b.filter { it.isDigit() }.takeLast(7)
        return tailA.isNotEmpty() && tailA == tailB
    }

    private fun toJson(s: ContactSuggestion) = JSONObject().apply {
        put("phoneNumber", s.phoneNumber)
        put("suggestedName", s.suggestedName)
        put("firstSeenAt", s.firstSeenAt)
        put("lastSeenAt", s.lastSeenAt)
        put("callCount", s.callCount)
        put("totalDurationSeconds", s.totalDurationSeconds)
        put("status", s.status.name)
    }

    private fun fromJson(o: JSONObject) = ContactSuggestion(
        phoneNumber = o.getString("phoneNumber"),
        suggestedName = o.getString("suggestedName"),
        firstSeenAt = o.getLong("firstSeenAt"),
        lastSeenAt = o.getLong("lastSeenAt"),
        callCount = o.getInt("callCount"),
        totalDurationSeconds = o.getInt("totalDurationSeconds"),
        status = ContactSuggestion.Status.valueOf(o.getString("status"))
    )
}
