package com.example.cameraxsample.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.display.DisplayManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.cameraxsample.R
import com.example.cameraxsample.databinding.ActivityCameraBinding
import com.example.cameraxsample.storage.StorageModule
import com.example.cameraxsample.ui.preview.PreviewActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var previewUseCase: Preview? = null
    private var imageCaptureUseCase: ImageCapture? = null

    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF
    private var hasFlashUnit: Boolean = false
    private var isCaptureInProgress: Boolean = false
    private var isCloseRequested: Boolean = false
    private var isDisplayListenerRegistered: Boolean = false
    private var lastControlRotationDegrees: Float = 0f
    private var exposureIndex: Int = 0
    private var isPinchGestureInProgress: Boolean = false

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private val captureExecutor = Executors.newSingleThreadExecutor()

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

        StorageModule.cleanupTemporaryCaptures(this)

        flashMode = savedInstanceState?.getInt(KEY_FLASH_MODE) ?: ImageCapture.FLASH_MODE_OFF
        exposureIndex = savedInstanceState?.getInt(KEY_EXPOSURE_INDEX) ?: 0

        setupInsets()
        setupGestureDetectors()
        setupListeners()
        binding.btnFlash.isEnabled = false
        updateFlashButton()
        updateExposureUi()
        setupCameraPreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_FLASH_MODE, flashMode)
        outState.putInt(KEY_EXPOSURE_INDEX, exposureIndex)
    }

    override fun onDestroy() {
        unregisterDisplayListener()
        releaseCameraResourcesSafely()
        captureExecutor.shutdown()
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

    private fun setupGestureDetectors() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinchGestureInProgress = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyPinchZoom(detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isPinchGestureInProgress = false
            }
        })
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

        binding.btnExposureUp.setOnClickListener {
            adjustExposureBy(1)
        }

        binding.btnExposureDown.setOnClickListener {
            adjustExposureBy(-1)
        }

        binding.viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    if (!isPinchGestureInProgress && !scaleGestureDetector.isInProgress) {
                        startFocusAndMeteringAt(event.x, event.y)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    isPinchGestureInProgress = false
                }
            }
            true
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

            activeCamera = camera
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

        val exposureState = camera.cameraInfo.exposureState
        val exposureRange = exposureState.exposureCompensationRange
        val isExposureSupported = exposureRange.lower != 0 || exposureRange.upper != 0
        binding.exposureControls.visibility = if (isExposureSupported) View.VISIBLE else View.GONE

        if (!isExposureSupported) {
            Log.d(TAG, "Exposure compensation is not supported on this device")
            exposureIndex = 0
            return
        }

        exposureIndex = exposureIndex.coerceIn(exposureRange.lower, exposureRange.upper)
        updateExposureUi()
        setExposureIndex(exposureIndex)
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
            activeCamera = null
            Log.d(TAG, "Camera resources unbound")
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to unbind camera resources", exception)
        }
    }

    private fun capturePhoto() {
        val imageCapture = imageCaptureUseCase ?: return

        isCaptureInProgress = true
        binding.btnShutter.isEnabled = false

        imageCapture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val tempFile = processCapturedImage(image)
                    runOnUiThread {
                        onCaptureFinished()
                        if (isDestroyed || isFinishing) return@runOnUiThread

                        if (tempFile == null) {
                            Toast.makeText(
                                this@CameraActivity,
                                R.string.msg_photo_save_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                            return@runOnUiThread
                        }

                        launchPreview(tempFile, StorageModule.createPhotoFileName())
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        onCaptureFinished()
                        if (isDestroyed || isFinishing) return@runOnUiThread

                        Log.e(TAG, "Photo capture failed", exception)
                        Toast.makeText(
                            this@CameraActivity,
                            R.string.msg_photo_save_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun processCapturedImage(image: ImageProxy): File? {
        return try {
            val bitmap = imageProxyToBitmap(image) ?: return null
            val fileName = "TMP_${System.currentTimeMillis()}.jpg"
            val tempFile = StorageModule.createTemporaryCaptureFile(this, fileName)
            FileOutputStream(tempFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            }
            tempFile
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to process captured image", exception)
            null
        } finally {
            image.close()
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)
        val imageBytes = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null

        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var outputOffset = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            for (col in 0 until width) {
                nv21[outputOffset++] = yBuffer[rowStart + col * yPixelStride]
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        for (row in 0 until chromaHeight) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaWidth) {
                nv21[outputOffset++] = vBuffer[vRowStart + col * vPixelStride]
                nv21[outputOffset++] = uBuffer[uRowStart + col * uPixelStride]
            }
        }

        return nv21
    }

    private fun launchPreview(tempFile: File, fileName: String) {
        val intent = Intent(this, PreviewActivity::class.java).apply {
            putExtra(PreviewActivity.EXTRA_FILE_NAME, fileName)
            putExtra(PreviewActivity.EXTRA_TEMP_FILE_PATH, tempFile.absolutePath)
        }
        startActivity(intent)
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

    private fun applyPinchZoom(scaleFactor: Float) {
        val camera = activeCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value ?: return

        val currentZoomRatio = zoomState.zoomRatio
        val desiredRatio = (currentZoomRatio * scaleFactor)
            .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)

        camera.cameraControl.setZoomRatio(desiredRatio)
        Log.d(TAG, "Zoom ratio changed: current=$currentZoomRatio desired=$desiredRatio")
    }

    private fun startFocusAndMeteringAt(x: Float, y: Float) {
        val camera = activeCamera ?: run {
            Log.d(TAG, "Ignoring focus tap: camera is not ready")
            return
        }

        showFocusRing(x, y)

        val meteringPoint = binding.viewFinder.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(meteringPoint)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        val future = camera.cameraControl.startFocusAndMetering(action)
        future.addListener(
            {
                try {
                    val result = future.get()
                    Log.d(TAG, "Focus and metering finished. Success=${result.isFocusSuccessful}")
                } catch (exception: Exception) {
                    Log.e(TAG, "Focus and metering failed", exception)
                    Toast.makeText(this, R.string.msg_focus_metering_failed, Toast.LENGTH_SHORT).show()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun showFocusRing(x: Float, y: Float) {
        binding.focusRing.apply {
            visibility = View.VISIBLE
            alpha = 1f
            val clampedX = min(max(0f, x - width / 2f), binding.viewFinder.width - width.toFloat())
            val clampedY = min(max(0f, y - height / 2f), binding.viewFinder.height - height.toFloat())
            this.x = clampedX
            this.y = clampedY
            animate()
                .alpha(0f)
                .setDuration(600L)
                .withEndAction { visibility = View.GONE }
                .start()
        }
    }

    private fun adjustExposureBy(delta: Int) {
        val camera = activeCamera ?: return
        val exposureRange = camera.cameraInfo.exposureState.exposureCompensationRange
        if (exposureRange.lower == 0 && exposureRange.upper == 0) {
            Toast.makeText(this, R.string.msg_exposure_not_supported, Toast.LENGTH_SHORT).show()
            return
        }

        val targetIndex = (exposureIndex + delta).coerceIn(exposureRange.lower, exposureRange.upper)
        if (targetIndex == exposureIndex) return
        setExposureIndex(targetIndex)
    }

    private fun setExposureIndex(targetIndex: Int) {
        val camera = activeCamera ?: return
        val exposureRange = camera.cameraInfo.exposureState.exposureCompensationRange
        val clampedIndex = targetIndex.coerceIn(exposureRange.lower, exposureRange.upper)
        camera.cameraControl.setExposureCompensationIndex(clampedIndex)
        exposureIndex = clampedIndex
        updateExposureUi()
        Log.d(TAG, "Exposure compensation index changed: $exposureIndex")
    }

    private fun updateExposureUi() {
        binding.tvExposureValue.text = exposureIndex.toString()
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
        binding.tvRotation.text = "${targetDegrees.toInt()}°"
        if (targetDegrees == lastControlRotationDegrees) return

        lastControlRotationDegrees = targetDegrees
        binding.btnFlash.animate().rotation(targetDegrees).setDuration(UI_ROTATION_ANIM_DURATION_MS).start()
        binding.btnClose.animate().rotation(targetDegrees).setDuration(UI_ROTATION_ANIM_DURATION_MS).start()
        binding.btnShutter.animate().rotation(targetDegrees).setDuration(UI_ROTATION_ANIM_DURATION_MS).start()
        binding.btnExposureUp.animate().rotation(targetDegrees).setDuration(UI_ROTATION_ANIM_DURATION_MS).start()
        binding.btnExposureDown.animate().rotation(targetDegrees).setDuration(UI_ROTATION_ANIM_DURATION_MS).start()
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
        private const val KEY_EXPOSURE_INDEX = "key_exposure_index"
        private const val UI_ROTATION_ANIM_DURATION_MS = 200L
    }
}
