package de.nilsmeier.audiorecorder

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Überträgt aufgenommene Dateien per Wearable Data Layer (ChannelClient) ans Handy.
 * Nicht übertragbare Dateien bleiben im Outbox-Ordner und werden beim nächsten
 * Stopp bzw. App-Start erneut versucht.
 */
object TransferManager {

    const val PATH_PREFIX = "/audiorecorder"
    private const val PER_FILE_TIMEOUT_MS = 10 * 60 * 1000L

    // Verhindert, dass Nachsende-Versuch (App-Start) und Stopp-Übertragung
    // dieselbe Datei gleichzeitig senden.
    private val flushMutex = Mutex()

    fun outboxDir(context: Context): File =
        File(context.filesDir, "outbox").apply { mkdirs() }

    fun pendingFiles(context: Context): List<File> =
        outboxDir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(".m4a") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Sendet alle ausstehenden Dateien (älteste zuerst).
     * @return Paar aus (erfolgreich gesendet, noch ausstehend)
     */
    suspend fun flushOutbox(context: Context): Pair<Int, Int> = flushMutex.withLock {
        val files = pendingFiles(context)
        var sent = 0
        for (file in files) {
            if (sendFile(context, file)) {
                file.delete()
                sent++
            } else {
                break
            }
        }
        sent to (files.size - sent)
    }

    private suspend fun sendFile(context: Context, file: File): Boolean {
        return try {
            withTimeout(PER_FILE_TIMEOUT_MS) {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                if (node == null) {
                    false
                } else {
                    val channelClient = Wearable.getChannelClient(context)
                    val channel =
                        channelClient.openChannel(node.id, "$PATH_PREFIX/${file.name}").await()
                    try {
                        channelClient.sendFile(channel, Uri.fromFile(file)).await()
                    } finally {
                        channelClient.close(channel).await()
                    }
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
