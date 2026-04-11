package com.prism.launcher

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ActivitySettingsBinding
import com.prism.launcher.databinding.ItemSettingHeaderBinding
import com.prism.launcher.databinding.ItemSettingNavBinding
import com.prism.launcher.databinding.ItemSettingToggleBinding
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.DownloadManager
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var adapter: SettingsAdapter
    
    private var pendingDownloadUrl: String? = null
    private var pendingDownloadName: String? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            pendingDownloadUrl?.let { url ->
                pendingDownloadName?.let { name ->
                    startDownloadSequence(name, url)
                }
            }
        } else {
            Toast.makeText(this, "Storage permission required for downloads", Toast.LENGTH_LONG).show()
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != -1L && id == PrismSettings.getAiDownloadId(context)) {
                checkDownloadStatus(id)
            }
        }
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val fileName = uri.lastPathSegment ?: "external_model.tflite"
            copyUriToInternal(uri, fileName)
        }
    }

    private fun downloadModel(name: String, url: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url
            pendingDownloadName = name
            permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startDownloadSequence(name, url)
        }
    }

    private fun startDownloadSequence(name: String, url: String) {
        val extension = if (url.endsWith(".task")) "task" else "bin"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading $name")
            .setDescription("Prism AI Model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "PrismAI/$name.$extension")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")

        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        PrismSettings.setAiDownloadId(this, downloadId)
        
        Toast.makeText(this, "Download started: $name", Toast.LENGTH_SHORT).show()
        adapter.setItems(buildItems())
    }

    private fun checkDownloadStatus(id: Long) {
        val q = DownloadManager.Query().setFilterById(id)
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        dm.query(q)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriString = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    val localUri = Uri.parse(uriString)
                    
                    // Always try to get a clean filename from the URI
                    val fileName = localUri.lastPathSegment ?: "downloaded_model.task"
                    copyUriToInternal(localUri, fileName)
                    
                    Toast.makeText(this, "AI Model downloaded successfully!", Toast.LENGTH_LONG).show()
                } else if (status == DownloadManager.STATUS_FAILED) {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    Toast.makeText(this, "Download failed (Reason: $reason)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyUriToInternal(uri: Uri, fileName: String) {
        val modelsDir = java.io.File(filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        // Cleanup old models to save space
        modelsDir.listFiles()?.forEach { it.delete() }

        val targetFile = java.io.File(modelsDir, fileName)
        
        val progressDialog = PrismDialogFactory.show(
            this,
            "Ingesting Intelligence",
            "Optimizing $fileName for Sam...",
            positiveText = null, // Hide buttons during copy
            negativeText = null,
            showProgress = true
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileSize = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var currentProgress = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            currentProgress += bytesRead
                            
                            if (fileSize > 0) {
                                val percentage = (currentProgress * 100 / fileSize).toInt()
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    PrismDialogFactory.updateProgress(progressDialog, percentage)
                                }
                            }
                        }
                    }
                }
                
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    PrismSettings.setLocalAiModelPath(this@SettingsActivity, targetFile.absolutePath)
                    adapter.setItems(buildItems())
                    Toast.makeText(this@SettingsActivity, "Intelligence Acquired: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@SettingsActivity, "Sync Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(downloadReceiver)
    }

    private fun pickLocalModel() {
        modelPicker.launch("*/*")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar
        binding.settingsToolbar.title = "Settings"
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        // Setup RecyclerView
        adapter = SettingsAdapter(buildItems(), this::onItemClick)
        binding.settingsList.layoutManager = LinearLayoutManager(this)
        binding.settingsList.adapter = adapter
    }

    private fun buildItems(): List<SettingItem> {
        return listOf(
            SettingItem.Header("Launcher"),
            SettingItem.Picker(
                "Default page",
                "Which page shows when Prism opens",
                listOf("Left (Browser)", "Center (Desktop)", "Right (App drawer)"),
                PrismSettings.getDefaultPage(this),
                { PrismSettings.setDefaultPage(this, it) }
            ),
            SettingItem.Toggle(
                "Show drawer labels",
                "Display app names below icons in the drawer",
                PrismSettings.getShowDrawerLabels(this),
                { PrismSettings.setShowDrawerLabels(this, it) }
            ),
            SettingItem.Picker(
                "Glow Accent",
                "Choose the glow color for borders and navigation",
                listOf("Cyan", "Magenta", "Lime", "Gold", "Electric Blue"),
                when (PrismSettings.getGlowColor(this)) {
                    android.graphics.Color.parseColor("#FFFF00FF") -> 1
                    android.graphics.Color.parseColor("#FF00FF00") -> 2
                    android.graphics.Color.parseColor("#FFFFD700") -> 3
                    android.graphics.Color.parseColor("#FF2222FF") -> 4
                    else -> 0
                },
                { idx ->
                    val color = when (idx) {
                        1 -> "#FFFF00FF" // Magenta
                        2 -> "#FF00FF00" // Lime
                        3 -> "#FFFFD700" // Gold
                        4 -> "#FF2222FF" // Electric Blue
                        else -> "#FF7C9EFF" // Cyan
                    }
                    PrismSettings.setGlowColor(this, android.graphics.Color.parseColor(color))
                    adapter.setItems(buildItems()) // Refresh to update neon borders if needed
                }
            ),

            SettingItem.Header("Browser"),
            SettingItem.Picker(
                "Search engine",
                "Default engine for the address bar",
                listOf("DuckDuckGo", "Google", "Bing", "Custom"),
                when (PrismSettings.getSearchEngine(this)) {
                    "google" -> 1
                    "bing" -> 2
                    "custom" -> 3
                    else -> 0
                },
                { idx ->
                    val engine = when (idx) {
                        1 -> "google"
                        2 -> "bing"
                        3 -> "custom"
                        else -> "ddg"
                    }
                    PrismSettings.setSearchEngine(this, engine)
                    if (engine == "custom") promptCustomSearchUrl()
                }
            ),
            SettingItem.Toggle(
                "Enable JavaScript",
                "Allow JS execution in standard tabs",
                PrismSettings.getJsEnabled(this),
                { PrismSettings.setJsEnabled(this, it) }
            ),
            SettingItem.Toggle(
                "Private by default",
                "New tabs open in private mode",
                PrismSettings.getPrivateByDefault(this),
                { PrismSettings.setPrivateByDefault(this, it) }
            ),

            SettingItem.Header("Privacy & VPN"),
            SettingItem.Toggle(
                "VPN auto-start",
                "Automatically connect VPN when a private tab opens",
                PrismSettings.getVpnAutoStart(this),
                { PrismSettings.setVpnAutoStart(this, it) }
            ),
            SettingItem.Toggle(
                "Locked private tabs",
                "Require biometric unlock to access private tabs",
                PrismSettings.getPrivateTabsLocked(this),
                { PrismSettings.setPrivateTabsLocked(this, it) }
            ),
            SettingItem.TextInput(
                "Primary DNS",
                "Used by the private VPN tunnel",
                PrismSettings.getPrimaryDns(this),
                { PrismSettings.setPrimaryDns(this, it) }
            ),
            SettingItem.TextInput(
                "Secondary DNS",
                "Used by the private VPN tunnel",
                PrismSettings.getSecondaryDns(this),
                { PrismSettings.setSecondaryDns(this, it) }
            ),

            SettingItem.Header("Intelligence & Messaging"),
            SettingItem.Picker(
                "Sam AI Engine",
                "Choose between local on-device AI or cloud LLM",
                listOf("Local TFLite", "Cloud API"),
                if (PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_CLOUD) 1 else 0,
                { 
                    PrismSettings.setAiMode(this, if (it == 1) PrismSettings.AI_MODE_CLOUD else PrismSettings.AI_MODE_LOCAL)
                    adapter.setItems(buildItems())
                }
            ),
            
            SettingItem.TextInput(
                "Cloud API Key",
                "OpenAI, Gemini, or custom provider key",
                PrismSettings.getCloudAiKey(this),
                { PrismSettings.setCloudAiKey(this, it) },
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_CLOUD
            ),
            SettingItem.TextInput(
                "Cloud Base URL",
                "Endpoint root (must be OpenAI compatible)",
                PrismSettings.getCloudAiBaseUrl(this),
                { PrismSettings.setCloudAiBaseUrl(this, it) },
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_CLOUD
            ),
            SettingItem.TextInput(
                "Cloud Model ID",
                "e.g. gpt-4o, gemini-1.5-pro",
                PrismSettings.getCloudAiModel(this),
                { PrismSettings.setCloudAiModel(this, it) },
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_CLOUD
            ),

            SettingItem.Nav(
                "Local AI Model",
                "Select a .tflite model from storage",
                { pickLocalModel() },
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_LOCAL
            ),
            
            SettingItem.Header("Available AI Models"),
            SettingItem.Nav(
                "Falcon-1B RefinedWeb",
                "Fast & efficient (1B params, ~600MB)",
                { downloadModel("Falcon-1B", PrismSettings.MODEL_FALCON_1B) },
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_LOCAL
            ),
            SettingItem.Nav(
                "Qwen2.5-1.5B (Expert)",
                "User-preferred high performance task bundle",
                { downloadModel("Qwen-1.5B", PrismSettings.MODEL_QWEN_1_5) },
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_LOCAL
            ),
            SettingItem.Nav(
                "Phi-2 (Microsoft)",
                "High Intellect (2.7B params, ~1.5GB RAM)",
                { downloadModel("Phi-2", PrismSettings.MODEL_PHI_2) },
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_LOCAL
            ),
            SettingItem.Nav(
                "MobileBERT-QA",
                "Specialized for question answering",
                { downloadModel("MobileBERT-QA", PrismSettings.MODEL_MOBILEBERT) },
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_LOCAL
            ),

            SettingItem.Header("Blocklist"),
            SettingItem.Nav(
                "Manage blocklist",
                "Add, remove, or import custom blocked domains",
                {
                    val intent = android.content.Intent(this, BlocklistActivity::class.java)
                    startActivity(intent)
                }
            )
        )
    }

    private fun onItemClick(item: SettingItem, position: Int) {
        when (item) {
            is SettingItem.Toggle -> {
                item.value = !item.value
                item.onChanged(item.value)
                adapter.notifyItemChanged(position)
            }
            is SettingItem.Picker -> {
                PrismDialogFactory.show(
                    this,
                    item.title,
                    "Choose an option:",
                    onPositive = {},
                    customView = android.widget.ListView(this).apply {
                        adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_list_item_single_choice, item.options)
                        choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
                        setItemChecked(item.currentSelection, true)
                        setOnItemClickListener { _, _, which, _ ->
                            item.currentSelection = which
                            item.onChanged(which)
                            this@SettingsActivity.adapter.notifyItemChanged(position)
                        }
                    }
                )
            }
            is SettingItem.TextInput -> {
                val input = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    setText(item.value)
                }
                PrismDialogFactory.show(
                    this,
                    item.title,
                    item.subtitle,
                    onPositive = {
                        val newValue = input.text.toString().trim()
                        if (newValue.isNotEmpty()) {
                            item.value = newValue
                            item.onChanged(newValue)
                            this@SettingsActivity.adapter.notifyItemChanged(position)
                        }
                    },
                    customView = input
                )
            }
            is SettingItem.Nav -> {
                item.onClick()
            }
            is SettingItem.Header -> {} 
        }
    }

    private fun promptCustomSearchUrl() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(PrismSettings.getCustomSearchUrl(this@SettingsActivity))
        }
        PrismDialogFactory.show(
            this,
            "Custom Search Engine",
            "Enter search URL. Use %s for query placeholder.",
            onPositive = {
                PrismSettings.setCustomSearchUrl(this@SettingsActivity, input.text.toString().trim())
            },
            customView = input
        )
    }
}

// ── Models & Adapter ────────────────────────────────────────────────────────

sealed class SettingItem(open val isEnabled: Boolean = true) {
    data class Header(val title: String) : SettingItem(true)
    data class Toggle(val title: String, val subtitle: String, var value: Boolean, val onChanged: (Boolean) -> Unit, override val isEnabled: Boolean = true) : SettingItem(isEnabled)
    data class Picker(val title: String, val subtitle: String, val options: List<String>, var currentSelection: Int, val onChanged: (Int) -> Unit, override val isEnabled: Boolean = true) : SettingItem(isEnabled)
    data class TextInput(val title: String, val subtitle: String, var value: String, val onChanged: (String) -> Unit, override val isEnabled: Boolean = true) : SettingItem(isEnabled)
    data class Nav(val title: String, val subtitle: String, val onClick: () -> Unit, override val isEnabled: Boolean = true) : SettingItem(isEnabled)
}

class SettingsAdapter(
    private var items: List<SettingItem>,
    private val onItemClick: (SettingItem, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun setItems(newItems: List<SettingItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SettingItem.Header -> 0
        is SettingItem.Toggle -> 1
        else -> 3
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(ItemSettingHeaderBinding.inflate(inflater, parent, false))
            1 -> ToggleVH(ItemSettingToggleBinding.inflate(inflater, parent, false))
            else -> NavVH(ItemSettingNavBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        
        holder.itemView.alpha = if (item.isEnabled) 1.0f else 0.4f
        holder.itemView.setOnClickListener { if (item.isEnabled) onItemClick(item, position) }

        applyCardBackground(holder, position)

        when (item) {
            is SettingItem.Header -> (holder as HeaderVH).binding.headerTitle.text = item.title
            is SettingItem.Toggle -> {
                val h = holder as ToggleVH
                h.binding.itemTitle.text = item.title
                h.binding.itemSubtitle.text = item.subtitle
                h.binding.itemToggle.isChecked = item.value
                h.binding.itemToggle.isEnabled = item.isEnabled
            }
            is SettingItem.Picker -> {
                val h = holder as NavVH
                h.binding.itemTitle.text = item.title
                h.binding.itemSubtitle.text = item.subtitle
                h.binding.itemValue.text = item.options.getOrNull(item.currentSelection) ?: ""
            }
            is SettingItem.TextInput -> {
                val h = holder as NavVH
                h.binding.itemTitle.text = item.title
                h.binding.itemSubtitle.text = item.subtitle
                h.binding.itemValue.text = item.value
            }
            is SettingItem.Nav -> {
                val h = holder as NavVH
                h.binding.itemTitle.text = item.title
                h.binding.itemSubtitle.text = item.subtitle
                h.binding.itemValue.text = ""
            }
        }
    }

    private fun applyCardBackground(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (item is SettingItem.Header) {
            holder.itemView.background = null
            return
        }

        val context = holder.itemView.context
        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(android.graphics.Color.parseColor("#333333"))

        val radius = 16f * context.resources.displayMetrics.density
        val isFirst = position == 0 || items[position - 1] is SettingItem.Header
        val isLast = position == items.size - 1 || items[position + 1] is SettingItem.Header

        when {
            isFirst && isLast -> bg.cornerRadius = radius
            isFirst -> bg.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            isLast -> bg.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            else -> {}
        }

        holder.itemView.background = bg
        val params = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
        params.setMargins(
            (16 * context.resources.displayMetrics.density).toInt(),
            if (isFirst) (8 * context.resources.displayMetrics.density).toInt() else 0,
            (16 * context.resources.displayMetrics.density).toInt(),
            if (isLast) (8 * context.resources.displayMetrics.density).toInt() else 0
        )
        holder.itemView.layoutParams = params
    }

    override fun getItemCount() = items.size

    class HeaderVH(val binding: ItemSettingHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class ToggleVH(val binding: ItemSettingToggleBinding) : RecyclerView.ViewHolder(binding.root)
    class NavVH(val binding: ItemSettingNavBinding) : RecyclerView.ViewHolder(binding.root)
}
