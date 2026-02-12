package com.example.cameraxsample.ui.gate

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.cameraxsample.databinding.ActivityGateBinding
import com.example.cameraxsample.ui.camera.CameraActivity

class GateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGateBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnTakePhoto.setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }
    }
}
