package com.prism.launcher.agentic

import android.content.Context
import android.content.Intent
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prism-native tools that aren't user-imported -- they call into the launcher's own existing
 * capabilities directly. Always present, always enabled, shown in the Agentic Tools page marked
 * "Built-in" (testable, but not editable/deletable since there's no HTTP config to edit).
 */
object AgenticBuiltinTools {

    const val ID_LIST_APPS = "list_installed_apps"
    const val ID_LAUNCH_APP = "launch_app"
    const val ID_WEB_SEARCH = "web_search"
    const val ID_WEB_CRAWL = "web_crawl"
    const val ID_LIST_FILES = "list_all_files"
    const val ID_WRITE_FILE = "write_file"
    const val ID_MKDIR = "mkdir"
    const val ID_P2P_HOST = "p2p_host"

    private const val MAX_RESULT_CHARS = 4000
    private const val MAX_LISTED_ENTRIES = 200

    val ALL: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = ID_LIST_APPS,
            description = "Search the apps installed on this device by name. Returns matching app names and package IDs.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"query":{"type":"string","description":"Optional name filter; leave empty to list all apps"}}}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_LIST_APPS)
        ),
        ToolDefinition(
            name = ID_LAUNCH_APP,
            description = "Open/launch an installed app by its exact Android package name (as returned by $ID_LIST_APPS). Optionally takes a URI to deep-link straight to a specific screen, item, or search inside that app instead of opening it at its home screen. Deep linking works with any app that declares a handler for the URI, which most major apps do both for their own https:// web links and for any custom scheme they publish. With no URI the app simply opens normally.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"package_name":{"type":"string","description":"Exact package name, e.g. com.example.app"},"uri":{"type":"string","description":"Optional deep link to open inside the app. Use the app's own https:// content URL, or a custom scheme it publishes. Examples: 'https://www.youtube.com/watch?v=VIDEO_ID' to play a video, 'https://www.youtube.com/results?search_query=cats' to run a search, 'https://open.spotify.com/track/TRACK_ID', 'geo:0,0?q=Tokyo+Station'. Omit entirely to just open the app."}},"required":["package_name"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_LAUNCH_APP)
        ),
        ToolDefinition(
            name = ID_WEB_SEARCH,
            description = "Searches the web for a query using the search engine configured in Settings > Browser, and returns the text content of the results page. For reading a specific page you already have the URL for, use $ID_WEB_CRAWL instead.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"query":{"type":"string","description":"Search query, e.g. 'weather in Tokyo'"}},"required":["query"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_WEB_SEARCH)
        ),
        ToolDefinition(
            name = ID_WEB_CRAWL,
            description = "Fetches a single web page by URL and returns its visible text content with HTML markup stripped out. This is a direct page fetch, not a search -- the argument must be a full URL, not a search query.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"url":{"type":"string","description":"Full URL to fetch, e.g. https://example.com/page"}},"required":["url"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_WEB_CRAWL)
        ),
        ToolDefinition(
            name = ID_LIST_FILES,
            description = "Lists the files and folders directly inside a directory on device storage.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path, or a path relative to internal storage root (e.g. 'Download'). Leave empty to list the storage root."}}}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_LIST_FILES)
        ),
        ToolDefinition(
            name = ID_WRITE_FILE,
            description = "Writes text content to a file, creating the file and any missing parent directories if needed, or overwriting it if it already exists.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path, or a path relative to internal storage root, of the file to write"},"content":{"type":"string","description":"Text content to write to the file"}},"required":["path","content"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_WRITE_FILE)
        ),
        ToolDefinition(
            name = ID_MKDIR,
            description = "Creates a directory (and any missing parent directories) at the given path if it doesn't already exist. Does nothing if it already exists.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path, or a path relative to internal storage root, of the directory to create"}},"required":["path"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_MKDIR)
        ),
        ToolDefinition(
            name = ID_P2P_HOST,
            description = "Hosts a folder on Prism's peer-to-peer mesh under a chosen domain name -- the same thing Settings > P2P Hosting does -- so other Prism peers on the mesh can reach it.",
            parametersSchema = JSONObject(
                """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path, or a path relative to internal storage root, of the folder to host"},"domain":{"type":"string","description":"Domain name to claim on the mesh, e.g. myfiles.p2p"}},"required":["path","domain"]}"""
            ),
            executor = ToolExecutorConfig.Builtin(ID_P2P_HOST)
        )
    )

    suspend fun execute(context: Context, id: String, argumentsJson: String): String {
        val args = try { JSONObject(argumentsJson) } catch (e: Exception) { JSONObject() }
        return when (id) {
            ID_LIST_APPS -> listInstalledApps(context, args.optString("query", ""))
            ID_LAUNCH_APP -> launchApp(context, args.optString("package_name", ""), args.optString("uri", ""))
            ID_WEB_SEARCH -> performWebSearch(context, args.optString("query", ""))
            ID_WEB_CRAWL -> fetchWebpageText(args.optString("url", ""))
            ID_LIST_FILES -> listAllFiles(args.optString("path", ""))
            ID_WRITE_FILE -> writeFile(args.optString("path", ""), args.optString("content", ""))
            ID_MKDIR -> makeDirectory(args.optString("path", ""))
            ID_P2P_HOST -> p2pHostFolder(context, args.optString("path", ""), args.optString("domain", ""))
            else -> "Error: unknown built-in tool '$id'"
        }
    }

    private suspend fun listInstalledApps(context: Context, query: String): String {
        val pm = context.packageManager
        val entities = com.prism.launcher.AppDatabase.get(context).installedAppDao().getAll()
        val matches = entities.mapNotNull { entity ->
            try {
                val cn = android.content.ComponentName(entity.packageName, entity.activityClass)
                val label = pm.getActivityInfo(cn, 0).loadLabel(pm).toString()
                if (query.isBlank() || label.contains(query, ignoreCase = true)) {
                    "$label (${entity.packageName})"
                } else null
            } catch (e: Exception) {
                null
            }
        }.sorted().take(50)

        return if (matches.isEmpty()) "No installed apps matched." else matches.joinToString("\n")
    }

    /**
     * Opens an app, optionally deep-linking into a specific screen or item inside it.
     *
     * With no [uri] this is a plain launcher-style start. With one, it becomes an ACTION_VIEW
     * intent SCOPED TO THE PACKAGE -- `setPackage`, deliberately not `component`. The activity
     * that handles a deep link is almost never the launcher activity, so pinning the component
     * to [InstalledAppEntity.activityClass] the way the plain launch does would send the URI to
     * an activity with no filter for it and it would be ignored. Naming only the package lets
     * Android pick the right activity within the app while still guaranteeing the URI cannot be
     * routed anywhere else -- which matters when the URI came from a language model.
     *
     * Nothing here is app-specific. Deep linking works for any app that declares an intent
     * filter for the URI, which covers both custom schemes and -- via App Links -- an app's own
     * https:// URLs, the form most major apps actually publish.
     *
     * The fallback is deliberate rather than silent: if the app declares no handler, it opens
     * normally and the result SAYS the URI was not honoured. Reporting "launched" for a deep
     * link that did nothing would leave the model believing it had reached content it hadn't.
     */
    private suspend fun launchApp(context: Context, packageName: String, uri: String): String {
        if (packageName.isBlank()) return "Error: package_name is required."
        val entity = com.prism.launcher.AppDatabase.get(context).installedAppDao().getAll()
            .firstOrNull { it.packageName == packageName }
            ?: return "Error: no installed app with package name '$packageName'. Use $ID_LIST_APPS first to find the exact name."

        val cn = android.content.ComponentName(entity.packageName, entity.activityClass)
        val deepLink = uri.trim()

        if (deepLink.isEmpty()) {
            val error = startMain(context, cn)
            return if (error == null) "Launched $packageName." else "Error launching $packageName: $error"
        }

        rejectUnusableUri(deepLink)?.let { return it }

        val viewIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(deepLink)).apply {
            setPackage(entity.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val declaresHandler = try {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(viewIntent, 0) != null
        } catch (e: Exception) {
            false
        }

        if (declaresHandler) {
            val error = try {
                context.startActivity(viewIntent)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (error == null) return "Launched $packageName at $deepLink."

            // Resolved but wouldn't start -- an unexported activity, or a content:// URI the
            // target has no read grant for. Get the app open anyway and say what happened.
            val fallbackError = startMain(context, cn)
            return if (fallbackError == null) {
                "$packageName declares a handler for '$deepLink' but refused to open it " +
                    "($error), so the app opened at its default screen instead."
            } else {
                "Error: couldn't open '$deepLink' in $packageName ($error), and the plain " +
                    "launch also failed ($fallbackError)."
            }
        }

        val fallbackError = startMain(context, cn)
        return if (fallbackError == null) {
            "$packageName doesn't declare a handler for '$deepLink', so it opened at its " +
                "default screen instead. If that app supports a deep link to this content, it " +
                "uses a different URI form."
        } else {
            "Error: $packageName doesn't handle '$deepLink' and couldn't be launched either " +
                "($fallbackError)."
        }
    }

    /** Plain launcher-style start. Returns null on success, or the failure message. */
    private fun startMain(context: Context, component: android.content.ComponentName): String? = try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                this.component = component
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
        )
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /**
     * Screens out URI forms that cannot work here, before they turn into a confusing failure.
     *
     * The `intent:`/`android-app:` rejection is the one that matters. Those are not ordinary
     * data URIs -- they are a serialized Intent, and the whole point of them is to carry a
     * target component, action and extras of their own. Handing one to a model-driven tool would
     * turn "open this app at this URL" into "construct an arbitrary Intent", which is a much
     * larger capability than this tool is meant to grant and a well-known Android redirection
     * vector. They are refused rather than sanitized, because sanitizing a serialized Intent
     * correctly is not something to attempt.
     *
     * @return an error message to return to the model, or null if the URI is usable.
     */
    private fun rejectUnusableUri(deepLink: String): String? {
        val scheme = try {
            android.net.Uri.parse(deepLink).scheme?.lowercase()
        } catch (e: Exception) {
            null
        }
        return when {
            scheme.isNullOrEmpty() ->
                "Error: '$deepLink' isn't a usable URI -- it needs a scheme, e.g. " +
                    "https://example.com/thing or someapp://thing."
            scheme == "intent" || scheme == "android-app" ->
                "Error: intent:// and android-app:// URIs aren't accepted. They encode a whole " +
                    "Intent rather than just an address. Use the app's ordinary deep link " +
                    "instead -- usually its https:// content URL or its own custom scheme."
            scheme == "file" ->
                "Error: file:// URIs can't be handed to another app on modern Android. Use a " +
                    "content:// URI instead."
            else -> null
        }
    }

    /** `path` is absolute if it starts with "/", otherwise resolved relative to the external
     * storage root -- matches how a model would naturally refer to a path (e.g. "Download/x.txt"). */
    private fun resolvePath(path: String): File {
        val trimmed = path.trim()
        return if (trimmed.startsWith("/")) File(trimmed) else File(Environment.getExternalStorageDirectory(), trimmed)
    }

    /** Same permission [com.prism.launcher.files.FileExplorerPageView] requires for full-device
     * file access; null means access is available. */
    private fun requireStorageAccess(): String? {
        return if (!Environment.isExternalStorageManager())
            "Error: Prism doesn't have All Files Access yet. Grant it in Settings > Privacy > All Files Access, then try again."
        else null
    }

    private fun performWebSearch(context: Context, query: String): String {
        if (query.isBlank()) return "Error: query is required."
        val engine = com.prism.launcher.PrismSettings.getSearchEngine(context)
        // Settings > Browser's duckduckgo.com/?q= URL is a JS-rendered SPA shell that returns
        // almost nothing to a plain HTTP GET -- html.duckduckgo.com's "HTML" endpoint is the same
        // DuckDuckGo results, just server-rendered, so a text-strip actually gets real content.
        val url = if (engine == "ddg") {
            "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        } else {
            com.prism.launcher.PrismSettings.buildSearchUrl(context, query)
        }
        return fetchWebpageText(url)
    }

    private fun fetchWebpageText(url: String): String {
        if (url.isBlank()) return "Error: url is required."
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Prism/1.0)")
            }
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) return "Error: HTTP $responseCode fetching $url"
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val text = htmlToText(html)
            if (text.length > MAX_RESULT_CHARS) text.take(MAX_RESULT_CHARS) + "... (truncated)" else text
        } catch (e: Exception) {
            "Error fetching $url: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    /** No HTML parser dependency in this app -- a small regex-based strip is good enough to turn
     * a page into readable text for a model, without pulling in a library like Jsoup for one tool. */
    private fun htmlToText(html: String): String {
        val stripped = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</p>"), "\n\n")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        return stripped.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun listAllFiles(path: String): String {
        requireStorageAccess()?.let { return it }
        val dir = resolvePath(path)
        if (!dir.exists()) return "Error: path does not exist: ${dir.absolutePath}"
        if (!dir.isDirectory) return "Error: not a directory: ${dir.absolutePath}"
        val entries = dir.listFiles() ?: return "Error: could not list ${dir.absolutePath} (permission denied?)"
        if (entries.isEmpty()) return "${dir.absolutePath} is empty."

        val lines = entries.sortedBy { it.name }.take(MAX_LISTED_ENTRIES).map { f ->
            if (f.isDirectory) "${f.name}/" else "${f.name} (${f.length()} bytes)"
        }
        val remainder = entries.size - MAX_LISTED_ENTRIES
        val suffix = if (remainder > 0) "\n... ($remainder more not shown)" else ""
        return lines.joinToString("\n") + suffix
    }

    private fun writeFile(path: String, content: String): String {
        requireStorageAccess()?.let { return it }
        if (path.isBlank()) return "Error: path is required."
        val file = resolvePath(path)
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            "Wrote ${content.toByteArray().size} bytes to ${file.absolutePath}."
        } catch (e: Exception) {
            "Error writing ${file.absolutePath}: ${e.message}"
        }
    }

    private fun makeDirectory(path: String): String {
        requireStorageAccess()?.let { return it }
        if (path.isBlank()) return "Error: path is required."
        val dir = resolvePath(path)
        if (dir.exists()) {
            return if (dir.isDirectory) "${dir.absolutePath} already exists."
                   else "Error: ${dir.absolutePath} already exists and is a file, not a directory."
        }
        return if (dir.mkdirs()) "Created directory ${dir.absolutePath}." else "Error: failed to create ${dir.absolutePath}."
    }

    private fun p2pHostFolder(context: Context, path: String, domain: String): String {
        requireStorageAccess()?.let { return it }
        if (path.isBlank() || domain.isBlank()) return "Error: path and domain are required."
        val dir = resolvePath(path)
        if (!dir.exists() || !dir.isDirectory) return "Error: not a valid directory: ${dir.absolutePath}"

        val cleanDomain = domain.trim().lowercase()
        val sites = com.prism.launcher.PrismSettings.getP2pHostedSites(context).toMutableList()
        if (sites.any { it.domain == cleanDomain }) return "Error: '$cleanDomain' is already hosted locally."

        val myIp = com.prism.launcher.MeshUtils.getLocalMeshIp(context)
        val existingIp = com.prism.launcher.browser.P2pDnsManager.resolve(cleanDomain)
        if (existingIp != null && existingIp != myIp) {
            return "Error: '$cleanDomain' is already claimed by another peer ($existingIp)."
        }

        sites.add(com.prism.launcher.PrismSettings.P2pHostedSite(domain = cleanDomain, localPath = dir.absolutePath))
        com.prism.launcher.PrismSettings.setP2pHostedSites(context, sites)
        com.prism.launcher.browser.P2pDnsManager.updateRecord(context, cleanDomain, myIp)
        return "Now hosting ${dir.absolutePath} on the mesh as '$cleanDomain'."
    }
}
