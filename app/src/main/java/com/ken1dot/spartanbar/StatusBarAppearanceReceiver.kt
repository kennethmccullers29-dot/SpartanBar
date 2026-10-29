package com.ken1dot.spartanbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Companion apps send this broadcast on resume and whenever their top surface changes. */
class StatusBarAppearanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = intent.getStringExtra("source_package") ?: return
        if (app.isBlank() || !intent.hasExtra("color")) return
        context.getSharedPreferences("shared_bar_appearance", Context.MODE_PRIVATE).edit()
            .putInt("$app.color", intent.getIntExtra("color", 0xff101010.toInt()))
            .apply {
                if (intent.hasExtra("dark_icons"))
                    putBoolean("$app.dark_icons", intent.getBooleanExtra("dark_icons", false))
                else remove("$app.dark_icons")
            }.apply()
        context.sendBroadcast(Intent(MetroAccessibilityService.ACTION_UPDATE)
            .setPackage(context.packageName))
    }

    companion object {
        const val ACTION = "com.ken1dot.spartanbar.SET_APPEARANCE"
        const val PERMISSION = "com.ken1dot.spartanbar.permission.SET_APPEARANCE"
    }
}
