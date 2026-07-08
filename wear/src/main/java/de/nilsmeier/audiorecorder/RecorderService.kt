package de.nilsmeier.audiorecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val startedAtRealtime: Long) : RecorderState
    data object Transferring : RecorderState
    data class Finished(val sent: Int, val pending: Int) : RecorderState
}

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "de.nilsmeier.audiorecorder.action.START"
        const val ACTION_STOP = "de.nilsmeier.audiorecorder.action.STOP"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
        val state: StateFlow<RecorderState> = _state

        val isRecording: Boolean
            get() = _state.value is RecorderState.Recording

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RecorderService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startForegroundService(
                Intent(context, RecorderService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isRecording) startRecording()
            ACTION_STOP -> if (isRecording) stopAndTransfer()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        // Aufnahme läuft in eine .tmp-Datei außerhalb der Outbox, damit ein paralleler
        // Nachsende-Versuch niemals eine unfertige Datei überträgt.
        val file = File(
            filesDir,
            "aufnahme_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(Date())}.m4a.tmp"
        )

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            mediaRecorder.release()
            file.delete()
            _state.value = RecorderState.Idle
            stopSelf()
            return
        }

        recorder = mediaRecorder
        outputFile = file

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiorecorder:recording")
            .apply { acquire(4 * 60 * 60 * 1000L) }

        val startedAt = SystemClock.elapsedRealtime()
        startForeground(
            NOTIFICATION_ID,
            buildRecordingNotification(startedAt),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        _state.value = RecorderState.Recording(startedAt)
    }

    private fun stopAndTransfer() {
        _state.value = RecorderState.Transferring

        finishRecordingFile()
        releaseWakeLock()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildTransferNotification())

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                TransferManager.flushOutbox(applicationContext)
            }
            _state.value = RecorderState.Finished(sent = result.first, pending = result.second)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildRecordingNotification(startedAtRealtime: Long): android.app.Notification {
        createNotificationChannel()

        val touchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(touchIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)

        val status = Status.Builder()
            .addTemplate("#time#")
            .addPart("time", Status.StopwatchPart(startedAtRealtime))
            .build()

        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_mic)
            .setTouchIntent(touchIntent)
            .setStatus(status)
            .build()
            .apply(applicationContext)

        return builder.build()
    }

    private fun buildTransferNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.transferring))
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Stoppt den Recorder und verschiebt die fertige Datei in die Outbox. */
    private fun finishRecordingFile() {
        val file = outputFile
        try {
            recorder?.stop()
            if (file != null && file.exists()) {
                file.renameTo(File(TransferManager.outboxDir(this), file.name.removeSuffix(".tmp")))
            }
        } catch (e: RuntimeException) {
            // stop() wirft, wenn keine gültigen Audiodaten vorliegen (sofort gestoppt)
            file?.delete()
        }
        recorder?.release()
        recorder = null
        outputFile = null
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        // Falls das System den Service während einer Aufnahme beendet:
        // Aufnahme sauber abschließen und in die Outbox legen, damit nichts verloren geht.
        if (_state.value is RecorderState.Recording) {
            finishRecordingFile()
            _state.value = RecorderState.Idle
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
