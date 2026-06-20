package com.movtery.zalithlauncher.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent activity that requests the CAMERA runtime permission on behalf of
 * {@link CaptureBridgeService} (a service can't show the system dialog). Reports the result back to
 * the service via its process-local {@code instance} handle, then finishes. Overlays the fullscreen
 * game without obscuring it (translucent theme).
 */
class CapturePermissionActivity : ComponentActivity() {

    private var permission: String = Manifest.permission.CAMERA

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            CaptureBridgeService.instance?.onPermissionResult(permission, granted)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permission = intent.getStringExtra(EXTRA_PERMISSION) ?: Manifest.permission.CAMERA
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            CaptureBridgeService.instance?.onPermissionResult(permission, true)
            finish()
        } else {
            requestPermission.launch(permission)
        }
    }

    companion object {
        private const val EXTRA_PERMISSION = "permission"

        fun request(context: Context, permission: String) {
            val intent = Intent(context, CapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_PERMISSION, permission)
            }
            context.startActivity(intent)
        }
    }
}
