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

4. AI Messaging Hub ("Sam") & Universal Theming
A proactive AI assistant and dynamic UI engine:
*   **Dual Engine**: Switch between On-Device (MediaPipe) for offline privacy and Cloud (OpenAI) for advanced reasoning.
*   **Universal Prism Theming**: A system-wide, attribute-based theme engine supporting Light, Dark, and System Auto modes.
*   **Sun/Moon Toggle**: A custom, high-fidelity theme switcher in Settings for manual mode control.
*   **Glassmorphism & Depth**: Uses Gaussian blurs, hardware-accelerated neon glows, and a "shaded-white" card layout to deliver a premium, modern aesthetic.

# 🛠 Technical Architecture

Prism is built as a highly decoupled, non-blocking environment. The networking and AI systems are designed to operate as global application singletons, ensuring state persistence regardless of the Activity lifecycle.

### Core Data & Persistence
*   **Room Database**: Manages the local P2P DNS ledger, AI message history, and the system-wide domain blocklist.
*   **WorkManager**: Orchestrates background peer synchronization and AI model ingestion.
*   **Encrypted SharedPreferences**: Handles mesh credentials, VPN configurations, and desktop layouts.
*   **Theme Persistence**: Centralized theme state management in `PrismSettings` with real-time Activity recreation.

### Networking & Decentralized DNS
*   **Mesh Proxy Backbone**: A global application-level singleton (`PrismTunnelEngine`) that maintains the P2P connectivity state. This allows for background content fetching and DNS resolution even when the browser is not in focus.
*   **Hardened SSL/TLS Mesh Layer**:
    *   **Identity Management**: Secure, pre-generated PKCS12 identities handled by `PrismSslManager`.
    *   **Crash Protection**: Background proxy threads are hardened with robust SSL handshake recovery, ensuring that failed peer connections never terminate the core Mesh service.
*   **Bi-Modal DNS Resolution**:
    1.  **Priority**: Look up within the decentralized P2P Mesh ledger.
    2.  **Fallback**: If a domain is "Mesh Unreachable", use a direct UDP query to the user-defined Primary DNS.
    3.  **Auto-Seeding**: Successful global lookups are automatically "seeded" back into the P2P mesh, crowdsourcing the global DNS table across all active Prism nodes.
*   **Universally Stabilized HTTPS**: 
    - Uses a custom `SSLSocketFactory` that **suppresses the SNI (Server Name Indication)** extension. 
    - This allows TLS handshakes to complete on custom TLDs (like `.p2p`) regardless of certificate availability on the server, eliminating the 15-second "silent drops" common in custom domain networking.
*   **VPN Tunneling**: Implements a local `TUN` interface via `VpnService` to intercept system-wide UDP/53 traffic.
*   **Packet-Level Interception**: Uses a `BlockedIpv4RouteTable` to translate domain blocklists into /32 IP routes, enabling zero-latency filtering at the kernel routing level.

### Integrated Browser Interception
*   **Standards-Compliant Rendering**: Uses a 6-argument `WebResourceResponse` to pass full HTTP semantics (Status Codes, Headers) from the P2P proxy back to the Chromium WebView, solving the "Raw HTML" display issue.
*   **Virtual Host Mapping**: Transparently handles URL mapping to internal mesh IPs while preserving the original `Host` headers for correct server-side routing.

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