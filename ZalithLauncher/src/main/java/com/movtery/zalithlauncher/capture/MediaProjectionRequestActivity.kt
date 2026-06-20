package com.movtery.zalithlauncher.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent activity that runs the system MediaProjection (screen-capture) consent dialog on behalf
 * of {@link CaptureBridgeService} (the consent Intent is framework-only and must come from an Activity).
 * Hands the result back to the service, which must promote itself to a {@code mediaProjection} foreground
 * service before calling {@code getMediaProjection}.
 */
class MediaProjectionRequestActivity : ComponentActivity() {

    private val request =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            CaptureBridgeService.instance?.onScreenConsentResult(result.resultCode, result.data)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        request.launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        fun request(context: Context) {
            context.startActivity(
                Intent(context, MediaProjectionRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
