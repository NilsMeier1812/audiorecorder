package de.nilsmeier.audiorecorder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

/**
 * Empfängt Aufnahmen von der Watch über die Wearable Data Layer API (ChannelClient)
 * und speichert sie in Downloads/WatchRecordings. Wird von den Play Services
 * automatisch gestartet – die App muss dafür nicht laufen.
 */
class AudioReceiverService : WearableListenerService() {

    companion object {
        private const val PATH_PREFIX = "/audiorecorder/"
        private const val CHANNEL_ID = "received"
        private const val RELATIVE_DIR = "WatchRecordings"
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(PATH_PREFIX)) return
        // In eine temporäre Datei empfangen; erst nach sauberem Abschluss
        // (onInputClosed mit CLOSE_REASON_NORMAL) wird sie nach Downloads verschoben.
        Wearable.getChannelClient(this)
            .receiveFile(channel, Uri.fromFile(tempFileFor(channel)), false)
    }

    override fun onInputClosed(
        channel: ChannelClient.Channel,
        closeReason: Int,
        appSpecificErrorCode: Int
    ) {
        if (!channel.path.startsWith(PATH_PREFIX)) return
        val tempFile = tempFileFor(channel)
        // Die Watch schließt den Channel selbst nach erfolgreichem sendFile –
        // NORMAL und REMOTE_CLOSE bedeuten daher vollständige Daten, nur ein
        // Verbindungsabriss (DISCONNECTED) heißt unvollständig.
        val complete = closeReason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL ||
            closeReason == ChannelClient.ChannelCallback.CLOSE_REASON_REMOTE_CLOSE
        if (complete && tempFile.exists() && tempFile.length() > 0) {
            val fileName = sanitizeFileName(channel.path.removePrefix(PATH_PREFIX))
            val savedUri = saveToDownloads(tempFile, fileName)
            if (savedUri != null) {
                showNotification(fileName, savedUri)
            }
        }
        tempFile.delete()
        Wearable.getChannelClient(this).close(channel)
    }

    private fun tempFileFor(channel: ChannelClient.Channel): File =
        File(cacheDir, "incoming_${sanitizeFileName(channel.path.removePrefix(PATH_PREFIX))}")

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun saveToDownloads(source: File, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$RELATIVE_DIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun showNotification(fileName: String, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "audio/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notification_received_title))
            .setContentText(fileName)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(fileName.hashCode(), notification)
    }
}
