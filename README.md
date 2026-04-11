# Prism

Welcome to the swiss army knife of android launchers. Prism is a high-performance, modular Android launcher built with Kotlin and Jetpack. It features a unique three-pane architecture, dynamic plugin support for custom pages, and a privacy-focused built-in browser with a DNS-based VPN.

Prism transforms the traditional Android interface into a customizable, AI-integrated workspace with a high-fidelity aesthetic.

🚀 Key Features
1. Three-Pane Desktop Architecture
The core experience is built on a horizontal ViewPager2 consisting of three distinct logical zones:
Left (The Hub): A built-in minimal web browser with multi-tab support and VPN integration.
Center (The Desktop): A 4-column grid for app shortcuts with support for reordering and drag-and-drop.
Right (The Drawer): A translucent, "glassy" app drawer with asynchronous loading and intelligent caching.

2. Modular Plugin System
Prism allows users to swap any of the three slots with custom pages provided by separate APKs.
Dynamic Loading: Uses PackageManager and foreign ClassLoader to instantiate views at runtime.
Discovery: Automatically detects third-party apps advertising the com.prism.launcher.action.DESKTOP_PAGE intent.

3. Integrated Privacy Browser & VPN
A custom browser engine designed for privacy-conscious users:
Private DNS VPN: A DNS-focused tunnel routing to Cloudflare (1.1.1.1) to filter trackers and malware.
Outbound Sink-Hole: Automatically resolves blocklist hostnames to IPv4 addresses and drops outbound packets to those IPs at the routing level.
Stealth Mode: Private tabs with DNT (Do Not Track) headers, disabled third-party cookies, and strict storage policies.

4. AI Messaging Hub ("Sam")
A proactive AI assistant integrated directly into the launcher:
Dual Engine: Switch between On-Device (MediaPipe) for offline privacy and Cloud (OpenAI) for advanced reasoning.
Contextual Memory: Persistent chat history stored via Room DB.
Neon UI: High-fidelity interface featuring hardware-accelerated glowing borders and Gaussian background blur.

# 🛠 Technical Architecture
Data & Persistence
Room Database: Handles the installed app list, AI message history, and blocklist caching.
WorkManager: Manages background synchronization of the app list and AI model ingestion.
SharedPreferences: Stores slot assignments, desktop grid layouts (serialized), and user preferences.
Networking & Security
VpnService: Implements a local TUN interface to intercept UDP/53 packets.
BlockedIpv4RouteTable: A specialized utility that translates domain-based blocklists into /32 IP routes for low-level packet dropping.

# Project Structure
:app: The primary launcher module.
:sample-custom-page: A reference implementation for developers to build their own Prism plugins.

# 📦 Build Instructions
Prerequisites
JDK 17
Android SDK (API 35)
KSP (Kotlin Symbol Processing) enabled.

# Compilation
Set your sdk.dir in local.properties.

Run the following commands:

Bash
# Build the main launcher
./gradlew :app:assembleDebug

# Build the sample plugin
./gradlew :sample-custom-page:assembleDebug

# 🛠 Developer Setup: Custom Pages
To create a custom page for Prism, your app must:
(1) Register an Activity in your AndroidManifest.xml with the intent filter:
com.prism.launcher.action.DESKTOP_PAGE.
(2) Add Metadata specifying your View class:
<meta-data android:name="com.prism.launcher.PAGE_VIEW_CLASS" 
           android:value="com.your.package.YourCustomView" />
(3) Implement a Constructor in your View that accepts (Context) or (Context, AttributeSet).

#⚠️ Known Limitations
VPN False Positives: Because the VPN blocks by IP resolution, some sites sharing CDNs with blocked domains may be inadvertently unreachable.

# Query Permissions: Uses QUERY_ALL_PACKAGES to function as a system-wide launcher; requires specific declarations for Play Store distribution.