package com.ken1dot.spartanbar

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/** Live overlay availability for companion apps signed with the same key. */
internal object StatusBarBridgeState {
    val URI: Uri = Uri.parse("content://com.ken1dot.spartanbar.state/overlay")
    @Volatile var active = false
        private set

    fun setActive(context: Context, value: Boolean) {
        if (active == value) return
        active = value
        context.contentResolver.notifyChange(URI, null)
    }

    /** Bottom edge (px) of everything SpartanBar draws at the top: bar, plus call/live strip. */
    @Volatile var insetPx = 0
        private set

    fun setInset(context: Context, px: Int) {
        if (insetPx == px) return
        insetPx = px
        context.contentResolver.notifyChange(URI, null)
    }
}

class StatusBarStateProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri != StatusBarBridgeState.URI) return null
        return MatrixCursor(arrayOf("active", "inset")).apply {
            addRow(arrayOf(if (StatusBarBridgeState.active) 1 else 0, StatusBarBridgeState.insetPx))
            context?.contentResolver?.let { setNotificationUri(it, uri) }
        }
    }
    override fun getType(uri: Uri): String? =
        if (uri == StatusBarBridgeState.URI) "vnd.android.cursor.item/vnd.spartanbar.overlay" else null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
