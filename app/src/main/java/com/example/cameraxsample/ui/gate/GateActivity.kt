package com.example.cameraxsample.ui.gate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.cameraxsample.R
import com.example.cameraxsample.databinding.ActivityGateBinding
import com.example.cameraxsample.ui.camera.CameraActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class GateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGateBinding

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = requiredPermissions().all { permission -> result[permission] == true }
            if (allGranted) {
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
            if (hasRequiredPermissions()) {
                navigateToCamera()
            } else {
                requestRequiredPermissions()
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        requestPermissionsLauncher.launch(requiredPermissions())
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
    }

    private fun handlePermissionDenied() {
        Toast.makeText(this, R.string.required_permissions_needed, Toast.LENGTH_SHORT).show()

        val showRationale = requiredPermissions().any { permission ->
            shouldShowRequestPermissionRationale(permission)
        }

        if (showRationale) {
            showPermissionRationaleDialog()
        } else {
            showPermissionSettingsDialog()
        }
    }

    private fun showPermissionRationaleDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.required_permissions_title)
            .setMessage(R.string.required_permissions_rationale)
            .setPositiveButton(R.string.retry_permission_request) { _, _ ->
                requestRequiredPermissions()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.required_permissions_title)
            .setMessage(R.string.required_permissions_permanently_denied)
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
