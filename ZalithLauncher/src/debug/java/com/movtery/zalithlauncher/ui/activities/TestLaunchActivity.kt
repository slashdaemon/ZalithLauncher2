package com.movtery.zalithlauncher.ui.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Debug builds only (TBS testkit): an exported entry point adb can start, which hands the launch
 * extras to MainActivity (not exported). Example:
 *
 *   adb shell am start -n as.papp.tbs.launcher.v2.debug/com.movtery.zalithlauncher.ui.activities.TestLaunchActivity \
 *     --es tbs_test_instance SC-Test-26.2 --es tbs_test_server 10.0.2.2:25565
 */
class TestLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, MainActivity::class.java).apply {
            intent.extras?.let { putExtras(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(forward)
        finish()
    }
}
