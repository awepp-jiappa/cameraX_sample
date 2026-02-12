package com.example.cameraxsample.ui.camera

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.cameraxsample.R
import com.example.cameraxsample.databinding.ActivityCameraBinding
import java.util.concurrent.ExecutionException

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val viewDisplay = binding.viewFinder.display ?: return
            if (displayId != viewDisplay.displayId) return
            previewUseCase?.targetRotation = viewDisplay.rotation
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInsets()
        setupListeners()
        setupCameraPreview()
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupListeners() {
        binding.btnFlash.setOnClickListener {
            Log.d(TAG, "Flash button tapped (placeholder)")
            Toast.makeText(this, R.string.msg_flash_placeholder, Toast.LENGTH_SHORT).show()
        }

        binding.btnShutter.setOnClickListener {
            Log.d(TAG, "Shutter button tapped (placeholder)")
            Toast.makeText(this, R.string.msg_shutter_placeholder, Toast.LENGTH_SHORT).show()
        }

        binding.btnClose.setOnClickListener {
            finish()
        }
    }

    private fun setupCameraPreview() {
        displayManager.registerDisplayListener(displayListener, null)

        binding.viewFinder.post {
            startCameraPreview()
        }
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    bindPreviewUseCase(provider)
                } catch (securityException: SecurityException) {
                    Log.e(TAG, "Camera permission missing while launching preview", securityException)
                    Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_SHORT).show()
                    finish()
                } catch (executionException: ExecutionException) {
                    Log.e(TAG, "Failed to initialize camera provider", executionException)
                    Toast.makeText(this, R.string.msg_camera_init_failed, Toast.LENGTH_SHORT).show()
                } catch (interruptedException: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.e(TAG, "Camera provider initialization interrupted", interruptedException)
                    Toast.makeText(this, R.string.msg_camera_init_failed, Toast.LENGTH_SHORT).show()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindPreviewUseCase(provider: ProcessCameraProvider) {
        val viewDisplay = binding.viewFinder.display ?: return
        val preview = Preview.Builder()
            .setTargetRotation(viewDisplay.rotation)
            .build()
            .also { it.surfaceProvider = binding.viewFinder.surfaceProvider }

        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        previewUseCase = preview
    }

    companion object {
        private const val TAG = "CameraActivity"
    }
}
