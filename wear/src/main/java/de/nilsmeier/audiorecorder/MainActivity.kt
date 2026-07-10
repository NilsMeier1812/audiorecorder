package de.nilsmeier.audiorecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
 * sich sofort wieder. Einzige Ausnahme sind die einmaligen System-Dialoge für die
 * Mikrofon- und Benachrichtigungs-Berechtigung beim allerersten Start.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Aufnahme braucht zwingend das Mikrofon; die Benachrichtigungs-Berechtigung
            // ist nur für den sichtbaren Timer nötig, blockiert die Aufnahme aber nicht.
            if (hasPermission(Manifest.permission.RECORD_AUDIO) ||
                result[Manifest.permission.RECORD_AUDIO] == true
            ) {
                startRecording()
            } else {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = missingPermissions()
        when {
            RecorderService.isRecording -> {
                RecorderService.stop(this)
                finish()
            }

            missing.isEmpty() -> {
                startRecording()
                finish()
            }

            else -> permissionLauncher.launch(missing)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Benötigte, aber noch nicht erteilte Berechtigungen. Ab Android 13 (Wear OS 4)
     * wird die Foreground-Service-Benachrichtigung – und damit der Timer – ohne
     * POST_NOTIFICATIONS komplett unterdrückt, deshalb muss sie mit angefragt werden.
     */
    private fun missingPermissions(): Array<String> {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        return needed.filterNot { hasPermission(it) }.toTypedArray()
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
