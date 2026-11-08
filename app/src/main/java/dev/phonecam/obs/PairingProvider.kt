package dev.phonecam.obs

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder

/**
 * Hands the live-connection key to the Mac's ADB shell, and to nothing else.
 *
 * `adb shell content query --uri content://dev.phonecam.obs.pairing/token` runs as Android's
 * shell user (uid 2000), which exists only for an authorized USB/wireless debugging connection.
 * Other apps on the phone get no rows, so they cannot learn the key and connect to the camera
 * socket. This replaces the debug-only `run-as` read, so release builds pair automatically.
 */
class PairingProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "dev.phonecam.obs.pairing"
        /** android.os.Process.SHELL_UID is hidden API; the value is fixed by the platform. */
        const val SHELL_UID = 2000
        fun allowed(uid: Int) = uid == SHELL_UID
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (!allowed(Binder.getCallingUid()) || uri.path != "/token") return null
        val directory = context?.filesDir ?: return null
        return MatrixCursor(arrayOf("token")).apply { addRow(arrayOf(LiveTransport.token(directory))) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
