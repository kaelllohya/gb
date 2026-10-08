package com.example.gamebooster

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Activity transparan: minta izin capture layar, lalu start FrameGenService. */
class ProjectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 77)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, FrameGenService::class.java)
                .setAction(FrameGenService.ACTION_START)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
        }
        finish()
    }
}
