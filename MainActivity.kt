package com.example.mcpapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var editTextPrompt: EditText
    private lateinit var buttonSend: Button

    private var geminiMcpService: GeminiMcpService? = null
    private var isBound = false
    private var isServiceReady = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as GeminiMcpService.GeminiMcpBinder
            geminiMcpService = binder.getService()
            isBound = true

            // Check if service is already initialized
            isServiceReady = geminiMcpService?.isReady() ?: false
            updateButtonState()

            // Set callback for service communication
            geminiMcpService?.setCallback(object : GeminiMcpService.GeminiMcpCallback {
                override fun onTaskCompleted(result: String) {
                    runOnUiThread {
                        buttonSend.isEnabled = true
                        buttonSend.text = "Submit"
                        Toast.makeText(this@MainActivity, "Task completed: $result", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        buttonSend.isEnabled = isServiceReady
                        buttonSend.text = if (isServiceReady) "Submit" else "Initializing..."
                        Toast.makeText(this@MainActivity, "Error: $error", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onStarted() {
                    runOnUiThread {
                        buttonSend.isEnabled = false
                        buttonSend.text = "Processing..."
                        Toast.makeText(this@MainActivity, "Processing started", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onProgress(message: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onInitialized() {
                    runOnUiThread {
                        isServiceReady = true
                        updateButtonState()
                        Toast.makeText(this@MainActivity, "Service ready! You can now submit tasks.", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        override fun onServiceDisconnected(className: ComponentName) {
            geminiMcpService = null
            isBound = false
            isServiceReady = false
            updateButtonState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editTextPrompt = findViewById(R.id.editTextPrompt)
        buttonSend = findViewById(R.id.buttonSend)

        // Initially disable button until service is ready
        buttonSend.isEnabled = false
        buttonSend.text = "Initializing..."

        buttonSend.setOnClickListener {
            if (isServiceReady) {
                val prompt = editTextPrompt.text.toString().trim()
                if (prompt.isNotEmpty()) {
                    geminiMcpService?.executeTask(prompt)
                } else {
                    Toast.makeText(this, "Please enter a prompt", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Service is still initializing. Please wait...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateButtonState() {
        runOnUiThread {
            if (isServiceReady) {
                buttonSend.isEnabled = true
                buttonSend.text = "Submit"
            } else {
                buttonSend.isEnabled = false
                buttonSend.text = "Initializing..."
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to service
        val intent = Intent(this, GeminiMcpService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            geminiMcpService?.removeCallback()
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
