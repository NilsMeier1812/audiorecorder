package de.nilsmeier.audiorecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var permissionDenied by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionDenied = false
                startRecording()
            } else {
                permissionDenied = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toggle()
        setContent {
            WearApp(
                permissionDenied = permissionDenied,
                onStop = { RecorderService.stop(this) },
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onDone = { finish() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Zweiter Doppelklick auf die Hometaste landet hier: Aufnahme togglen.
        toggle()
    }

    /** Kernlogik: Doppelklick startet die Aufnahme bzw. stoppt eine laufende. */
    private fun toggle() {
        when {
            RecorderService.isRecording -> RecorderService.stop(this)

            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startRecording()

            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        RecorderService.start(this)
        // Liegengebliebene Aufnahmen (Handy war nicht erreichbar) parallel nachsenden.
        if (TransferManager.pendingFiles(this).isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                TransferManager.flushOutbox(applicationContext)
            }
        }
    }
}

@Composable
fun WearApp(
    permissionDenied: Boolean,
    onStop: () -> Unit,
    onRequestPermission: () -> Unit,
    onDone: () -> Unit
) {
    MaterialTheme {
        val state by RecorderService.state.collectAsState()

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                permissionDenied -> PermissionScreen(onRequestPermission)
                else -> when (val s = state) {
                    is RecorderState.Recording -> RecordingScreen(s.startedAtRealtime, onStop)
                    is RecorderState.Transferring -> TransferringScreen()
                    is RecorderState.Finished -> FinishedScreen(s, onDone)
                    is RecorderState.Idle -> {}
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequestPermission: () -> Unit) {
    Text(
        text = stringResource(R.string.permission_needed),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    Button(
        onClick = onRequestPermission,
        modifier = Modifier.padding(top = 12.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = stringResource(R.string.permission_needed)
        )
    }
}

@Composable
private fun RecordingScreen(startedAtRealtime: Long, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtRealtime) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    Text(
        text = stringResource(R.string.recording),
        color = MaterialTheme.colors.onBackground
    )
    Text(
        text = formatElapsed(now - startedAtRealtime),
        fontSize = 32.sp,
        color = Color(0xFFFF5252),
        modifier = Modifier.padding(vertical = 8.dp)
    )
    Button(
        onClick = onStop,
        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB3261E)),
        modifier = Modifier.size(52.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_stop),
            contentDescription = stringResource(R.string.stop)
        )
    }
}

@Composable
private fun TransferringScreen() {
    CircularProgressIndicator()
    Text(
        text = stringResource(R.string.transferring),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp)
    )
}

@Composable
private fun FinishedScreen(state: RecorderState.Finished, onDone: () -> Unit) {
    val success = state.pending == 0
    LaunchedEffect(state) {
        delay(if (success) 2000 else 4000)
        onDone()
    }
    Text(
        text = stringResource(if (success) R.string.sent else R.string.queued),
        textAlign = TextAlign.Center,
        color = if (success) Color(0xFF4CAF50) else Color(0xFFFFB74D),
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

private fun formatElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.GERMANY, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.GERMANY, "%02d:%02d", minutes, seconds)
    }
}
