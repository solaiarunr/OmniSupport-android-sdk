package io.omnisupport.sdk

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class OmniChatActivity : AppCompatActivity(), OmniChatListener {
    private val client = OmniChatClient.instance
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val messages = mutableListOf<OmniChatMessage>()

    private lateinit var adapter: MessageAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var headerTitle: TextView
    private lateinit var headerSubtitle: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client.addListener(this)

        val root = createViewHierarchy()
        setContentView(root)

        loadInitialData()
    }

    override fun onDestroy() {
        super.onDestroy()
        client.removeListener(this)
        scope.cancel()
    }

    private fun createViewHierarchy(): View {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(client.currentConfig?.appearance?.primaryColor ?: "#4F46E5"))
            setPadding(32, 40, 32, 24)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        headerTitle = TextView(this).apply {
            text = client.currentConfig?.companyName ?: "Customer Support"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        headerSubtitle = TextView(this).apply {
            text = "● ${client.currentConfig?.appearance?.botName ?: "AI Assistant"}"
            setTextColor(Color.parseColor("#E0E7FF"))
            textSize = 12f
        }
        titleContainer.addView(headerTitle)
        titleContainer.addView(headerSubtitle)

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(16, 0, 8, 0)
            setOnClickListener { finish() }
        }

        topRow.addView(titleContainer)
        topRow.addView(closeBtn)
        header.addView(topRow)
        rootLayout.addView(header)

        // Progress Bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
        }
        rootLayout.addView(progressBar)

        // Recycler View for chat messages
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@OmniChatActivity).apply { stackFromEnd = true }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
            setPadding(16, 16, 16, 16)
            clipToPadding = false
        }
        adapter = MessageAdapter(messages)
        recyclerView.adapter = adapter
        rootLayout.addView(recyclerView)

        // Action Bar for Escalation
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            setPadding(24, 12, 24, 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val escalateBtn = Button(this).apply {
            text = "🎧 Request Human Agent"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            setOnClickListener { handleEscalate() }
        }
        actionBar.addView(escalateBtn)
        rootLayout.addView(actionBar)

        // Bottom Input Row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        inputEditText = EditText(this).apply {
            hint = "Type your message..."
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            setPadding(24, 16, 24, 16)
            textSize = 14f
        }

        sendButton = Button(this).apply {
            text = "➔"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(client.currentConfig?.appearance?.primaryColor ?: "#4F46E5"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 16
            }
            setOnClickListener { handleSend() }
        }

        inputRow.addView(inputEditText)
        inputRow.addView(sendButton)
        rootLayout.addView(inputRow)

        return rootLayout
    }

    private fun loadInitialData() {
        progressBar.visibility = View.VISIBLE
        scope.launch {
            try {
                val config = client.fetchConfig()
                headerTitle.text = config.companyName ?: "Customer Support"
                headerSubtitle.text = "● ${config.appearance?.botName ?: "AI Assistant"}"

                client.activeConversationId?.let { convId ->
                    val history = client.fetchMessages(convId)
                    messages.clear()
                    messages.addAll(history)
                    adapter.notifyDataSetChanged()
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            } catch (e: Exception) {
                // handle error
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun handleSend() {
        val text = inputEditText.text.toString().trim()
        if (text.isEmpty()) return
        inputEditText.setText("")

        val localMsg = OmniChatMessage(
            id = "local_${System.currentTimeMillis()}",
            conversationId = client.activeConversationId ?: "",
            senderType = OmniSenderType.CUSTOMER,
            senderName = "You",
            content = text
        )
        messages.add(localMsg)
        adapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)

        scope.launch {
            try {
                client.sendMessage(text)
                client.activeConversationId?.let { convId ->
                    val fresh = client.fetchMessages(convId)
                    messages.clear()
                    messages.addAll(fresh)
                    adapter.notifyDataSetChanged()
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            } catch (e: Exception) {
                Toast.makeText(this@OmniChatActivity, "Send failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleEscalate() {
        scope.launch {
            try {
                client.escalate()
                val systemMsg = OmniChatMessage(
                    id = "sys_${System.currentTimeMillis()}",
                    conversationId = client.activeConversationId ?: "",
                    senderType = OmniSenderType.SYSTEM,
                    senderName = "System",
                    content = "Your inquiry has been escalated to a live support agent."
                )
                messages.add(systemMsg)
                adapter.notifyItemInserted(messages.size - 1)
                recyclerView.scrollToPosition(messages.size - 1)
            } catch (e: Exception) {
                Toast.makeText(this@OmniChatActivity, "Escalation failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewMessage(message: OmniChatMessage) {
        if (!messages.any { it.id == message.id }) {
            messages.add(message)
            adapter.notifyItemInserted(messages.size - 1)
            recyclerView.scrollToPosition(messages.size - 1)
        }
    }

    override fun onTypingChanged(isTyping: Boolean, senderName: String) {
        headerSubtitle.text = if (isTyping) "$senderName is typing..." else "● ${client.currentConfig?.appearance?.botName ?: "AI Assistant"}"
    }

    override fun onStatusChanged(status: String) {
        // Status updated
    }

    // Message Adapter
    inner class MessageAdapter(private val list: List<OmniChatMessage>) : RecyclerView.Adapter<MessageViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val tv = TextView(parent.context).apply {
                setPadding(24, 16, 24, 16)
                textSize = 14f
            }
            return MessageViewHolder(tv)
        }

        override fun getItemCount() = list.size

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val item = list[position]
            val tv = holder.itemView as TextView
            tv.text = item.content

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }

            when (item.senderType) {
                OmniSenderType.CUSTOMER -> {
                    params.gravity = Gravity.END
                    params.leftMargin = 100
                    params.rightMargin = 0
                    tv.setBackgroundColor(Color.parseColor(client.currentConfig?.appearance?.primaryColor ?: "#4F46E5"))
                    tv.setTextColor(Color.WHITE)
                }
                OmniSenderType.AGENT, OmniSenderType.AI -> {
                    params.gravity = Gravity.START
                    params.rightMargin = 100
                    params.leftMargin = 0
                    tv.setBackgroundColor(Color.WHITE)
                    tv.setTextColor(Color.parseColor("#1E293B"))
                }
                OmniSenderType.SYSTEM -> {
                    params.gravity = Gravity.CENTER_HORIZONTAL
                    tv.setBackgroundColor(Color.parseColor("#EFF6FF"))
                    tv.setTextColor(Color.parseColor("#2563EB"))
                }
            }
            tv.layoutParams = params
        }
    }

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
