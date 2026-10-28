package io.omnisupport.sdk

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class OmniChatActivity : AppCompatActivity(), OmniChatListener {
    private val client = OmniChatClient.instance
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val messages = mutableListOf<OmniChatMessage>()

    private lateinit var adapter: MessageAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerSubtitle: TextView
    private lateinit var progressBar: ProgressBar
    private var actionBarLayout: LinearLayout? = null

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

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun createRoundedDrawable(bgColor: Int, cornerRadiusDp: Float, strokeColor: Int = 0, strokeWidthDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = cornerRadiusDp * resources.displayMetrics.density
            if (strokeWidthDp > 0) {
                setStroke((strokeWidthDp * resources.displayMetrics.density).toInt(), strokeColor)
            }
        }
    }

    private fun createBubbleDrawable(bgColor: Int, isCustomer: Boolean, strokeColor: Int = 0, strokeWidthDp: Int = 0): GradientDrawable {
        val r = 16f * resources.displayMetrics.density
        val tail = 4f * resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            // 8 radii: top-left (x,y), top-right (x,y), bottom-right (x,y), bottom-left (x,y)
            cornerRadii = if (isCustomer) {
                floatArrayOf(r, r, r, r, tail, tail, r, r)
            } else {
                floatArrayOf(tail, tail, r, r, r, r, r, r)
            }
            if (strokeWidthDp > 0) {
                setStroke((strokeWidthDp * resources.displayMetrics.density).toInt(), strokeColor)
            }
        }
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
            val brandColor = client.currentConfig?.primaryColor ?: client.currentConfig?.appearance?.primaryColor ?: "#4F46E5"
            setBackgroundColor(Color.parseColor(brandColor))
            setPadding(dp(16f), dp(16f), dp(16f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
            typeface = Typeface.DEFAULT_BOLD
        }
        headerSubtitle = TextView(this).apply {
            text = "● ${client.currentConfig?.appearance?.botName ?: client.currentConfig?.botName ?: "AI Assistant"} • Online"
            setTextColor(Color.parseColor("#E0E7FF"))
            textSize = 12f
        }
        titleContainer.addView(headerTitle)
        titleContainer.addView(headerSubtitle)

        // New Chat Compose Button
        val composeBtn = TextView(this).apply {
            text = "✏️"
            textSize = 16f
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            setOnClickListener { promptNewChat() }
        }

        // Close Button
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12f), dp(6f), dp(4f), dp(6f))
            setOnClickListener { finish() }
        }

        topRow.addView(titleContainer)
        topRow.addView(composeBtn)
        topRow.addView(closeBtn)
        header.addView(topRow)
        rootLayout.addView(header)

        // Progress Bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4f))
        }
        rootLayout.addView(progressBar)

        // Recycler View for chat messages
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@OmniChatActivity).apply { stackFromEnd = true }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            clipToPadding = false
            setOnTouchListener { _, _ ->
                hideKeyboard()
                false
            }
        }
        adapter = MessageAdapter(messages)
        recyclerView.adapter = adapter
        rootLayout.addView(recyclerView)

        // Action Bar for Escalation
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        this.actionBarLayout = actionBar

        val escalateBtn = Button(this).apply {
            text = "🎧 Request Human Agent"
            textSize = 11f
            setTextColor(Color.parseColor("#475569"))
            background = createRoundedDrawable(Color.parseColor("#E2E8F0"), 8f)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { handleEscalate() }
        }
        actionBar.addView(escalateBtn)
        rootLayout.addView(actionBar)

        // Bottom Input Row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Growing dynamic multiline EditText
        inputEditText = EditText(this).apply {
            hint = "Type a message..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 15f
            minLines = 1
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = createRoundedDrawable(Color.parseColor("#F1F5F9"), 20f, Color.parseColor("#E2E8F0"), 1)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        // WhatsApp-style emerald teal circular send button
        sendButton = TextView(this).apply {
            text = "➔"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            rotation = 45f // Angled arrow WhatsApp style
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0BA37F")) // WhatsApp emerald teal
            }
            layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f)).apply {
                leftMargin = dp(10f)
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnClickListener { handleSend() }
        }

        inputRow.addView(inputEditText)
        inputRow.addView(sendButton)
        rootLayout.addView(inputRow)

        return rootLayout
    }

    private fun promptNewChat() {
        AlertDialog.Builder(this)
            .setTitle("Start New Chat?")
            .setMessage("This will start a fresh conversation with support for a new inquiry.")
            .setPositiveButton("Start New Chat") { _, _ -> startFreshChat() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun startFreshChat() {
        client.startNewConversation()
        val greeting = client.currentConfig?.greetingMessage
            ?: client.currentConfig?.welcomeMessage
            ?: client.currentConfig?.appearance?.greeting
            ?: "Hello! How can we help you today?"
        val welcome = OmniChatMessage(
            id = "welcome_${System.currentTimeMillis()}",
            conversationId = "",
            senderType = OmniSenderType.AI,
            senderName = client.currentConfig?.appearance?.botName ?: client.currentConfig?.botName ?: "AI Assistant",
            content = greeting,
            createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        )
        messages.clear()
        messages.add(welcome)
        adapter.notifyDataSetChanged()
        inputEditText.setText("")
    }

    private fun applyDeliveryStatuses() {
        for (msg in messages) {
            if (msg.senderType == OmniSenderType.CUSTOMER) {
                if (msg.id.startsWith("local_") && msg.deliveryStatus == OmniMessageDeliveryStatus.SENT) {
                    continue
                }
                if (msg.isRead == true || msg.deliveryStatus == OmniMessageDeliveryStatus.READ) {
                    msg.deliveryStatus = OmniMessageDeliveryStatus.READ
                } else {
                    msg.deliveryStatus = OmniMessageDeliveryStatus.DELIVERED
                }
            }
        }
    }

    private fun loadInitialData() {
        progressBar.visibility = View.VISIBLE
        scope.launch {
            try {
                val config = client.fetchConfig()
                headerTitle.text = config.companyName ?: "Customer Support"
                headerSubtitle.text = "● ${config.appearance?.botName ?: "AI Assistant"} • Online"

                val activeId = client.activeConversationId
                if (!activeId.isNullOrBlank()) {
                    val history = client.fetchMessages(activeId)
                    if (history.isNotEmpty()) {
                        messages.clear()
                        messages.addAll(history)
                        applyDeliveryStatuses()
                        adapter.notifyDataSetChanged()
                        recyclerView.scrollToPosition(messages.size - 1)
                        updateEscalateButtonVisibility()
                    } else {
                        showWelcomeFallback()
                    }
                } else {
                    showWelcomeFallback()
                }
            } catch (e: Exception) {
                showWelcomeFallback()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun showWelcomeFallback() {
        if (messages.isEmpty()) {
            val greeting = client.currentConfig?.greetingMessage
                ?: client.currentConfig?.welcomeMessage
                ?: client.currentConfig?.appearance?.greeting
                ?: "Hello! How can we help you today?"
            val welcome = OmniChatMessage(
                id = "welcome_init",
                conversationId = "",
                senderType = OmniSenderType.AI,
                senderName = client.currentConfig?.appearance?.botName ?: client.currentConfig?.botName ?: "AI Assistant",
                content = greeting,
                createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            )
            messages.add(welcome)
            adapter.notifyDataSetChanged()
        }
    }

    private fun handleSend() {
        val text = inputEditText.text.toString().trim()
        if (text.isEmpty()) return
        inputEditText.setText("")

        val localId = "local_${System.currentTimeMillis()}"
        val localMsg = OmniChatMessage(
            id = localId,
            conversationId = client.activeConversationId ?: "",
            senderType = OmniSenderType.CUSTOMER,
            senderName = "You",
            content = text,
            createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()),
            deliveryStatus = OmniMessageDeliveryStatus.SENT // WhatsApp Single Tick!
        )
        messages.add(localMsg)
        adapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)

        val sendStart = System.currentTimeMillis()
        scope.launch {
            try {
                client.sendMessage(text)

                // Natural WhatsApp single-to-double tick progression timing (~350ms)
                val elapsed = System.currentTimeMillis() - sendStart
                if (elapsed < 350) {
                    delay(350 - elapsed)
                }

                val idx = messages.indexOfFirst { it.id == localId }
                if (idx != -1) {
                    messages[idx].deliveryStatus = OmniMessageDeliveryStatus.DELIVERED // WhatsApp Double Tick!
                    adapter.notifyItemChanged(idx)
                }

                client.activeConversationId?.let { convId ->
                    val fresh = client.fetchMessages(convId)
                    if (fresh.isNotEmpty()) {
                        messages.clear()
                        messages.addAll(fresh)
                        applyDeliveryStatuses()
                        adapter.notifyDataSetChanged()
                        recyclerView.scrollToPosition(messages.size - 1)
                    }
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
                actionBarLayout?.visibility = View.GONE
                val systemMsg = OmniChatMessage(
                    id = "sys_${System.currentTimeMillis()}",
                    conversationId = client.activeConversationId ?: "",
                    senderType = OmniSenderType.SYSTEM,
                    senderName = "System",
                    content = "🎧 Your inquiry has been routed to a human support specialist. A live agent will join shortly."
                )
                messages.add(systemMsg)
                adapter.notifyItemInserted(messages.size - 1)
                recyclerView.scrollToPosition(messages.size - 1)
            } catch (e: Exception) {
                Toast.makeText(this@OmniChatActivity, "Escalation failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateEscalateButtonVisibility() {
        val hasAgent = messages.any { it.senderType == OmniSenderType.AGENT }
        actionBarLayout?.visibility = if (hasAgent) View.GONE else View.VISIBLE
    }

    override fun onNewMessage(message: OmniChatMessage) {
        if (message.isInternalNote == true) return
        if (!messages.any { it.id == message.id }) {
            messages.add(message)
            applyDeliveryStatuses()
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(messages.size - 1)
            updateEscalateButtonVisibility()
        }
    }

    override fun onMessagesSynced(syncedMessages: List<OmniChatMessage>) {
        val publicMessages = syncedMessages.filter { it.isInternalNote != true }
        var needsReload = false
        for (serverMsg in publicMessages) {
            val existing = messages.find { it.id == serverMsg.id }
            if (existing != null) {
                val newStatus = serverMsg.deliveryStatus ?: if (serverMsg.isRead == true) OmniMessageDeliveryStatus.READ else OmniMessageDeliveryStatus.DELIVERED
                if (existing.deliveryStatus != newStatus || existing.isRead != serverMsg.isRead) {
                    existing.deliveryStatus = newStatus
                    if (serverMsg.isRead != null) existing.isRead = serverMsg.isRead
                    needsReload = true
                }
            } else {
                messages.add(serverMsg)
                needsReload = true
            }
        }
        if (needsReload) {
            applyDeliveryStatuses()
            adapter.notifyDataSetChanged()
            updateEscalateButtonVisibility()
        }
    }

    override fun onTypingChanged(isTyping: Boolean, senderName: String) {
        headerSubtitle.text = if (isTyping) "$senderName is typing..." else "● ${client.currentConfig?.appearance?.botName ?: client.currentConfig?.botName ?: "AI Assistant"} • Online"
    }

    override fun onStatusChanged(status: String) {
        // Status updated
    }

    private fun formatTimestamp(isoStr: String): String {
        if (isoStr.isBlank()) return ""
        val date = parseIsoDate(isoStr) ?: return ""
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
    }

    private fun formatDateHeader(isoStr: String): String {
        val date = parseIsoDate(isoStr) ?: return ""
        val cal1 = Calendar.getInstance().apply { time = date }
        val cal2 = Calendar.getInstance()
        return if (cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
        ) {
            "Today"
        } else {
            cal2.add(Calendar.DAY_OF_YEAR, -1)
            if (cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
            ) {
                "Yesterday"
            } else {
                SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(date)
            }
        }
    }

    private fun parseIsoDate(isoStr: String): Date? {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US).apply {
                    if (f.endsWith("'Z'") || f.endsWith("Z")) timeZone = TimeZone.getTimeZone("UTC")
                }
                return sdf.parse(isoStr)
            } catch (_: Exception) {}
        }
        return null
    }

    // Message Adapter with WhatsApp Ticks and Content-based Bubbles
    inner class MessageAdapter(private val list: List<OmniChatMessage>) : RecyclerView.Adapter<MessageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            // Date Header badge
            val dateHeader = TextView(parent.context).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#64748B"))
                background = createRoundedDrawable(Color.parseColor("#E2E8F0"), 10f)
                setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(8f)
                    bottomMargin = dp(8f)
                }
                layoutParams = lp
            }
            root.addView(dateHeader)

            // Bubble container (outer row)
            val bubbleRow = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2f)
                    bottomMargin = dp(2f)
                }
            }

            // Avatar for receiver
            val avatarView = TextView(parent.context).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28f), dp(28f)).apply {
                    rightMargin = dp(6f)
                    gravity = Gravity.TOP
                }
            }
            bubbleRow.addView(avatarView)

            // Main Bubble card (Content-based width, max 80% screen)
            val bubbleCard = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(8f), dp(12f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val senderNameLabel = TextView(parent.context).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#64748B"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(2f)
                }
            }
            bubbleCard.addView(senderNameLabel)

            val messageText = TextView(parent.context).apply {
                textSize = 15f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            bubbleCard.addView(messageText)

            // Footer row: Timestamp + WhatsApp ticks
            val footerRow = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.END
                    topMargin = dp(2f)
                }
            }

            val timeLabel = TextView(parent.context).apply {
                textSize = 10f
            }
            footerRow.addView(timeLabel)

            val tickLabel = TextView(parent.context).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(4f)
                }
            }
            footerRow.addView(tickLabel)
            bubbleCard.addView(footerRow)

            bubbleRow.addView(bubbleCard)
            root.addView(bubbleRow)

            return MessageViewHolder(root, dateHeader, bubbleRow, avatarView, bubbleCard, senderNameLabel, messageText, timeLabel, tickLabel)
        }

        override fun getItemCount() = list.size

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val item = list[position]
            val timeStr = formatTimestamp(item.createdAt)
            holder.messageText.text = item.content
            holder.timeLabel.text = timeStr

            // Check date header visibility
            val currentDate = if (item.createdAt.isNotBlank()) formatDateHeader(item.createdAt) else ""
            val prevDate = if (position > 0 && list[position - 1].createdAt.isNotBlank()) formatDateHeader(list[position - 1].createdAt) else ""
            if (currentDate.isNotBlank() && (position == 0 || currentDate != prevDate)) {
                holder.dateHeader.visibility = View.VISIBLE
                holder.dateHeader.text = currentDate
            } else {
                holder.dateHeader.visibility = View.GONE
            }

            when (item.senderType) {
                OmniSenderType.CUSTOMER -> {
                    holder.bubbleRow.gravity = Gravity.END
                    holder.avatarView.visibility = View.GONE
                    holder.senderNameLabel.visibility = View.GONE

                    val primary = client.currentConfig?.primaryColor ?: client.currentConfig?.appearance?.primaryColor ?: "#4F46E5"
                    holder.bubbleCard.background = createBubbleDrawable(Color.parseColor(primary), isCustomer = true)
                    holder.messageText.setTextColor(Color.WHITE)
                    holder.timeLabel.setTextColor(Color.parseColor("#CCFFFFFF"))

                    // Read status checkmarks hidden as requested
                    holder.tickLabel.visibility = View.GONE
                }
                OmniSenderType.AGENT, OmniSenderType.AI -> {
                    holder.bubbleRow.gravity = Gravity.START
                    holder.avatarView.visibility = View.VISIBLE
                    holder.senderNameLabel.visibility = View.VISIBLE

                    val isAi = item.senderType == OmniSenderType.AI
                    holder.avatarView.text = if (isAi) "🤖" else "🎧"
                    holder.avatarView.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(if (isAi) "#EDE9FE" else "#DCFCE7"))
                    }

                    holder.senderNameLabel.text = item.senderName.ifEmpty { if (isAi) "AI Assistant" else "Support Team" }
                    holder.senderNameLabel.setTextColor(Color.parseColor(if (isAi) "#7C3AED" else "#059669"))

                    holder.bubbleCard.background = createBubbleDrawable(Color.WHITE, isCustomer = false, strokeColor = Color.parseColor("#E2E8F0"), strokeWidthDp = 1)
                    holder.messageText.setTextColor(Color.parseColor("#0F172A"))
                    holder.timeLabel.setTextColor(Color.parseColor("#94A3B8"))
                    holder.tickLabel.visibility = View.GONE
                }
                OmniSenderType.SYSTEM -> {
                    holder.bubbleRow.gravity = Gravity.CENTER_HORIZONTAL
                    holder.avatarView.visibility = View.GONE
                    holder.senderNameLabel.visibility = View.GONE
                    holder.tickLabel.visibility = View.GONE

                    holder.bubbleCard.background = createRoundedDrawable(Color.parseColor("#FEF3C7"), 12f, Color.parseColor("#FDE68A"), 1)
                    holder.messageText.setTextColor(Color.parseColor("#B45309"))
                    holder.timeLabel.setTextColor(Color.parseColor("#D97706"))
                }
            }
        }
    }

    inner class MessageViewHolder(
        view: View,
        val dateHeader: TextView,
        val bubbleRow: LinearLayout,
        val avatarView: TextView,
        val bubbleCard: LinearLayout,
        val senderNameLabel: TextView,
        val messageText: TextView,
        val timeLabel: TextView,
        val tickLabel: TextView
    ) : RecyclerView.ViewHolder(view)
}
