package com.siptranscribe.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget that one-taps a SIP call to a single configured
 * contact. Multiple instances are supported — each widgetId has its own
 * (name, number) in SharedPreferences so the caregiver can add one
 * widget per important person.
 *
 * Tap → MainActivity is launched with [EXTRA_AUTO_DIAL_NUMBER]. Main
 * handles the rest: triggers SIP registration if not already up, waits
 * for it, then makes the call. Direct-to-CallActivity isn't safe
 * because the call activity expects the linphone core to be initialised
 * and the account registered.
 */
class ContactWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { id ->
            editor.remove(keyName(id))
            editor.remove(keyNumber(id))
        }
        editor.apply()
    }

    companion object {
        const val PREFS = "contact_widgets"
        const val EXTRA_AUTO_DIAL_NUMBER = "com.siptranscribe.app.AUTO_DIAL_NUMBER"
        const val EXTRA_AUTO_DIAL_NAME = "com.siptranscribe.app.AUTO_DIAL_NAME"

        fun keyName(widgetId: Int) = "widget_${widgetId}_name"
        fun keyNumber(widgetId: Int) = "widget_${widgetId}_number"

        /**
         * Renders the widget for a given id from its persisted (name, number).
         * Called from both [onUpdate] and from [WidgetConfigActivity] after
         * the user picks a contact, so the widget refreshes instantly without
         * waiting for the next system update tick.
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val name = prefs.getString(keyName(widgetId), null)?.takeIf { it.isNotBlank() }
            val number = prefs.getString(keyNumber(widgetId), null)?.takeIf { it.isNotBlank() }

            val views = RemoteViews(context.packageName, R.layout.widget_contact)
            views.setTextViewText(R.id.widget_name, name ?: "Kontakt wählen")
            views.setTextViewText(
                R.id.widget_subtitle,
                if (number != null) "Anrufen" else "Tippen zum Einrichten"
            )

            // The PendingIntent's request code is the widgetId so each widget
            // gets a distinct intent — without that Android conflates multiple
            // widgets and they all end up dialling the same number.
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (number != null) putExtra(EXTRA_AUTO_DIAL_NUMBER, number)
                if (name != null) putExtra(EXTRA_AUTO_DIAL_NAME, name)
            }
            val pi = PendingIntent.getActivity(
                context,
                widgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
