package com.prism.launcher.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.prism.launcher.databinding.PageMessagingRootBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MessagingPageView(context: Context) : LinearLayout(context) {

    private val binding: PageMessagingRootBinding
    private val adapter = ConversationAdapter(emptyList()) { thread ->
        val intent = android.content.Intent(context, ConversationActivity::class.java).apply {
            putExtra("thread_id", thread.threadId)
            putExtra("address", thread.address)
        }
        context.startActivity(intent)
    }

    private var allThreads: List<ThreadInfo> = emptyList()

    init {
        orientation = VERTICAL
        binding = PageMessagingRootBinding.inflate(LayoutInflater.from(context), this, true)
        
        binding.messagingConversationList.layoutManager = LinearLayoutManager(context)
        binding.messagingConversationList.adapter = adapter

        binding.messagingRequestPermissionBtn.setOnClickListener {
            (context as? com.prism.launcher.LauncherActivity)?.requestMessagingPermissions()
        }

        binding.messagingSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterMessages(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        checkPermissionsAndLoad()
    }

    private fun checkPermissionsAndLoad() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            binding.messagingRequestPermissionBtn.visibility = VISIBLE
            loadSamOnly()
        } else {
            binding.messagingRequestPermissionBtn.visibility = GONE
            loadThreads()
        }
    }

    private fun loadSamOnly() {
        val sam = ThreadInfo(-100, "Sam", "Always here for you.")
        // noraThread() parses Nora's transcript off disk, so it stays off the main thread.
        val lifecycleOwner = context as? LifecycleOwner
        if (lifecycleOwner == null) {
            adapter.update(listOf(sam))
            return
        }
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val nora = noraThread()
            withContext(Dispatchers.Main) { adapter.update(listOf(sam, nora)) }
        }
    }

    /**
     * Nora sits alongside Sam as a second permanent thread. She is a simulated visual cortex,
     * not a language model -- see [com.prism.launcher.nora.NoraChat].
     */
    private fun noraThread() = ThreadInfo(
        com.prism.launcher.nora.NoraChat.THREAD_ID,
        com.prism.launcher.nora.NoraChat.DISPLAY_NAME,
        com.prism.launcher.nora.NoraChatStore.lastSnippet(context)
    )

    private fun loadThreads() {
        val lifecycleOwner = context as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val threads = mutableListOf<ThreadInfo>()
            
            // 1. Add Sam (Permanent AI)
            threads.add(ThreadInfo(-100, "Sam", "How can I help you?"))

            // 2. Add Nora (Permanent, brain-based image/video generation)
            threads.add(noraThread())

            // 3. Load Real SMS Threads
            val uri = Uri.parse("content://mms-sms/conversations?simple=true")
            val projection = arrayOf("_id", "recipient_ids", "snippet", "date")

            try {
                context.contentResolver.query(uri, projection, null, null, "date DESC")?.use { cursor ->
                    val idIdx = cursor.getColumnIndex("_id")
                    val recIdx = cursor.getColumnIndex("recipient_ids")
                    val snipIdx = cursor.getColumnIndex("snippet")
                    val dateIdx = cursor.getColumnIndex("date")

                    while (cursor.moveToNext()) {
                        val threadId = cursor.getLong(idIdx)
                        val recipientIds = cursor.getString(recIdx)
                        val snippet = cursor.getString(snipIdx) ?: ""
                        val date = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                        val address = resolveAddress(recipientIds)
                        threads.add(ThreadInfo(threadId, address, snippet, date))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            allThreads = threads
            withContext(Dispatchers.Main) {
                adapter.update(threads)
            }
        }
    }

    private fun filterMessages(query: String) {
        val lifecycleOwner = context as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (query.isBlank()) {
                withContext(Dispatchers.Main) { adapter.update(allThreads) }
                return@launch
            }

            // Search in both SMS and AI records
            val filtered = allThreads.filter { 
                it.address.contains(query, ignoreCase = true) || it.snippet.contains(query, ignoreCase = true)
            }.toMutableList()
            
            // TODO: In a real implementation, we'd also search THROUGH message bodies 
            // by querying content://sms with LIKE %query%

            withContext(Dispatchers.Main) {
                adapter.update(filtered)
            }
        }
    }
    private fun resolveAddress(recipientIds: String?): String {
        if (recipientIds.isNullOrEmpty()) return "Unknown"
        val firstId = recipientIds.split(" ").firstOrNull() ?: return "Unknown"
        
        var address = "Unknown"
        val uri = Uri.parse("content://mms-sms/canonical-address/$firstId")
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    address = c.getString(c.getColumnIndexOrThrow("address"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return address
    }
}
