package com.overdrive.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

/** 1x1 pet-mode button: tap to start, tap again to stop. Shows ON/OFF and the live cabin temperature. */
class SealDrivePetWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.overdrive.app.PET_TOGGLE"

        private fun render(ctx: Context): RemoteViews {
            val on = PetMode.isActive(ctx)
            val cabin = PetMode.cabin(ctx)
            val (text, color) = when {
                on -> ("ON" + (cabin?.let { " · $it" } ?: "")) to "#2BD4A7"
                else -> "Tap to start" to "#8FA6BD"
            }
            val tap = PendingIntent.getBroadcast(ctx, 6,
                Intent(ctx, SealDrivePetWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteViews(ctx.packageName, R.layout.widget_pet).apply {
                setTextViewText(R.id.pet_status, text)
                setTextColor(R.id.pet_status, Color.parseColor(color))
                setInt(R.id.pet_icon, "setColorFilter", Color.parseColor(if (on) "#2BD4A7" else "#8FA6BD"))
                setInt(R.id.widget_root, "setBackgroundResource", when (Config.bg(ctx)) {
                    0 -> R.drawable.widget_bg_clear
                    1 -> R.drawable.widget_bg_glass
                    else -> R.drawable.widget_bg
                })
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
        }

        /** Redraws every pet button (state changed, or the background setting did). */
        fun redraw(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SealDrivePetWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, render(ctx))
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, render(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_TOGGLE) PetMode.toggle(ctx)
    }
}
