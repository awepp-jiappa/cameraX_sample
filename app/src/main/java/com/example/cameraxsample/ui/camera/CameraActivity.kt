package com.example.cameraxsample.ui.camera

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.cameraxsample.R
import com.example.cameraxsample.databinding.ActivityCameraBinding
import com.example.cameraxsample.storage.StorageModule
import java.util.concurrent.ExecutionException

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageCaptureUseCase: ImageCapture? = null

    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF
    private var hasFlashUnit: Boolean = false
    private var isCaptureInProgress: Boolean = false
    private var isCloseRequested: Boolean = false
    private var isDisplayListenerRegistered: Boolean = false
    private var lastControlRotationDegrees: Float = 0f

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            val viewDisplay = binding.viewFinder.display ?: return
            if (displayId != viewDisplay.displayId) return
            updateRotationTargets(viewDisplay.rotation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        flashMode = savedInstanceState?.getInt(KEY_FLASH_MODE) ?: ImageCapture.FLASH_MODE_OFF

        setupInsets()
        setupListeners()
        binding.btnFlash.isEnabled = false
        updateFlashButton()
        setupCameraPreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_FLASH_MODE, flashMode)
    }

    override fun onDestroy() {
        unregisterDisplayListener()
        releaseCameraResourcesSafely()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        registerDisplayListener()
        updateRotationTargets(getCurrentDisplayRotation())
    }

    override fun onPause() {
        unregisterDisplayListener()
        super.onPause()
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
            if (!hasFlashUnit) return@setOnClickListener
            flashMode = nextFlashMode(flashMode)
            imageCaptureUseCase?.flashMode = flashMode
            Log.d(TAG, "Flash mode changed: ${flashModeToText(flashMode)}")
            updateFlashButton()
        }

        binding.btnShutter.setOnClickListener {
            capturePhoto()
        }

        binding.btnClose.setOnClickListener {
            onCloseClicked()
        }
    }

    private fun setupCameraPreview() {
        binding.viewFinder.post {
            startCameraPreview()
        }
    }

    private fun registerDisplayListener() {
        if (isDisplayListenerRegistered) return
        displayManager.registerDisplayListener(displayListener, null)
        isDisplayListenerRegistered = true
    }

    private fun unregisterDisplayListener() {
        if (!isDisplayListenerRegistered) return
        displayManager.unregisterDisplayListener(displayListener)
        isDisplayListenerRegistered = false
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    bindCameraUseCases(provider)
                } catch (securityException: SecurityException) {
                    Log.e(TAG, "Camera permission missing while launching preview", securityException)
                    Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_SHORT).show()
                    finish()
                } catch (executionException: ExecutionException) {
                    Log.e(TAG, "Failed to initialize camera provider", executionException)
                    Toast.makeText(this, R.string.msg_camera_init_failed, Toast.LENGTH_SHORT).show()
                    finish()
                } catch (interruptedException: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.e(TAG, "Camera provider initialization interrupted", interruptedException)
                    Toast.makeText(this, R.string.msg_camera_init_failed, Toast.LENGTH_SHORT).show()
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        val viewDisplay = binding.viewFinder.display ?: return

        Log.d(TAG, "Binding camera use cases")

        val preview = Preview.Builder()
            .setTargetRotation(viewDisplay.rotation)
            .build()
            .also { it.surfaceProvider = binding.viewFinder.surfaceProvider }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(viewDisplay.rotation)
            .setFlashMode(flashMode)
            .build()

        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
            onCameraBound(camera)

            previewUseCase = preview
            imageCaptureUseCase = imageCapture
            updateRotationTargets(viewDisplay.rotation)
            updateFlashButton()
            Log.d(TAG, "Camera use cases bound successfully")
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to bind camera use cases", exception)
            Toast.makeText(this, R.string.msg_camera_init_failed, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun onCameraBound(camera: Camera) {
        hasFlashUnit = camera.cameraInfo.hasFlashUnit()
        Log.d(TAG, "Camera flash support: $hasFlashUnit")

        binding.btnFlash.isEnabled = hasFlashUnit
        if (!hasFlashUnit) {
            flashMode = ImageCapture.FLASH_MODE_OFF
            Toast.makeText(this, R.string.msg_flash_not_supported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFlashButton() {
        val iconRes = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        }
        binding.btnFlash.setIconResource(iconRes)
    }

    private fun onCloseClicked() {
        if (isCloseRequested) return

        isCloseRequested = true
        binding.btnClose.isEnabled = false
        Log.d(TAG, "Close button tapped. inProgress=$isCaptureInProgress")

        if (isCaptureInProgress) {
            return
        }

        closeAndFinish()
    }

    private fun closeAndFinish() {
        releaseCameraResourcesSafely()
        finish()
    }

    private fun releaseCameraResourcesSafely() {
        try {
            cameraProvider?.unbindAll()
            Log.d(TAG, "Camera resources unbound")
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to unbind camera resources", exception)
        }
    }

    private fun capturePhoto() {
        val imageCapture = imageCaptureUseCase ?: return
        val saveRequest = StorageModule.createCaptureSaveRequest(this)

        isCaptureInProgress = true
        binding.btnShutter.isEnabled = false

        imageCapture.takePicture(
            saveRequest.outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onCaptureFinished()
                    if (isDestroyed || isFinishing) return

                    val savedUri = outputFileResults.savedUri ?: saveRequest.legacyUri

                    if (saveRequest.legacyFile != null) {
                        StorageModule.scanLegacyFile(this@CameraActivity, saveRequest.legacyFile)
                    }

                    val locationMessage = savedUri?.toString() ?: saveRequest.userVisiblePath
                    Toast.makeText(
                        this@CameraActivity,
                        getString(R.string.msg_photo_saved, locationMessage),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    onCaptureFinished()
                    if (isDestroyed || isFinishing) return

                    Log.e(TAG, "Photo capture failed", exception)
                    Toast.makeText(
                        this@CameraActivity,
                        R.string.msg_photo_save_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun onCaptureFinished() {
        isCaptureInProgress = false
        if (!isDestroyed) {
            binding.btnShutter.isEnabled = true
        }
        if (isCloseRequested) {
            closeAndFinish()
        }
    }

    private fun nextFlashMode(currentMode: Int): Int {
        return when (currentMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    private fun flashModeToText(mode: Int): String {
        return when (mode) {
            ImageCapture.FLASH_MODE_OFF -> "OFF"
            ImageCapture.FLASH_MODE_ON -> "ON"
            ImageCapture.FLASH_MODE_AUTO -> "AUTO"
            else -> "UNKNOWN"
        }
    }

    private fun updateRotationTargets(displayRotation: Int) {
        previewUseCase?.targetRotation = displayRotation
        imageCaptureUseCase?.targetRotation = displayRotation

        val targetDegrees = mapDisplayRotationToDegrees(displayRotation)
        if (targetDegrees == lastControlRotationDegrees) return

        lastControlRotationDegrees = targetDegrees
        binding.btnFlash.animate().rotation(targetDegrees).setDuration(UI_ROTATION_ANIM_DURATION_MS).start()
        binding.btnClose.animate().rotation(targetDegrees).setDuration(UI_ROTATION_ANIM_DURATION_MS).start()
        binding.btnShutter.animate().rotation(targetDegrees).setDuration(UI_ROTATION_ANIM_DURATION_MS).start()
    }

    private fun getCurrentDisplayRotation(): Int {
        return binding.viewFinder.display?.rotation
            ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0
    }

    private fun mapDisplayRotationToDegrees(displayRotation: Int): Float {
        return when (displayRotation) {
            Surface.ROTATION_0 -> 0f
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 270f
            else -> 0f
        }
    }

    companion object {
        private const val TAG = "CameraActivity"
        private const val KEY_FLASH_MODE = "key_flash_mode"
        private const val UI_ROTATION_ANIM_DURATION_MS = 200L
    }
}
