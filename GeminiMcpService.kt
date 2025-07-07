package com.example.mcpapp

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiMcpService : Service() {

    private val binder = GeminiMcpBinder()
    private lateinit var generativeModel: GenerativeModel
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mcpBaseUrl = "http://10.0.2.2:8000/mcp"

    // Track initialization status
    private var isInitialized = false
    private var toolsList = ""

    // Interface for communication with activity
    interface GeminiMcpCallback {
        fun onTaskCompleted(result: String)
        fun onError(error: String)
        fun onStarted()
        fun onProgress(message: String)
        fun onInitialized()
    }

    private var callback: GeminiMcpCallback? = null

    inner class GeminiMcpBinder : Binder() {
        fun getService(): GeminiMcpService = this@GeminiMcpService
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize Gemini model
        generativeModel = GenerativeModel(
            modelName = "gemini-pro",
            apiKey = "AIzaSyAL-JzJ7kmh7MePAHg2r_0s2DCgftfe5iA"
        )
    }

    override fun onBind(intent: Intent): IBinder {
        // Start initialization when service is bound
        if (!isInitialized) {
            initializeService()
        }
        return binder
    }

    // Method to set callback for communication
    fun setCallback(callback: GeminiMcpCallback) {
        this.callback = callback
        // If already initialized, notify immediately
        if (isInitialized) {
            callback.onInitialized()
        }
    }

    // Method to remove callback
    fun removeCallback() {
        this.callback = null
    }

    // Initialize the service with MCP server
    private fun initializeService() {
        serviceScope.launch {
            try {
                callback?.onProgress("Initializing MCP connection...")

                // Step 1: Get tools list from MCP server
                callback?.onProgress("Fetching available tools...")
                toolsList = getToolsList()
                if (toolsList.isEmpty()) {
                    callback?.onError("Failed to get tools list from MCP server")
                    return@launch
                }

                // Step 2: List available devices
                callback?.onProgress("Discovering available devices...")
                val devicesResponse = listAvailableDevices()
                if (devicesResponse.lowercase().contains("error")) {
                    callback?.onError("Failed to list available devices: $devicesResponse")
                    return@launch
                }

                // Step 3: Connect to device using mobile_use_device
                callback?.onProgress("Connecting to device...")
                val connectionResponse = connectToDevice()
                if (connectionResponse.lowercase().contains("error")) {
                    callback?.onError("Failed to connect to device: $connectionResponse")
                    return@launch
                }

                isInitialized = true
                callback?.onProgress("Initialization completed successfully!")
                callback?.onInitialized()

            } catch (e: Exception) {
                Log.e("GeminiMcpService", "Error during initialization", e)
                callback?.onError("Initialization failed: ${e.message}")
            }
        }
    }

    // Get tools list from MCP server
    private suspend fun getToolsList(): String {
        return try {
            val json = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/list")
                put("params", JSONObject())
            }

            val response = callMcpServerSync(json)
            Log.d("GeminiMcpService", "Tools list response: $response")
            response
        } catch (e: Exception) {
            Log.e("GeminiMcpService", "Error getting tools list", e)
            ""
        }
    }

    // List available devices
    private suspend fun listAvailableDevices(): String {
        return try {
            val json = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", "list_available_devices")
                    put("arguments", JSONObject())
                })
            }

            val response = callMcpServerSync(json)
            Log.d("GeminiMcpService", "List devices response: $response")
            response
        } catch (e: Exception) {
            Log.e("GeminiMcpService", "Error listing devices", e)
            "Error: ${e.message}"
        }
    }

    // Connect to device using mobile_use_device
    private suspend fun connectToDevice(): String {
        return try {
            val json = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 3)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", "mobile_use_device")
                    put("arguments", JSONObject().apply {
                        put("device", "emulator-5554") // Default emulator device
                        put("deviceType", "android")
                    })
                })
            }

            val response = callMcpServerSync(json)
            Log.d("GeminiMcpService", "Device connection response: $response")
            response
        } catch (e: Exception) {
            Log.e("GeminiMcpService", "Error connecting to device", e)
            "Error: ${e.message}"
        }
    }

    // Main method to execute the complete task
    fun executeTask(userPrompt: String) {
        if (userPrompt.isBlank()) {
            callback?.onError("Prompt cannot be empty")
            return
        }

        // Check if service is initialized
        if (!isInitialized) {
            callback?.onError("Service not initialized. Please wait for initialization to complete.")
            return
        }

        callback?.onStarted()

        serviceScope.launch {
            try {
                // Step 1: Get task breakdown from Gemini (toolsList is already available)
                callback?.onProgress("Analyzing task and creating breakdown...")
                val tasksList = getTaskBreakdown(toolsList, userPrompt)
                if (tasksList.isEmpty()) {
                    callback?.onError("Failed to get task breakdown from Gemini")
                    return@launch
                }

                // Step 2: Execute tasks sequentially
                callback?.onProgress("Executing tasks...")
                var prevMcpResponse = ""

                for (i in tasksList.indices) {
                    val task = tasksList[i]
                    callback?.onProgress("Executing task ${i + 1}/${tasksList.size}: $task")

                    // Get JSON command from Gemini
                    val jsonCommand = getJsonCommand(toolsList, task, prevMcpResponse)
                    if (jsonCommand.isEmpty()) {
                        callback?.onError("Failed to get JSON command for task: $task")
                        return@launch
                    }

                    // Execute command on MCP server
                    val mcpResponse = executeMcpCommand(jsonCommand)
                    prevMcpResponse = mcpResponse

                    // Check for errors
                    if (mcpResponse.lowercase().contains("error") || mcpResponse.lowercase().contains("failed")) {
                        callback?.onError("Task failed: $mcpResponse")
                        return@launch
                    }
                }

                callback?.onTaskCompleted("All tasks completed successfully. Final response: $prevMcpResponse")

            } catch (e: Exception) {
                Log.e("GeminiMcpService", "Error executing task", e)
                callback?.onError("Error: ${e.message}")
            }
        }
    }

    // Get task breakdown from Gemini
    private suspend fun getTaskBreakdown(toolsList: String, userPrompt: String): List<String> {
        return try {
            val prompt = """
            Available tools: $toolsList
            
            User request: $userPrompt
            
            Please break down this user request into specific tasks that can be executed using the available tools. 
            Return each task on a separate line, with a brief description of what needs to be done and which tool to use.
            Keep each task simple and specific.
            
            Format: Task description - Tool to use
            Example: Launch Chrome browser - mobile_launch_app
            """.trimIndent()

            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: ""

            Log.d("GeminiMcpService", "Task breakdown response: $responseText")

            // Split into individual tasks
            responseText.split("\n").filter { it.isNotBlank() }

        } catch (e: Exception) {
            Log.e("GeminiMcpService", "Error getting task breakdown", e)
            emptyList()
        }
    }

    // Get JSON command from Gemini
    private suspend fun getJsonCommand(toolsList: String, task: String, prevResponse: String): String {
        return try {
            val prompt = """
            Available tools: $toolsList
            
            Task to execute: $task
            
            Previous MCP response: $prevResponse
            
            Now just give the JSON output which I can send to the MCP server to execute the task.
            Return only the JSON object, no other text.
            
            The JSON should follow this format:
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "tools/call",
                "params": {
                    "name": "tool_name",
                    "arguments": {
                        // tool arguments
                    }
                }
            }
            """.trimIndent()

            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: ""

            Log.d("GeminiMcpService", "JSON command response: $responseText")

            // Extract JSON from response (remove any extra text)
            val jsonStart = responseText.indexOf("{")
            val jsonEnd = responseText.lastIndexOf("}") + 1

            if (jsonStart != -1 && jsonEnd > jsonStart) {
                responseText.substring(jsonStart, jsonEnd)
            } else {
                responseText
            }

        } catch (e: Exception) {
            Log.e("GeminiMcpService", "Error getting JSON command", e)
            ""
        }
    }

    // Execute command on MCP server
    private suspend fun executeMcpCommand(jsonCommand: String): String {
        return try {
            val jsonObject = JSONObject(jsonCommand)
            callMcpServerSync(jsonObject)
        } catch (e: Exception) {
            Log.e("GeminiMcpService", "Error executing MCP command", e)
            "Error: ${e.message}"
        }
    }

    // Synchronous call to MCP server
    private suspend fun callMcpServerSync(params: JSONObject): String {
        return try {
            val body = RequestBody.create("application/json".toMediaTypeOrNull(), params.toString())
            val request = Request.Builder()
                .url("$mcpBaseUrl/")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            response.body?.string() ?: "No response"
        } catch (e: IOException) {
            "Error: ${e.message}"
        }
    }

    // Check if service is ready to execute tasks
    fun isReady(): Boolean = isInitialized

    override fun onDestroy() {
        super.onDestroy()
        callback = null
        isInitialized = false
    }
}
