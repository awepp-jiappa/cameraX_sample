package com.example.cameraxsample.ui.camera

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.cameraxsample.R
import com.example.cameraxsample.databinding.ActivityCameraBinding

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInsets()
        setupListeners()
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

    companion object {
        private const val TAG = "CameraActivity"
    }
}
