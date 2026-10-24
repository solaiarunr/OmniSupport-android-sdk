package io.omnisupport.sdk

import kotlinx.serialization.Serializable

@Serializable
enum class OmniSenderType {
    CUSTOMER,
    AGENT,
    AI,
    SYSTEM
}

@Serializable
enum class OmniMessageDeliveryStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ
}

@Serializable
data class OmniChatAppearance(
    val primaryColor: String? = null,
    val headerTextColor: String? = null,
    val position: String? = null,
    val botName: String? = null,
    val launcherText: String? = null,
    val showPoweredBy: Boolean? = null,
    val soundEnabled: Boolean? = null,
    val greeting: String? = null
)

@Serializable
data class OmniChatConfig(
    val widgetId: String,
    val companyId: String? = null,
    val companyName: String? = null,
    val logoUrl: String? = null,
    val welcomeMessage: String? = null,
    val greetingMessage: String? = null,
    val offlineMessage: String? = null,
    val appearance: OmniChatAppearance? = null,
    val publicSdkKey: String? = null,
    val isEnabled: Boolean? = null
)

@Serializable
data class OmniChatAIMetadata(
    val confidenceScore: Double? = null,
    val modelUsed: String? = null,
    val citations: List<String>? = null,
    val provider: String? = null
)

@Serializable
data class OmniChatMessage(
    val id: String,
    val conversationId: String,
    val senderType: OmniSenderType,
    val senderName: String,
    val content: String,
    val isInternalNote: Boolean? = false,
    val createdAt: String = "",
    val aiMetadata: OmniChatAIMetadata? = null,
    var deliveryStatus: OmniMessageDeliveryStatus? = null
)

@Serializable
data class OmniChatSession(
    val visitorToken: String,
    val sessionToken: String? = null,
    val sessionId: String? = null,
    val visitorCompany: String? = null,
    val visitorName: String? = null,
    var conversationId: String? = null
)
