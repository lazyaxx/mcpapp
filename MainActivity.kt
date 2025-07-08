package com.example.mcpapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var editTextPrompt: EditText
    private lateinit var buttonSend: Button
    private lateinit var textViewStatus: TextView
    private lateinit var textViewResponse: TextView

    private var geminiMcpService: GeminiMcpService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as GeminiMcpService.GeminiMcpBinder
            geminiMcpService = binder.getService()
            bound = true

            // Set callback for service responses
            geminiMcpService?.setCallback(object : GeminiMcpService.GeminiMcpCallback {
                override fun onStatusUpdate(status: String) {
                    runOnUiThread {
                        textViewStatus.text = status
                    }
                }

                override fun onResponse(response: String) {
                    runOnUiThread {
                        textViewResponse.text = response
                        buttonSend.isEnabled = true
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        textViewResponse.text = "Error: $error"
                        textViewStatus.text = "Ready"
                        buttonSend.isEnabled = true
                    }
                }

                override fun onCompleted() {
                    runOnUiThread {
                        textViewStatus.text = "Task completed successfully"
                        buttonSend.isEnabled = true
                    }
                }
            })
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            bound = false
            geminiMcpService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editTextPrompt = findViewById(R.id.editTextPrompt)
        buttonSend = findViewById(R.id.buttonSend)
        textViewStatus = findViewById(R.id.textViewStatus)
        textViewResponse = findViewById(R.id.textViewResponse)

        buttonSend.setOnClickListener {
            val prompt = editTextPrompt.text.toString().trim()
            if (prompt.isNotEmpty()) {
                buttonSend.isEnabled = false
                textViewResponse.text = ""
                geminiMcpService?.processUserQuery(prompt)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, GeminiMcpService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            geminiMcpService?.removeCallback()
            unbindService(connection)
            bound = false
        }
    }
}
