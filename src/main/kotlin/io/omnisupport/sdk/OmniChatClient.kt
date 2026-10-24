package io.omnisupport.sdk

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

interface OmniChatListener {
    fun onNewMessage(message: OmniChatMessage)
    fun onMessagesSynced(messages: List<OmniChatMessage>) {}
    fun onTypingChanged(isTyping: Boolean, senderName: String)
    fun onStatusChanged(status: String)
}

class OmniChatClient private constructor() {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val httpClient = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var appKey: String = ""
        private set
    var baseUrl: String = "http://localhost:3000"
        private set

    var currentConfig: OmniChatConfig? = null
        private set
    var currentSession: OmniChatSession? = null
        private set
    var activeConversationId: String? = null
        private set

    private var prefs: SharedPreferences? = null
    private var pollJob: Job? = null
    private val listeners = mutableSetOf<OmniChatListener>()

    companion object {
        val instance: OmniChatClient by lazy { OmniChatClient() }
    }

    fun initialize(context: Context, appKey: String, baseUrl: String) {
        this.appKey = appKey
        this.baseUrl = baseUrl.trimEnd('/')
        this.prefs = context.getSharedPreferences("omnisupport_sdk_$appKey", Context.MODE_PRIVATE)
        loadCachedSession()
    }

    fun addListener(listener: OmniChatListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OmniChatListener) {
        listeners.remove(listener)
    }

    private val apiBaseUrl: String
        get() = if (baseUrl.endsWith("/api")) baseUrl else "$baseUrl/api"

    suspend fun fetchConfig(): OmniChatConfig = withContext(Dispatchers.IO) {
        val url = "$apiBaseUrl/widget/config/$appKey"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Unexpected response $response")

        val body = response.body?.string() ?: throw IOException("Empty body")
        val jsonElement = json.parseToJsonElement(body)
        val dataObj = jsonElement.toString()

        @kotlinx.serialization.Serializable
        data class ConfigWrapper(val success: Boolean, val data: OmniChatConfig)

        val decoded = json.decodeFromString<ConfigWrapper>(dataObj)
        currentConfig = decoded.data
        decoded.data
    }

    suspend fun getOrCreateSession(visitorName: String? = null, visitorCompany: String? = null): OmniChatSession = withContext(Dispatchers.IO) {
        currentSession?.let { if (it.visitorToken.isNotEmpty()) return@withContext it }

        val url = "$apiBaseUrl/widget/session"
        val jsonBody = """
            {
                "widgetId": "$appKey",
                "visitorName": ${visitorName?.let { "\"$it\"" } ?: "null"},
                "visitorCompany": ${visitorCompany?.let { "\"$it\"" } ?: "null"}
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Failed to create session: $response")

        val body = response.body?.string() ?: throw IOException("Empty body")
        @kotlinx.serialization.Serializable
        data class SessionWrapper(val success: Boolean, val data: OmniChatSession)

        val decoded = json.decodeFromString<SessionWrapper>(body)
        currentSession = decoded.data
        activeConversationId = decoded.data.conversationId
        persistSession()
        decoded.data
    }

    suspend fun createConversation(initialMessage: String, visitorName: String? = null): String = withContext(Dispatchers.IO) {
        val session = getOrCreateSession(visitorName = visitorName)
        val url = "$apiBaseUrl/widget/conversations"

        val jsonBody = """
            {
                "widgetId": "$appKey",
                "visitorToken": "${session.visitorToken}",
                "initialMessage": "$initialMessage",
                "visitorName": "${visitorName ?: session.visitorName ?: "Android User"}"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .header("x-visitor-token", session.visitorToken)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Failed to create conversation")

        val body = response.body?.string() ?: throw IOException("Empty body")
        @kotlinx.serialization.Serializable
        data class ConvData(val id: String, val ticketNumber: String? = null)
        @kotlinx.serialization.Serializable
        data class ConvWrapper(val success: Boolean, val data: ConvData)

        val decoded = json.decodeFromString<ConvWrapper>(body)
        val convId = decoded.data.id
        activeConversationId = convId
        currentSession?.conversationId = convId
        persistSession()
        startPolling(convId)
        convId
    }

    suspend fun fetchMessages(conversationId: String): List<OmniChatMessage> = withContext(Dispatchers.IO) {
        val session = getOrCreateSession()
        val url = "$apiBaseUrl/widget/conversations/$conversationId/messages?visitorToken=${session.visitorToken}"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("x-visitor-token", session.visitorToken)
            .build()

        val response = httpClient.newCall(request).execute()
        if (response.code == 404) {
            activeConversationId = null
            saveSession()
            stopPolling()
            return@withContext emptyList()
        }
        if (!response.isSuccessful) return@withContext emptyList()

        val body = response.body?.string() ?: return@withContext emptyList()
        @kotlinx.serialization.Serializable
        data class MsgWrapper(val success: Boolean, val data: List<OmniChatMessage>)

        val decoded = json.decodeFromString<MsgWrapper>(body)
        decoded.data
    }

    suspend fun sendMessage(content: String, conversationId: String? = null): OmniChatMessage = withContext(Dispatchers.IO) {
        val targetId = conversationId ?: activeConversationId ?: createConversation(content)
        val session = getOrCreateSession()
        val url = "$apiBaseUrl/widget/conversations/$targetId/messages"

        val jsonBody = """
            {
                "visitorToken": "${session.visitorToken}",
                "content": "$content"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .header("x-visitor-token", session.visitorToken)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        if (response.code == 404) {
            activeConversationId = null
            saveSession()
            val newId = createConversation(content)
            return@withContext OmniChatMessage(
                id = "msg_${System.currentTimeMillis()}",
                conversationId = newId,
                senderType = OmniSenderType.CUSTOMER,
                senderName = "You",
                content = content,
                deliveryStatus = OmniMessageDeliveryStatus.DELIVERED
            )
        }
        if (!response.isSuccessful) throw IOException("Failed to send message")

        val body = response.body?.string() ?: throw IOException("Empty body")
        @kotlinx.serialization.Serializable
        data class SingleMsgWrapper(val success: Boolean, val data: OmniChatMessage, val conversationId: String? = null)

        val decoded = json.decodeFromString<SingleMsgWrapper>(body)
        val finalConvId = decoded.conversationId ?: targetId
        if (finalConvId != targetId) {
            activeConversationId = finalConvId
            saveSession()
        }
        startPolling(finalConvId)
        decoded.data
    }

    suspend fun escalate(reason: String = "Customer requested human support"): Unit = withContext(Dispatchers.IO) {
        val convId = activeConversationId ?: return@withContext
        val session = getOrCreateSession()
        val url = "$apiBaseUrl/widget/conversations/$convId/escalate"

        val jsonBody = """
            {
                "visitorToken": "${session.visitorToken}",
                "reason": "$reason"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .header("x-visitor-token", session.visitorToken)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().close()
    }

    fun startPolling(conversationId: String) {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                delay(3500)
                try {
                    val messages = fetchMessages(conversationId)
                    withContext(Dispatchers.Main) {
                        listeners.forEach { it.onMessagesSynced(messages) }
                        messages.lastOrNull()?.let { lastMsg ->
                            listeners.forEach { it.onNewMessage(lastMsg) }
                        }
                    }
                } catch (e: Exception) {
                    // silent fallback
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun resetSession() {
        stopPolling()
        currentSession = null
        activeConversationId = null
        prefs?.edit()?.clear()?.apply()
    }

    fun startNewConversation() {
        stopPolling()
        activeConversationId = null
        currentSession?.conversationId = null
        prefs?.edit()?.remove("session_data")?.apply()
    }

    private fun persistSession() {
        currentSession?.let {
            val serialized = json.encodeToString(it)
            prefs?.edit()?.putString("session_data", serialized)?.apply()
        }
    }

    private fun loadCachedSession() {
        prefs?.getString("session_data", null)?.let {
            try {
                val decoded = json.decodeFromString<OmniChatSession>(it)
                currentSession = decoded
                activeConversationId = decoded.conversationId
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
