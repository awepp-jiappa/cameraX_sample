package com.example.cameraxsample.ui.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.example.cameraxsample.R
import com.example.cameraxsample.databinding.ActivityLargeScreenCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LargeScreenCameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLargeScreenCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var pendingSavedUri: Uri? = null
    private var pendingSavedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLargeScreenCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!hasCameraPermission() || (requiresLegacyWritePermission() && !hasLegacyWritePermission())) {
            Toast.makeText(this, R.string.required_permissions_needed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupUi()
        startCamera()
    }

    private fun setupUi() {
        binding.btnClose.setOnClickListener { finish() }
        binding.btnShutter.setOnClickListener { capturePhoto() }
        binding.btnSave.setOnClickListener { onSavePreview() }
        binding.btnDiscard.setOnClickListener { onDiscardPreview() }
        binding.viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            imageCapture?.targetRotation = binding.viewFinder.display?.rotation ?: Surface.ROTATION_0
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.viewFinder.surfaceProvider }
            imageCapture = ImageCapture.Builder()
                .setTargetRotation(binding.viewFinder.display?.rotation ?: Surface.ROTATION_0)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.msg_camera_init_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        binding.btnShutter.isEnabled = false

        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    binding.btnShutter.isEnabled = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        pendingSavedUri = outputFileResults.savedUri
                        pendingSavedFile = null
                    }
                    showPreviewMode()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(this@LargeScreenCameraActivity, R.string.msg_photo_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val photoName = createPhotoFileName()
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, photoName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/codex_app/cameraX")
            }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues,
            ).build()
            capture.takePicture(outputOptions, cameraExecutor, callback)
        } else {
            val destination = createLegacyOutputFile()
            pendingSavedFile = destination
            pendingSavedUri = null
            val outputOptions = ImageCapture.OutputFileOptions.Builder(destination).build()
            capture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    MediaScannerConnection.scanFile(
                        this@LargeScreenCameraActivity,
                        arrayOf(destination.absolutePath),
                        arrayOf("image/jpeg"),
                        null,
                    )
                    callback.onImageSaved(outputFileResults)
                }

                override fun onError(exception: ImageCaptureException) {
                    callback.onError(exception)
                }
            })
        }
    }

    private fun showPreviewMode() {
        val previewSource: Any = pendingSavedUri ?: pendingSavedFile ?: run {
            Toast.makeText(this, R.string.msg_photo_save_failed, Toast.LENGTH_SHORT).show()
            return
        }

        Glide.with(this)
            .load(previewSource)
            .fitCenter()
            .into(binding.ivPreview)

        binding.ivPreview.visibility = android.view.View.VISIBLE
        binding.previewActions.visibility = android.view.View.VISIBLE
        binding.btnShutter.visibility = android.view.View.GONE
    }

    private fun onSavePreview() {
        Toast.makeText(this, R.string.msg_save_completed, Toast.LENGTH_SHORT).show()
        clearPreviewMode()
    }

    private fun onDiscardPreview() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pendingSavedUri?.let { contentResolver.delete(it, null, null) }
        } else {
            pendingSavedFile?.takeIf { it.exists() }?.delete()
        }
        Toast.makeText(this, R.string.msg_delete_completed, Toast.LENGTH_SHORT).show()
        clearPreviewMode()
    }

    private fun clearPreviewMode() {
        Glide.with(this).clear(binding.ivPreview)
        binding.ivPreview.setImageDrawable(null)
        binding.ivPreview.visibility = android.view.View.GONE
        binding.previewActions.visibility = android.view.View.GONE
        binding.btnShutter.visibility = android.view.View.VISIBLE

        pendingSavedUri = null
        pendingSavedFile = null
    }

    private fun createLegacyOutputFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDirectory = File(downloadsDir, "codex_app/cameraX")
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }
        return File(targetDirectory, createPhotoFileName())
    }

    private fun createPhotoFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "IMG_${formatter.format(Date())}.jpg"
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLegacyWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiresLegacyWritePermission(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    override fun onDestroy() {
        imageCapture = null
        cameraProvider?.unbindAll()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        super.onDestroy()
    }
}
