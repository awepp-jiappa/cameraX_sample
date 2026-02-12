package com.example.cameraxsample.ui.gate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.cameraxsample.databinding.ActivityGateBinding
import com.example.cameraxsample.R
import com.example.cameraxsample.ui.camera.CameraActivity

class GateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGateBinding
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                navigateToCamera()
            } else {
                handlePermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnTakePhoto.setOnClickListener {
            if (hasCameraPermission()) {
                navigateToCamera()
            } else {
                requestCameraPermission()
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun handlePermissionDenied() {
        Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_SHORT).show()

        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            showPermissionRationaleDialog()
        } else {
            showPermissionSettingsDialog()
        }
    }

    private fun showPermissionRationaleDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camera_permission_title)
            .setMessage(R.string.camera_permission_rationale)
            .setPositiveButton(R.string.retry_permission_request) { _, _ ->
                requestCameraPermission()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camera_permission_title)
            .setMessage(R.string.camera_permission_permanently_denied)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun navigateToCamera() {
        startActivity(Intent(this, CameraActivity::class.java))
    }
}
