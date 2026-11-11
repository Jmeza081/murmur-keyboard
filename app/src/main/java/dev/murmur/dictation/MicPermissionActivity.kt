package dev.murmur.dictation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import helium314.keyboard.latin.R

/**
 * A keyboard can't show the microphone permission prompt itself, so the mic key opens this
 * invisible screen, which asks and then closes.
 */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return finish()
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(this, if (granted) R.string.murmur_mic_granted else R.string.murmur_mic_denied, Toast.LENGTH_LONG).show()
        finish()
    }
}
