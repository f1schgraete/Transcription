package com.siptranscribe.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Read-only phone-number → contact name resolver.
 *
 * Uses `ContactsContract.PhoneLookup`, which Android implements with loose
 * number matching (typically the last 7+ digits), so the same contact is
 * found whether the caller's number arrives as `+4930123456`, `030123456`,
 * or `0049 30 123456`.
 *
 * Returns null on any failure path — missing permission, no match, query
 * error — so callers can fall back to the SIP-provided name or the raw
 * number without having to handle exceptions.
 */
object ContactsLookup {

    private const val TAG = "ContactsLookup"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns one entry per system contact that has at least one phone
     * number, alphabetised by display name. When a contact has multiple
     * numbers we keep the first one the provider hands back — typical
     * Android default ordering puts the primary number first. If the
     * elderly user needs a non-primary number she can dial it from
     * history, which preserves the exact form that was used.
     */
    data class Entry(val displayName: String, val phoneNumber: String)

    fun loadAllContacts(context: Context): List<Entry> {
        if (!hasPermission(context)) return emptyList()
        return try {
            val out = mutableListOf<Entry>()
            val seen = HashSet<String>()
            context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = c.getString(1)?.trim().orEmpty()
                    if (name.isBlank() || number.isBlank()) continue
                    if (seen.add(name)) out.add(Entry(name, number))
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "loadAllContacts failed", e)
            emptyList()
        }
    }

    fun displayNameForNumber(context: Context, number: String): String? {
        if (number.isBlank()) return null
        if (!hasPermission(context)) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lookup failed for $number", e)
            null
        }
    }
}
