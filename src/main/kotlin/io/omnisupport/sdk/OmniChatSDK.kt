package io.omnisupport.sdk

import android.content.Context
import android.content.Intent

object OmniChatSDK {
    /**
     * Initializes the OmniSupport SDK with your company's Public App Key (Widget ID) and Backend Base URL.
     */
    fun initialize(context: Context, appKey: String, baseUrl: String) {
        OmniChatClient.instance.initialize(context, appKey, baseUrl)
    }

    /**
     * Opens the full customer support chat interface.
     */
    fun openChat(context: Context) {
        val intent = Intent(context, OmniChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Resets the active session and clears cached conversations.
     */
    fun resetSession() {
        OmniChatClient.instance.resetSession()
    }
}
