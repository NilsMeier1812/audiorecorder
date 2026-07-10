package de.nilsmeier.audiorecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Unsichtbare Aktivität: Wird per Hometasten-Doppelklick (Samsung-Tastenbelegung)
 * gestartet und togglet nur die Aufnahme – sie zeigt selbst kein UI und schließt
 * sich sofort wieder. Einzige Ausnahme ist der einmalige System-Dialog für die
 * Mikrofon-Berechtigung beim allerersten Start.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            RecorderService.isRecording -> {
                RecorderService.stop(this)
                finish()
            }

            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> {
                startRecording()
                finish()
            }

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
