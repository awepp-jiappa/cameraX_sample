package com.example.cameraxsample.ui.preview

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.cameraxsample.R
import com.example.cameraxsample.databinding.ActivityPreviewBinding
import com.example.cameraxsample.storage.StorageModule
import com.example.cameraxsample.ui.saved.SavedImageActivity
import java.io.File

class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding

    private var tempFilePath: String? = null
    private var fileName: String = ""
    private var actionHandled: Boolean = false
    private var saved: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tempFilePath = intent.getStringExtra(EXTRA_TEMP_FILE_PATH)
        fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()

        if (tempFilePath.isNullOrBlank()) {
            Log.e(TAG, "PreviewActivity launched without temp source")
            finish()
            return
        }

        setupListeners()
        loadPreviewImage()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            Glide.with(this).clear(binding.ivPreview)
            if (!saved) {
                deleteTempFileIfExists()
            }
        }
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener { onSaveClicked() }
        binding.btnDiscard.setOnClickListener { onDiscardClicked() }
        binding.btnClose.setOnClickListener { onDiscardClicked() }
    }

    private fun loadPreviewImage() {
        val source = File(tempFilePath.orEmpty())
        Glide.with(this)
            .load(source)
            .fitCenter()
            .into(binding.ivPreview)
    }

    private fun onSaveClicked() {
        if (actionHandled) return
        actionHandled = true

        val tempFile = tempFilePath?.let(::File)
        if (tempFile == null || !tempFile.exists()) {
            actionHandled = false
            Toast.makeText(this, R.string.msg_photo_save_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val result = StorageModule.saveCapturedPhoto(this, tempFile, fileName)
        if (result == null) {
            actionHandled = false
            Toast.makeText(this, R.string.msg_photo_save_failed, Toast.LENGTH_SHORT).show()
            return
        }

        saved = true
        StorageModule.deleteTempCapture(tempFile)
        Toast.makeText(this, R.string.msg_save_completed, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Save completed. savedUri=${result.savedUri} savedFilePath=${result.savedFilePath}")

        val intent = Intent(this, SavedImageActivity::class.java).apply {
            putExtra(SavedImageActivity.EXTRA_SAVED_URI, result.savedUri?.toString())
            putExtra(SavedImageActivity.EXTRA_SAVED_FILE_PATH, result.savedFilePath)
        }
        startActivity(intent)
        finish()
    }

    private fun onDiscardClicked() {
        if (actionHandled) return
        actionHandled = true

        val deleted = deleteTempFileIfExists()
        Log.d(TAG, "Discard clicked. deleted=$deleted path=$tempFilePath")
        finish()
    }

    private fun deleteTempFileIfExists(): Boolean {
        val tempFile = tempFilePath?.let(::File)
        return StorageModule.deleteTempCapture(tempFile)
    }

    companion object {
        private const val TAG = "PreviewActivity"

        const val EXTRA_TEMP_FILE_PATH = "extra_temp_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
    }
}
