package com.prism.launcher

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.prism.launcher.messaging.ModelDiscoveryService
import kotlinx.coroutines.withContext

class SettingsActivity : PrismBaseActivity() {

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

    private val fontPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            copyFontToInternal(uri)
        }
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val fileName = uri.lastPathSegment ?: "external_model.tflite"
            copyUriToInternal(uri, fileName, isPickingImageModel)
        }
    }
    
    private var isPickingImageModel = false

    private fun copyFontToInternal(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val file = java.io.File(filesDir, "custom_font.ttf")
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        PrismSettings.setCustomFontPath(this@SettingsActivity, file.absolutePath)
                        adapter.setItems(buildItems())
                        Toast.makeText(this@SettingsActivity, "Custom Font Applied", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val vpnProfilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val contents = stream.reader().readText()
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            PrismSettings.setExternalVpnProfile(this@SettingsActivity, contents)
                            adapter.setItems(buildItems())
                            Toast.makeText(this@SettingsActivity, "External VPN Profile Loaded", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "Failed to load profile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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
                    
                    // Route to correct ingestion loop based on type
                    val isImage = fileName.contains("SD", ignoreCase = true) || fileName.contains("Diffusion", ignoreCase = true)
                    copyUriToInternal(localUri, fileName, isImage)
                    
                    Toast.makeText(this, "AI Model downloaded successfully!", Toast.LENGTH_LONG).show()
                } else if (status == DownloadManager.STATUS_FAILED) {
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    if (reason == 401 || reason == 403) {
                        PrismDialogFactory.show(
                            this,
                            "Access Denied",
                            "The download failed because the source requires authentication (Error $reason). Would you like to search for a working mirror?",
                            onPositive = { startModelDiscovery() },
                            positiveText = "Search Web",
                            negativeText = "Dismiss"
                        )
                    } else {
                        Toast.makeText(this, "Download failed (Reason: $reason)", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun copyUriToInternal(uri: Uri, fileName: String, isImageModel: Boolean = false) {
        val modelsDir = java.io.File(filesDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val targetFile = java.io.File(modelsDir, fileName)
        
        val progressDialog = PrismDialogFactory.show(
            this,
            "Ingesting Intelligence",
            "Optimizing $fileName for Prism...",
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
                    if (isImageModel) {
                        PrismSettings.setLocalImageModelPath(this@SettingsActivity, targetFile.absolutePath)
                    } else {
                        PrismSettings.setLocalAiModelPath(this@SettingsActivity, targetFile.absolutePath)
                    }
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
        PrismDialogFactory.show(
            this,
            "Model Type",
            "What kind of intelligence are you importing?",
            onPositive = {
                isPickingImageModel = false
                modelPicker.launch("*/*")
            },
            positiveText = "Text AI",
            onNegative = {
                isPickingImageModel = true
                modelPicker.launch("*/*")
            },
            negativeText = "Image Gen"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Toolbar
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false) // Use custom TextView
        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        // Setup Theme Toggle
        val currentMode = PrismSettings.getThemeMode(this)
        binding.themeToggle.setImageResource(
            if (currentMode == PrismSettings.THEME_LIGHT) R.drawable.ic_theme_moon 
            else R.drawable.ic_theme_sun
        )
        binding.themeToggle.setOnClickListener {
            val nextMode = if (currentMode == PrismSettings.THEME_LIGHT) PrismSettings.THEME_DARK else PrismSettings.THEME_LIGHT
            PrismSettings.setThemeMode(this, nextMode)
            
            // Restart with fade
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            startActivity(intent)
        }

        // Setup RecyclerView
        adapter = SettingsAdapter(buildItems(), this::onItemClick)
        binding.settingsList.layoutManager = LinearLayoutManager(this)
        binding.settingsList.adapter = adapter
    }

    private fun getLocalIpAddress(): String {
        return MeshUtils.getLocalMeshIp(this)
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

            SettingItem.Header("Launcher Aesthetic"),
            SettingItem.Picker(
                "Icon Pack",
                "Choose the appearance of app icons",
                listOf("System Default") + IconPackEngine.getAvailableIconPacks(this).map { it.first },
                run {
                    val currentPkg = PrismSettings.getIconPackPackage(this)
                    val packs = IconPackEngine.getAvailableIconPacks(this)
                    val idx = packs.indexOfFirst { it.second == currentPkg }
                    if (idx == -1) 0 else idx + 1
                },
                { idx ->
                    if (idx == 0) {
                        PrismSettings.setIconPackPackage(this, "")
                    } else {
                        val packs = IconPackEngine.getAvailableIconPacks(this)
                        PrismSettings.setIconPackPackage(this, packs[idx - 1].second)
                    }
                    adapter.setItems(buildItems())
                }
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
                "Enable VPN Tunneling",
                "Route traffic through Prism or an external VPN",
                PrismSettings.getVpnTunnelingEnabled(this),
                { 
                    PrismSettings.setVpnTunnelingEnabled(this, it) 
                    adapter.setItems(buildItems())
                }
            ),
            SettingItem.Toggle(
                "VPN auto-start",
                "Automatically connect VPN when a private tab opens",
                PrismSettings.getVpnAutoStart(this),
                { PrismSettings.setVpnAutoStart(this, it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this)
            ),
            SettingItem.Picker(
                "VPN Mode",
                "Choose Prism P2P VPN or an external provider",
                listOf("Prism VPN", "External VPN"),
                if (PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_EXTERNAL) 1 else 0,
                {
                    PrismSettings.setVpnMode(this, if (it == 1) PrismSettings.VPN_MODE_EXTERNAL else PrismSettings.VPN_MODE_PRISM)
                    adapter.setItems(buildItems())
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this)
            ),
            SettingItem.Toggle(
                "Persistent VPN Server",
                "Keep Prism Server running even outside of private browsing (Backbone mode)",
                PrismSettings.getVpnServerAlwaysOn(this),
                { 
                    PrismSettings.setVpnServerAlwaysOn(this, it) 
                    adapter.setItems(buildItems())
                    // Start or let service re-evaluate
                    com.prism.launcher.browser.PrivateDnsVpnService.start(this)
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_PRISM
            ),
            SettingItem.Picker(
                "Prism VPN Role",
                "Serve as a node or connect as a client",
                listOf("Client", "Server"),
                if (PrismSettings.getPrismVpnRole(this) == PrismSettings.PRISM_ROLE_SERVER) 1 else 0,
                {
                    PrismSettings.setPrismVpnRole(this, if (it == 1) PrismSettings.PRISM_ROLE_SERVER else PrismSettings.PRISM_ROLE_CLIENT)
                    adapter.setItems(buildItems())
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_PRISM
            ),
            SettingItem.Picker(
                "VPN Protocol",
                "Choose protocol or let Prism auto-detect",
                listOf("Automatic (Detected)", "IKEv2", "L2TP", "Proxy Only"),
                when (PrismSettings.getVpnProtocolMode(this)) {
                    PrismSettings.VPN_PROTOCOL_IKEV2 -> 1
                    PrismSettings.VPN_PROTOCOL_L2TP -> 2
                    PrismSettings.VPN_PROTOCOL_PROXY -> 3
                    else -> 0
                },
                { idx ->
                    val mode = when(idx) {
                        1 -> PrismSettings.VPN_PROTOCOL_IKEV2
                        2 -> PrismSettings.VPN_PROTOCOL_L2TP
                        3 -> PrismSettings.VPN_PROTOCOL_PROXY
                        else -> PrismSettings.VPN_PROTOCOL_AUTO
                    }
                    PrismSettings.setVpnProtocolMode(this, mode)
                    adapter.setItems(buildItems())
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole(this) == PrismSettings.PRISM_ROLE_SERVER
            ),
            SettingItem.Nav(
                "Device IP Address",
                getLocalIpAddress(),
                {},
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole(this) == PrismSettings.PRISM_ROLE_SERVER
            ),
            SettingItem.TextInput(
                "Server Port",
                "Port to accept P2P nodes (Default 8080)",
                PrismSettings.getPrismVpnPort(this),
                { PrismSettings.setPrismVpnPort(this, it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole(this) == PrismSettings.PRISM_ROLE_SERVER
            ),
            SettingItem.TextInput(
                "Proxy Auth Password",
                "(Optional) Set Password for incoming clients",
                PrismSettings.getPrismVpnPassword(this),
                { PrismSettings.setPrismVpnPassword(this, it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole(this) == PrismSettings.PRISM_ROLE_SERVER
            ),
            SettingItem.TextInput(
                "Proxy Auth Username",
                "(Optional) Set Username for incoming clients",
                PrismSettings.getPrismVpnUsername(this),
                { PrismSettings.setPrismVpnUsername(this, it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole(this) == PrismSettings.PRISM_ROLE_SERVER
            ),
            SettingItem.Nav(
                "Manage Prism Servers",
                "${PrismSettings.getPrismServers(this).size} servers saved (Auto-failover active)",
                { showServerFleetManager() },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole(this) == PrismSettings.PRISM_ROLE_CLIENT
            ),
            SettingItem.Header("Mesh Bootstrap Server"),
            SettingItem.TextInput(
                "Bootstrap Address",
                "Primary entry point for P2P DNS & Mesh search",
                PrismSettings.getMeshBootstrapAddress(this),
                { PrismSettings.setMeshBootstrapAddress(this, it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole(this) == PrismSettings.PRISM_ROLE_CLIENT
            ),
            SettingItem.TextInput(
                "Bootstrap Port",
                "Port of the bootstrap node (Default 8081)",
                PrismSettings.getMeshBootstrapPort(this),
                { PrismSettings.setMeshBootstrapPort(this, it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_PRISM && PrismSettings.getPrismVpnRole(this) == PrismSettings.PRISM_ROLE_CLIENT
            ),
            SettingItem.Nav(
                "Configure External VPN",
                if (PrismSettings.getExternalVpnProfile(this).isEmpty()) "Setup WireGuard profile (.conf)" else "WireGuard Profile Loaded",
                {
                    vpnProfilePicker.launch("*/*")
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this) && PrismSettings.getVpnMode(this) == PrismSettings.VPN_MODE_EXTERNAL
            ),
            SettingItem.Nav(
                "App Whitelists",
                "Select apps to bypass the VPN tunnel",
                {
                    startActivity(android.content.Intent(this@SettingsActivity, com.prism.launcher.vpn.WhitelistActivity::class.java))
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this)
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
                { PrismSettings.setPrimaryDns(this, it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this)
            ),
            SettingItem.TextInput(
                "Secondary DNS",
                "Used by the private VPN tunnel",
                PrismSettings.getSecondaryDns(this),
                { PrismSettings.setSecondaryDns(this, it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this)
            ),

            SettingItem.Header("Native VPN Server (WireGuard)"),
            SettingItem.TextInput(
                "WireGuard Listen Port",
                "Port for direct VPN connections (Default 51820)",
                PrismSettings.getWgServerPort(this).toString(),
                { 
                    val p = it.toIntOrNull() ?: 51820
                    PrismSettings.setWgServerPort(this, p) 
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this)
            ),
            SettingItem.TextInput(
                "Allowed IPs",
                "Traffic to route through VPN (e.g. 0.0.0.0/0 for everything)",
                PrismSettings.getWgAllowedIps(this),
                { PrismSettings.setWgAllowedIps(this, it) },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this)
            ),
            SettingItem.Nav(
                "Copy Client Config",
                "Generate .conf for Windows WireGuard app",
                {
                    val config = PrismSettings.generateWgClientConfig(this)
                        .replace("YOUR_PHONE_IP_HERE", getLocalIpAddress())
                    
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Prism WireGuard", config))
                    
                    PrismDialogFactory.show(this, "Config Copied", "Paste this into a new tunnel in your Windows WireGuard app. \n\nNOTE: Replace 'CLIENT_PRIVATE_KEY_HERE' in the config with your own generated key.")
                },
                isEnabled = PrismSettings.getVpnTunnelingEnabled(this)
            ),

            SettingItem.Header("Intelligence & Messaging"),
            SettingItem.Picker(
                "Prism AI Engine",
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
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_CLOUD,
                isSingleLine = true,
                isEncoded = true
            ),
            SettingItem.TextInput(
                "Cloud Base URL",
                "Endpoint root (must be OpenAI compatible)",
                PrismSettings.getCloudAiBaseUrl(this),
                { PrismSettings.setCloudAiBaseUrl(this, it) },
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_CLOUD,
                isSingleLine = true
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
                "Select a .task or .bin LLM from storage",
                { pickLocalModel() },
                isEnabled = PrismSettings.getAiMode(this) == PrismSettings.AI_MODE_LOCAL
            ),
            
            SettingItem.Header("Available LLM Models"),
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

            SettingItem.Header("Visual Intelligence (Diffusion)"),
            SettingItem.Nav(
                "Search for Models",
                "Find working Stable Diffusion mirrors on Hugging Face",
                { startModelDiscovery() }
            ),
            SettingItem.Nav(
                "Stable Diffusion v1.5",
                "Generate realistic images locally (~2GB RAM needed)",
                { downloadModel("SD-1.5", PrismSettings.MODEL_SD_1_5_CPU) },
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
            ),
            SettingItem.Header("Decentralized Name System"),
            SettingItem.Nav(
                "Manage P2P DNS",
                "View and edit domain mappings in the mesh ledger",
                {
                    startActivity(android.content.Intent(this, com.prism.launcher.browser.P2pDnsActivity::class.java))
                }
            ),
            SettingItem.Nav(
                "P2P Web Hosting",
                "Host local folders as websites on the mesh",
                {
                    startActivity(android.content.Intent(this, com.prism.launcher.browser.P2pHostingActivity::class.java))
                }
            ),
            SettingItem.Nav(
                "System Diagnostics",
                "Live terminal console and error logs",
                {
                    startActivity(android.content.Intent(this, DiagnosticsActivity::class.java))
                }
            ),

            SettingItem.Header("Typography"),
            SettingItem.Picker(
                "Font Style",
                "Choose the default app & browser font",
                listOf("System Default", "Nasalization (Modern)", "Custom File (.ttf)"),
                when(PrismSettings.getFontStyle(this)) {
                    PrismSettings.FONT_STYLE_NASALIZATION -> 1
                    PrismSettings.FONT_STYLE_CUSTOM -> 2
                    else -> 0
                },
                { idx ->
                    val style = when(idx) {
                        1 -> PrismSettings.FONT_STYLE_NASALIZATION
                        2 -> PrismSettings.FONT_STYLE_CUSTOM
                        else -> PrismSettings.FONT_STYLE_DEFAULT
                    }
                    PrismSettings.setFontStyle(this, style)
                    // If Custom is selected but no path exists, prompt to pick
                    if (style == PrismSettings.FONT_STYLE_CUSTOM && PrismSettings.getCustomFontPath(this).isEmpty()) {
                        fontPicker.launch("*/*")
                    } else {
                        Toast.makeText(this, "Restart app to fully apply fonts", Toast.LENGTH_SHORT).show()
                    }
                }
            ),
            SettingItem.Nav(
                "Select Custom Font",
                "Load a .ttf or .otf file from storage",
                { fontPicker.launch("*/*") },
                isEnabled = PrismSettings.getFontStyle(this) == PrismSettings.FONT_STYLE_CUSTOM
            )
        )
    }

    private fun buildSlotPickers(): Array<SettingItem> {
        val prefs = SlotPreferences(this)
        val assignments = prefs.getAssignments()
        val options = listOf("Browser", "Desktop Grid", "App Drawer", "Messaging", "Nebula Social", "Kinetic Halo", "File Explorer")
        
        return assignments.mapIndexed { index, current ->
            val currentIdx = when(current) {
                SlotAssignment.Browser -> 0
                SlotAssignment.DesktopGrid -> 1
                SlotAssignment.AppDrawer -> 2
                SlotAssignment.Messaging -> 3
                SlotAssignment.NebulaSocial -> 4
                SlotAssignment.KineticHalo -> 5
                SlotAssignment.FileExplorer -> 6
                else -> 1 // Default to Desktop Grid
            }
            
            SettingItem.Picker(
                "Page ${index + 1} Content",
                "Built-in page assigned to this slot",
                options,
                currentIdx,
                { idx ->
                    val assignment = when(idx) {
                        0 -> SlotAssignment.Browser
                        1 -> SlotAssignment.DesktopGrid
                        2 -> SlotAssignment.AppDrawer
                        3 -> SlotAssignment.Messaging
                        4 -> SlotAssignment.NebulaSocial
                        5 -> SlotAssignment.KineticHalo
                        6 -> SlotAssignment.FileExplorer
                        else -> SlotAssignment.Default
                    }
                    prefs.setAt(index, assignment)
                }
            )
        }.toTypedArray()
    }

    private fun onItemClick(item: SettingItem, position: Int) {
        when (item) {
            is SettingItem.Toggle -> {
                item.value = !item.value
                item.onChanged(item.value)
                adapter.setItems(buildItems())
            }
            is SettingItem.Picker -> {
                PrismDialogFactory.show(
                    this,
                    item.title,
                    "Choose an option:",
                    onPositive = {},
                    customView = android.widget.ListView(this).apply {
                        adapter = object : android.widget.ArrayAdapter<String>(this@SettingsActivity, android.R.layout.simple_list_item_single_choice, item.options) {
                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                                val v = super.getView(position, convertView, parent)
                                (v as? TextView)?.setTextColor(this@SettingsActivity.resolveAttr(com.prism.launcher.R.attr.prismTextPrimary))
                                return v
                            }
                        }
                        choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
                        setItemChecked(item.currentSelection, true)
                        setOnItemClickListener { _, _, which, _ ->
                            item.currentSelection = which
                            item.onChanged(which)
                            this@SettingsActivity.adapter.setItems(buildItems())
                        }
                    }
                )
            }
            is SettingItem.TextInput -> {
                val input = EditText(this).apply {
                    val p = (16 * resources.displayMetrics.density).toInt()
                    setPadding(p, p, p, p)
                    setTextColor(resolveAttr(R.attr.prismTextPrimary))
                    setHintTextColor(resolveAttr(R.attr.prismTextSecondary))
                    
                    if (item.isSingleLine) {
                        isSingleLine = true
                        maxLines = 1
                    }
                    if (item.isEncoded) {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    } else {
                        inputType = InputType.TYPE_CLASS_TEXT
                    }
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
                            this@SettingsActivity.adapter.setItems(buildItems())
                        }
                    },
                    customView = FrameLayout(this).apply {
                        val pad = (24 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        addView(input)
                    }
                )
            }
            is SettingItem.Nav -> {
                item.onClick()
            }
            is SettingItem.Header -> {} 
        }
    }

    private fun showServerFleetManager() {
        val servers = PrismSettings.getPrismServers(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val list = android.widget.ListView(this).apply {
            adapter = object : android.widget.BaseAdapter() {
                override fun getCount(): Int = servers.size
                override fun getItem(p0: Int) = servers[p0]
                override fun getItemId(p0: Int) = p0.toLong()
                override fun getView(idx: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                    val s = servers[idx]
                    val view = convertView ?: android.view.LayoutInflater.from(this@SettingsActivity).inflate(android.R.layout.simple_list_item_2, parent, false)
                    val t1 = view.findViewById<android.widget.TextView>(android.R.id.text1)
                    val t2 = view.findViewById<android.widget.TextView>(android.R.id.text2)
                    
                    t1.text = if (s.isActive) "● ${s.name} (ACTIVE)" else s.name
                    t1.setTextColor(if (s.isActive) PrismSettings.getGlowColor(this@SettingsActivity) else android.graphics.Color.WHITE)
                    t2.text = "${s.address}:${s.port} | User: ${s.username}"
                    t2.setTextColor(android.graphics.Color.GRAY)
                    
                    view.setOnClickListener {
                        servers.forEach { it.isActive = false }
                        s.isActive = true
                        PrismSettings.setPrismServers(this@SettingsActivity, servers)
                        this@SettingsActivity.adapter.setItems(buildItems())
                        showServerFleetManager() // Refresh
                    }
                    
                    view.setOnLongClickListener {
                        PrismDialogFactory.show(this@SettingsActivity, "Delete Server?", "Remove ${s.name} from your fleet?", onPositive = {
                            val newList = servers.toMutableList()
                            newList.removeAt(idx)
                            PrismSettings.setPrismServers(this@SettingsActivity, newList)
                            this@SettingsActivity.adapter.setItems(buildItems())
                            showServerFleetManager()
                        })
                        true
                    }
                    return view
                }
            }
        }
        
        container.addView(list, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 800))
        
        val addBtn = android.widget.Button(this).apply {
            text = "+ ADD PRISM SERVER"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(PrismSettings.getGlowColor(this@SettingsActivity))
            setOnClickListener { showAddServerDialog() }
        }
        container.addView(addBtn)

        PrismDialogFactory.show(this, "Prism Server Fleet", "Select active server or long-press to delete.", customView = container)
    }

    private fun showAddServerDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        
        val nameInput = EditText(this).apply { hint = "Server Name (e.g. Home Lab)" }
        val ipInput = EditText(this).apply { hint = "Target IP/Hostname" }
        val portInput = EditText(this).apply { hint = "Port (Default 8888)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val userInput = EditText(this).apply { hint = "Username" }
        val passInput = EditText(this).apply { hint = "Password"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        
        layout.addView(nameInput)
        layout.addView(ipInput)
        layout.addView(portInput)
        layout.addView(userInput)
        layout.addView(passInput)

        PrismDialogFactory.show(this, "Add Prism Server", "Enter your server credentials below.", onPositive = {
            val name = nameInput.text.toString().trim()
            val ip = ipInput.text.toString().trim()
            if (name.isNotEmpty() && ip.isNotEmpty()) {
                val servers = PrismSettings.getPrismServers(this).toMutableList()
                servers.add(PrismSettings.PrismServer(
                    name = name,
                    address = ip,
                    port = portInput.text.toString().toIntOrNull() ?: 8888,
                    username = userInput.text.toString(),
                    password = passInput.text.toString()
                ))
                PrismSettings.setPrismServers(this, servers)
                adapter.setItems(buildItems())
                showServerFleetManager()
            }
        }, customView = layout)
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

    private fun startModelDiscovery() {
        val categories = arrayOf("Generative", "Vision/Face", "Enhancement", "Unified (All)")
        val categoryIds = arrayOf("generative", "vision", "enhancement", "all")
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        
        val searchInput = EditText(this).apply { 
            hint = "Search query (e.g. flux, face, deep)" 
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        val categorySpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, categories)
        }
        
        layout.addView(android.widget.TextView(this).apply { 
            text = "AI Category"
            setTextColor(android.graphics.Color.WHITE)
        })
        layout.addView(categorySpinner)
        layout.addView(android.widget.TextView(this).apply { 
            text = "Search Term"
            setTextColor(android.graphics.Color.WHITE)
        })
        layout.addView(searchInput)

        PrismDialogFactory.show(this, "Discovery Engine", "Find AI models from HuggingFace, GitHub, and Google.", onPositive = {
            val query = searchInput.text.toString().trim().ifEmpty { "stable diffusion" }
            val category = categoryIds[categorySpinner.selectedItemPosition]
            performUniversalSearch(query, category)
        }, customView = layout, positiveText = "Launch Scan")
    }

    private fun performUniversalSearch(query: String, category: String) {
        val discoveryDialog = PrismDialogFactory.show(
            this, "Crawling Mesh", "Searching multiple sources for '$query'...", 
            showProgress = true, positiveText = null, negativeText = "Cancel"
        )
        
        lifecycleScope.launch {
            val models = ModelDiscoveryService.discoverAll(query, category)
            discoveryDialog.dismiss()
            
            if (models.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "No models found for '$query'.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val modelNames = models.map { model -> 
                "[${model.source}] ${model.name}\n${model.sizeLabel} • ${model.category.uppercase()}" 
            }.toTypedArray()
            
            val listView = android.widget.ListView(this@SettingsActivity).apply {
                adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_list_item_1, modelNames)
            }
            
            val selectionDialog = PrismDialogFactory.show(
                this@SettingsActivity,
                "Universal Mesh Discovery",
                "Select a model to ingestion:",
                customView = listView,
                positiveText = null
            )
            
            listView.setOnItemClickListener { _, _, position, _ ->
                val selected = models[position]
                selectionDialog.dismiss()
                downloadModel(selected.name, selected.downloadUrl)
            }
        }
    }
}

// ── Models & Adapter ────────────────────────────────────────────────────────

sealed class SettingItem(open val isEnabled: Boolean = true) {
    data class Header(val title: String) : SettingItem(true)
    data class Toggle(val title: String, val subtitle: String, var value: Boolean, val onChanged: (Boolean) -> Unit, override val isEnabled: Boolean = true) : SettingItem(isEnabled)
    data class Picker(val title: String, val subtitle: String, val options: List<String>, var currentSelection: Int, val onChanged: (Int) -> Unit, override val isEnabled: Boolean = true) : SettingItem(isEnabled)
    data class TextInput(val title: String, val subtitle: String, var value: String, val onChanged: (String) -> Unit, override val isEnabled: Boolean = true, val isSingleLine: Boolean = false, val isEncoded: Boolean = false) : SettingItem(isEnabled)
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
        
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(R.attr.prismCardColor, typedValue, true)
        val color = if (typedValue.type != android.util.TypedValue.TYPE_NULL) typedValue.data else android.graphics.Color.WHITE
        
        bg.setColor(color)

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
