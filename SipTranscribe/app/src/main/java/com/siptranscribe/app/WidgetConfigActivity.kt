package com.siptranscribe.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Configuration activity launched when the user drags a fresh
 * ContactWidget onto the home screen. We don't draw a UI of our own —
 * the activity is themed transparent and immediately delegates to the
 * system contact picker. On return:
 *   - success → persist (name, number) for the widget id, push an
 *     updated RemoteViews so the widget shows the contact's name
 *     instantly, return RESULT_OK
 *   - cancel  → leave RESULT_CANCELED so the launcher drops the
 *     newly-added widget rather than leaving a blank one behind
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            saveContactForWidget(uri)
        } else {
            // Cancelled or no contact returned. Leave RESULT_CANCELED so
            // the launcher removes the pending widget.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default in case the user backs out of the picker — the launcher
        // checks this to decide whether to keep the widget.
        setResult(Activity.RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // ACTION_PICK against the Phone CONTENT_TYPE returns one specific
        // phone-number row, not just a contact — so a person with multiple
        // numbers shows up once per number and the caregiver picks exactly
        // which line to dial.
        val pickIntent = Intent(Intent.ACTION_PICK).apply {
            type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
        }
        try {
            pickContact.launch(pickIntent)
        } catch (e: Exception) {
            Log.e("WidgetConfig", "Couldn't launch contact picker", e)
            Toast.makeText(this, "Kontakte-App fehlt", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun saveContactForWidget(uri: Uri) {
        try {
            contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = c.getString(1)?.trim().orEmpty()
                    if (name.isNotBlank() && number.isNotBlank()) {
                        getSharedPreferences(ContactWidgetProvider.PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(ContactWidgetProvider.keyName(widgetId), name)
                            .putString(ContactWidgetProvider.keyNumber(widgetId), number)
                            .apply()
                        ContactWidgetProvider.updateWidget(
                            this,
                            AppWidgetManager.getInstance(this),
                            widgetId
                        )
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("WidgetConfig", "Couldn't read picked contact", e)
        }
        finish()
    }
}
