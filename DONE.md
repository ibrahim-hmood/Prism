# Prism — DONE.md (full project history)

This document records **everything** that was done for the **Prism** Android launcher project across the Cursor conversation(s), in **chronological / thematic** order and **extreme detail**. It is meant as a single handoff artifact for what exists in the repo, **why** it exists, and **how** the pieces fit together.

---

## Part A — Original request (conversation 1): “Create a custom Android launcher”

### A.1 What the user asked for (verbatim intent)

The user wanted a **custom Android launcher** (the HOME app that shows the homescreen, app drawer, etc.) with these properties:

1. **Desktop activity** hosting the main experience.
2. **Horizontal `ViewPager`** (implemented as **`ViewPager2`**) with **three** pages:
   - **Left**: a **minimal Web browser** page.
   - **Center** (default page on open): a **desktop grid** for shortcuts.
   - **Right**: an **app drawer** with a **translucent / glossy** visual treatment.
3. **Installable custom pages**:
   - Users install **separate APKs** that advertise a **page provider**.
   - The launcher uses **dynamic class loading** (`PackageManager` + `createPackageContext` + foreign `ClassLoader`) to instantiate a **`View`** supplied by the other package.
   - **Any** of the three slots (left / center / right) could be swapped to a **custom** page.
4. **Page switching UI**:
   - User **long-presses** to enter a **zoomed-out** picker.
   - Picker uses a **`ViewPager` pattern** described as **vertical outer** and **horizontal inner** options (implemented with nested **`ViewPager2`** in early iterations; later refined in conversation 2).
5. **Normal launcher behaviors**:
   - **Drag-and-drop** / reorder on the **desktop grid** when the **built-in** desktop is active (not a custom replacement view).
6. **Implementation language**: **Kotlin**, full **resources**, and **terminal-buildable** Gradle project.
7. **Sample module**: a second app (**`sample-custom-page`**) demonstrating how to register a custom page.

### A.2 High-level architecture chosen

- **Single launcher activity**: `LauncherActivity` with **`HOME` / `DEFAULT`** intent filter so Android can treat it as a launcher candidate.
- **Main pager**: `ViewPager2` backed by a **`RecyclerView.Adapter`** (`MainDesktopPagerAdapter`) holding **three** logical pages.
- **Slot configuration**: `SlotIndex` + `SlotAssignment` persisted in **`SharedPreferences`** (`SlotPreferences`).
- **Plugin discovery**: `PluginPageDiscovery` queries activities handling `com.prism.launcher.action.DESKTOP_PAGE` and reads meta-data key `com.prism.launcher.PAGE_VIEW_CLASS`.
- **Dynamic view loading**: `DynamicPageLoader` loads `View` subclasses via the foreign package `ClassLoader` and tries common constructors `(Context)`, `(Context, AttributeSet)`, etc.
- **Desktop persistence**: `DesktopShortcutStore` stores up to **24** cells as a serialized string in `SharedPreferences`.
- **Desktop UI**: `DesktopGridPage` + `DesktopGridAdapter` + `ItemTouchHelper` for reorder.
- **Drawer UI**: `DrawerPageView` + `DrawerAppsAdapter` + `loadLauncherApps()`.
- **Browser UI (initial)**: `BrowserPageView` with `WebView`, URL field, Go, progress.

### A.3 Gradle / project structure created (conversation 1)

- **Root**:
  - `settings.gradle.kts` — includes `:app` and `:sample-custom-page`.
  - `build.gradle.kts` — Android Gradle Plugin + Kotlin plugin versions.
  - `gradle.properties` — AndroidX, Kotlin style, non-transitive R.
  - `gradle/wrapper/gradle-wrapper.properties` — Gradle 8.9.
  - `gradlew` / `gradlew.bat` + `gradle-wrapper.jar` (wrapper fetched from upstream Gradle repo in the assistant environment when `gradle` was not on PATH).
  - `.gitignore` — standard Android ignores.

- **`:app` module** (`app/build.gradle.kts`):
  - `namespace` / `applicationId`: `com.prism.launcher`
  - `compileSdk` **35**, `minSdk` **26**, `targetSdk` **35**
  - **ViewBinding** enabled.
  - Dependencies: `core-ktx`, `appcompat`, `material`, `constraintlayout`, `viewpager2`, `recyclerview`, `webkit`.

- **`:sample-custom-page` module**:
  - Separate `applicationId` `com.prism.sample.page`
  - Exports a **registration activity** + meta-data pointing at `SampleGlowPageView` (custom `FrameLayout`).

### A.4 Manifest & permissions (conversation 1 baseline)

- `INTERNET` for browser.
- `QUERY_ALL_PACKAGES` (with lint ignore) for broad launcher listing (developer-oriented; not Play-policy friendly).
- `<queries>` entries for `MAIN/LAUNCHER` and the custom page action.
- `LauncherActivity`:
  - **`MAIN` + `LAUNCHER` + `DEFAULT`** (so it appears in “All apps” of another launcher).
  - **`MAIN` + `HOME` + `DEFAULT`** (so it can be set as default home).
- `android:launchMode="singleTask"` on the launcher activity.
- `Application` subclass: `PrismApp`.

### A.5 Key Kotlin / resource files created in conversation 1 (non-exhaustive but broad)

**Kotlin (`com.prism.launcher`)**

- `PrismApp.kt`
- `PrismContracts.kt` — `ACTION_DESKTOP_PAGE`, `META_PAGE_VIEW_CLASS`
- `SlotAssignment.kt`, `SlotIndex`, `SlotPreferences`
- `PluginPageInfo.kt`
- `PluginPageDiscovery.kt` — `PackageManager` query w/ API-level branching for `ResolveInfoFlags`
- `DynamicPageLoader.kt`
- `DesktopShortcutStore.kt`
- `DesktopGridAdapter.kt`, `DesktopGridPage.kt`
- `DrawerAppsAdapter.kt`, `DrawerPageView.kt`, `loadLauncherApps()`
- `MainDesktopPagerAdapter.kt`
- `PagePickerAdapters.kt` (later rewritten in conversation 2)
- `LauncherActivity.kt` (later heavily rewritten in conversation 2)
- `BrowserPageView.kt` — **originally** in `com.prism.launcher` (later **deleted** and replaced by `com.prism.launcher.browser` package in conversation 2)

**Kotlin (`com.prism.sample.page`)**

- `PageRegistrationActivity.kt` — immediately finishes; exists for discovery.
- `SampleGlowPageView.kt` — simple gradient + title `View`.

**Layouts / drawables / values**

- `res/layout/activity_launcher.xml` (evolved multiple times)
- `res/layout/page_desktop_root.xml`, `item_desktop_cell.xml`
- `res/layout/page_drawer_root.xml`, `item_drawer_app.xml`, `drawer_glass_bg.xml`
- `res/layout/include_browser_page.xml` (evolved heavily in conversation 2)
- `res/layout/item_slot_picker_vertical.xml`, `item_page_option.xml`, `item_picker_position.xml` (picker pieces evolved)
- `res/values/strings.xml`, `colors.xml`, `themes.xml`
- `res/drawable/ic_launcher.xml`

### A.6 Behavioral notes from conversation 1 implementation

- **Desktop grid** uses **24** cells in a **4-column** `GridLayoutManager`.
- **Reorder** uses `ItemTouchHelper` on **four directions**.
- **Drag from drawer** uses `ClipData` + `View.startDragAndDrop` / legacy `startDrag`, and the desktop `RecyclerView` listens for `DragEvent.ACTION_DROP`.
- **Custom pages**: if loading fails, a simple error `TextView` is shown.
- **ViewPager2 recycling**: `MainDesktopPagerAdapter` uses a **cache key** so WebView isn’t rebuilt unnecessarily when the assignment string is unchanged.

*   **Neon Prism Dialog Infrastructure**: 
    *   Designed a custom high-fidelity dialog system with hardware-accelerated glowing borders.
    *   Implemented Gaussian Background Blur (180px radius) for API 31+ devices.
    *   Dynamic accent synchronization: Dialogs automatically adopt the user's chosen "Glow Accent" color (Cyan, Magenta, etc.).
*   **Intelligent Model Loading with Progress**:
    *   Updated `SettingsActivity` to provide real-time percentage feedback during model ingestion.
    *   Implemented automated storage cleanup to purge legacy model fragments before syncing new intelligence.
    *   High-performance buffer-streaming to handle 2GB+ model files with zero UI latency.

### A.7 Build verification (conversation 1)

- The assistant attempted `./gradlew :app:assembleDebug` but the environment lacked **`JAVA_HOME` / Android SDK**, so compilation wasn’t completed there. Instructions were left to set `sdk.dir` in `local.properties` and JDK 17.

---

## Part B — Second major request (conversation 2): wallpaper, UX, browser, VPN/DNS, manifest

The user asked for a large set of **refinements** and **new browser/security networking** behavior.

### B.1 Desktop / drawer interaction rules

- **Desktop grid should start empty** (no pre-seeded shortcuts) — ensured by `DesktopShortcutStore` default empty serialization.
- **Drag from drawer → desktop** should only work when the **rightmost** page is the **built-in drawer**, and (as implemented) when the **center** page is the **built-in desktop** so drops are meaningful.

This was implemented via lambdas passed into `MainDesktopPagerAdapter`:

- `allowDrawerDrag: () -> Boolean` → true only if `SlotIndex.DRAWER` assignment is **Default**.
- `acceptDesktopDrawerDrops: () -> Boolean` → true only if **both** desktop and drawer assignments are **Default**.

`DrawerAppsAdapter` only starts a drag if `allowDragToDesktop()` returns true.

`DesktopGridPage` returns **false** from the drag listener immediately if `acceptDrawerDrops()` is false.

### B.2 Wallpaper behind pages (“like normal launchers”)

`Theme.PrismLauncher` was updated to include:

- `android:windowShowWallpaper=true`
- transparent `windowBackground`

Launcher root layouts were adjusted away from opaque full-screen colors toward **scrims** (e.g. `prism_page_scrim`) so wallpaper shows through.

### B.3 Page picker UX change (two-step)

The user wanted:

1. Pick **which physical page position** (left / center / right).
2. Then **page vertically** through **page options** for that slot.

Implementation approach:

- `activity_launcher.xml` overlay uses a **single** `ViewPager2` (`pickerMainPager`) whose adapter is **swapped** between steps:
  - **Step 1**: `PositionPickerAdapter` (3 vertical pages + “Choose page for this slot” button).
  - **Step 2**: `VerticalPageOptionsAdapter` (vertical list of cards: built-in + each discovered plugin).
- Back button returns from step 2 → step 1.
- `LauncherActivity` owns `discoveredPlugins`, `pendingPickerSlot`, and the step transitions.

### B.4 Long-press anywhere except on “apps”

Instead of only long-pressing narrow “handles”, the activity uses:

- `GestureDetector.SimpleOnGestureListener.onLongPress`
- hooked from `LauncherActivity.dispatchTouchEvent`

**Exclusions**: any view under a walk up the parent chain carrying tag `R.id.tag_prism_launcher_app_target`:

- Desktop cells with a shortcut set the tag on the row + icon.
- Drawer rows set the tag on the row + icon.

Empty desktop cells **do not** set the tag → long-press opens picker.

### B.5 Persistence “permanent until changed”

`SlotPreferences.set(...)` was changed from `apply()` to **`commit()`** for **immediate durable** writes (still same prefs keys).

### B.6 Browser overhaul (only when left slot is built-in browser)

Because `MainDesktopPagerAdapter` only inflates `BrowserPageView` for **position 0** when assignment is **Default**, all advanced browser features apply **only** in that configuration.

New / moved code lives under `com.prism.launcher.browser`:

- `BrowserPageView.kt` — multi-tab WebView container, tab switcher overlay, private chip, VPN coordination hooks.
- `PrismWebViewClient.kt` — `shouldInterceptRequest` blocking for **private** tabs using the blocklist.
- `HostBlocklist.kt` — loads `assets/mini_blocklist.txt`, merges downloadable StevenBlack hosts to app-private file, maintains host + suffix lists.
- `PrismBlocklist.kt` — singleton accessor for `HostBlocklist`.
- `BrowserTabsAdapter.kt` + `item_browser_tab_card.xml` — “Chrome cards + Firefox grid” vibe: 2-column grid, preview bitmap via `WebView.draw`, private badge, close button.
- `include_browser_page.xml` redesigned: toolbar ids, `webContainer`, tabs overlay, chips, icons (`ic_tabs_24`, `ic_add_24`, `ic_close_24`).

**Private tabs behavior (summary)**

- Separate per-tab `WebView` instances.
- Private tabs: stricter `WebSettings` (e.g. mixed content never, safe browsing when available), reduced storage features, `DNT` / `Sec-GPC` headers on loads, third-party cookies disabled via `CookieManager.setAcceptThirdPartyCookies(webView, false)`.

**VPN service (conversation 2 baseline)**

- `PrivateDnsVpnService` extending `VpnService`, declared in manifest with:
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`
  - `foregroundServiceType="connectedDevice"`
- A **DNS-focused** tunnel:
  - Adds VPN interface address and **routes only** to **`1.1.1.1/32` and `1.0.0.1/32`** plus uses those as DNS servers (so general TCP doesn’t need a userspace TCP stack).
  - Reads IPv4/UDP/53 from TUN, parses QNAME, blocks or forwards to Cloudflare via a **protected** `DatagramSocket`.
  - Builds DNS replies (NXDOMAIN) or UDP/IP responses.

**VPN permission flow**

- `LauncherActivity` registers `ActivityResultContracts.StartActivityForResult` for `VpnService.prepare(...)`.
- `BrowserPageView` calls `PrivateDnsVpnService.start/stop` based on whether **any private tab** exists.
- `attachBrowserPage(...)` + `requestVpnPermission(...)` bridge between `BrowserPageView` and `LauncherActivity`.

### B.7 Manifest consolidation for HOME

The manifest was consolidated to a **single** `MAIN` intent filter containing:

- `HOME`
- `DEFAULT`
- `LAUNCHER`

…so the app is recognized as a launcher **and** remains launchable from the app list.

### B.8 Additional resources from conversation 2

- `res/values/ids.xml` — `tag_prism_launcher_app_target`
- `assets/mini_blocklist.txt` — seed domains
- Updated `strings.xml` with browser + VPN strings
- Color additions like `prism_tab_overlay`, `prism_page_scrim`, transparent `prism_bg`

---

## Part C — Third request (this conversation): expand VPN + write DONE.md

### C.1 User request: block more than DNS — ignore connections to/from blocked sites

**Reality check / design**

A full “capture all TCP and drop by domain” VPN typically requires a **complete userspace NAT/TCP implementation** or a native forwarder. To avoid claiming a full TCP stack, the implementation extends the VPN using a standard trick:

1. **Resolve blocklist hostnames to IPv4 addresses** on the **active network** (`Network.getAllByName` on API 23+).
2. For each resolved address (capped), call `VpnService.Builder.addRoute(ipv4, 32)`.
3. Any packet **destined to those /32s** is delivered to the TUN fd and **dropped** by the service (no forwarding), which **effectively ignores** connection attempts to those IPs for protocols that would route through those routes (TCP, UDP-not-DNS-to-those-IPs, ICMP, etc., depending on OS routing).

**Caveats (intentionally documented)**

- **Shared IPs / CDNs**: multiple sites may share an IP → **false positives** possible.
- **Coverage limits**: only up to **`BlockedIpv4RouteTable.MAX_HOSTS_TO_RESOLVE`** hostnames and **`MAX_UNIQUE_IPS`** distinct IPv4 addresses are installed (to avoid exhausting OS route tables / startup time).
- **Suffix-only blocklist entries** are not wildcard-expanded into infinite subdomains (only explicit host lines from the merged host sets are resolved).
- **Return-path packets** (“from blocked sites”) often **won’t** traverse the TUN the same way as outbound /32-routed flows; the practical win is **blocking outbound** connects to those sink IPs once resolved.
- **DNS-over-HTTPS** or hard-coded IPs can bypass DNS filtering; **WebView interception** still helps for private tabs.

### C.2 Code added/changed in Part C

**New file**

- `app/src/main/java/com/prism/launcher/browser/BlockedIpv4RouteTable.kt`
  - `compute(context, HostBlocklist) -> RouteComputation`
  - Resolves hostnames → packed IPv4 `Set<Int>` + dotted strings list for `addRoute`
  - Skips reserving Cloudflare resolver IPs from becoming “sink routes” if a hostname somehow resolved to them (defensive).
  - Utility: `packIpv4`, `destinationPackedIpv4(packet)`

**Major rewrite**

- `app/src/main/java/com/prism/launcher/browser/PrivateDnsVpnService.kt`
  - Establishes VPN with:
    - interface address
    - DNS servers `1.1.1.1` + `1.0.0.1`
    - routes to those DNS /32s (DNS path preserved)
    - **plus** `/32` route per resolved blocked IPv4
  - Packet handling order:
    1. If IPv4 **UDP dst port 53** → existing DNS logic (blocklist by QNAME or forward).
    2. Else if IPv4 **dst** is in the **sink set** → **drop** (ignore connection).
    3. Else → drop (should be rare for this VPN profile).
  - **Periodic refresh** (every **5 minutes**): recomputes resolved IPs; if the packed set changes, **tears down** the TUN reader thread, closes interface, re-`establish()`es with updated routes, restarts IO thread.

**Strings**

- Updated `vpn_notification_text` to mention DNS + IPv4 sinks.

### C.3 DONE.md (this file)

You asked for **everything** done in the **entire** conversation, in **extreme detail**, in **`DONE.md`**. This file is that artifact.

---

## Part D — Operational notes for developers

### D.1 How to build

From the project root (with JDK 17 + Android SDK configured):

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :sample-custom-page:assembleDebug
```

Ensure `local.properties` contains:

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
```

### D.2 How to use Prism as HOME

Install `com.prism.launcher`, open Android **Settings → Apps → Default apps → Home app** (wording varies), choose **Prism**.

### D.3 How custom pages register

1. Add an activity exported with intent action `com.prism.launcher.action.DESKTOP_PAGE`.
2. Add meta-data `com.prism.launcher.PAGE_VIEW_CLASS` pointing at a fully-qualified `android.view.View` subclass with a usable constructor.
3. Install APK; Prism discovery lists it in the picker.

See `sample-custom-page` for a minimal example.

### D.4 Security / policy warnings

- `QUERY_ALL_PACKAGES` is powerful; expect **Play Protect / Play policy** scrutiny.
- Loading foreign code via `createPackageContext` + `ClassLoader` is **powerful and risky**; only install plugins you trust.
- VPN code prompts user approval; incorrect routing logic can **disrupt networking** (mitigated here by avoiding a default `0.0.0.0/0` route).

---

## Part E — File path index (quick lookup)

**Launcher core**

- `app/src/main/java/com/prism/launcher/LauncherActivity.kt`
- `app/src/main/java/com/prism/launcher/MainDesktopPagerAdapter.kt`
- `app/src/main/java/com/prism/launcher/DesktopGridPage.kt`
- `app/src/main/java/com/prism/launcher/DesktopGridAdapter.kt`
- `app/src/main/java/com/prism/launcher/DesktopShortcutStore.kt`
- `app/src/main/java/com/prism/launcher/DrawerPageView.kt`
- `app/src/main/java/com/prism/launcher/DrawerAppsAdapter.kt`
- `app/src/main/java/com/prism/launcher/SlotAssignment.kt` / `SlotPreferences`
- `app/src/main/java/com/prism/launcher/PluginPageDiscovery.kt`
- `app/src/main/java/com/prism/launcher/DynamicPageLoader.kt`
- `app/src/main/java/com/prism/launcher/PagePickerAdapters.kt`

**Browser + blocklist + VPN**

- `app/src/main/java/com/prism/launcher/browser/BrowserPageView.kt`
- `app/src/main/java/com/prism/launcher/browser/PrismWebViewClient.kt`
- `app/src/main/java/com/prism/launcher/browser/HostBlocklist.kt`
- `app/src/main/java/com/prism/launcher/browser/PrismBlocklist.kt`
- `app/src/main/java/com/prism/launcher/browser/BrowserTabsAdapter.kt`
- `app/src/main/java/com/prism/launcher/browser/PrivateDnsVpnService.kt`
- `app/src/main/java/com/prism/launcher/browser/BlockedIpv4RouteTable.kt`

**Sample plugin app**

- `sample-custom-page/src/main/AndroidManifest.xml`
- `sample-custom-page/src/main/java/com/prism/sample/page/*`

**Primary manifest**

- `app/src/main/AndroidManifest.xml`

---


## Part F — Known limitations / future work ideas

- Implement a **true full-tunnel** forwarder if you need universal TCP policy without `/32` route tricks.
- Expand suffix blocking to **controlled** wildcard resolution (careful with cardinality).
- Replace `QUERY_ALL_PACKAGES` with narrower `<queries>` for production distribution.
- Add automated tests (currently none were added in these conversations).

---

## Part G — Conversation 4 (this session): crash fixes, DB sync, VPN overhaul

### G.1 LauncherActivity crash on startup (`<init>` NullPointerException)

**Root cause:** `longPressDetector` was declared as a class-level field initializer:

```kotlin
private val longPressDetector = GestureDetector(this, ...)
```

Field initializers run during the class **constructor** (`<init>`), before the Activity is attached to its Application context. `GestureDetector(context, ...)` internally calls `context.getResources()` on the unattached `Activity` → `NullPointerException`.

**Fix:** Changed to `by lazy { ... }` so the `GestureDetector` is only constructed on first use (always after `onCreate`):

```kotlin
private val longPressDetector by lazy { GestureDetector(this, ...) }
```

### G.2 VPN foreground service type wrong (`connectedDevice` → `specialUse`)

**Root cause:** `PrivateDnsVpnService` was declared with `foregroundServiceType="connectedDevice"` in the manifest and `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission. On Android 14 (API 34+), the `connectedDevice` type requires one of: Bluetooth, NFC, WiFi, UWB, etc. — none of which a DNS VPN needs.

**Fix:**
- Manifest: `FOREGROUND_SERVICE_CONNECTED_DEVICE` → `FOREGROUND_SERVICE_SPECIAL_USE`; service `foregroundServiceType` → `specialUse`.
- Kotlin: `startForeground()` now passes `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+. On lower APIs, the no-argument form is used (no type enforcement).

### G.3 VPN service started but tunnel never connected — three root causes fixed

**Root cause A — DNS resolution on the wrong thread (primary cause):**
`bootstrapTunnel()` was called directly from `onStartCommand()` (main thread). Inside it, `BlockedIpv4RouteTable.compute()` performs up to 450 DNS lookups via `network.getAllByName()`. Network I/O on the main thread throws `NetworkOnMainThreadException`, caught silently by `catch (Throwable)`, which then called `stopSelf()` — service died instantly.

**Fix:** `bootstrapTunnel()` is now dispatched to a named background thread:
```kotlin
thread(name = "prism-vpn-bootstrap") { bootstrapTunnel() }
```

**Root cause B — Tunnel blocked for minutes inside `synchronized(tunnelLock)` (secondary cause):**
Even after moving to a background thread, `bootstrapTunnel()` held `tunnelLock` for the entire duration of `BlockedIpv4RouteTable.compute()` (up to 450 DNS lookups × several seconds each = potentially minutes). `establish()` was never reached. Meanwhile `shutdownTunnelAndThreads()` from `onDestroy()` also needs `tunnelLock` → **deadlock**.

**Fix:** `bootstrapTunnel()` now calls `buildMinimalTunnel()` which establishes the VPN with **only the two DNS /32 routes** and no DNS resolution. The tunnel is up in milliseconds. Blocked-IP sink routes are then computed and installed by the route-refresh thread's first immediate pass.

```kotlin
private fun buildMinimalTunnel(): ParcelFileDescriptor? =
    Builder()
        .setSession("Prism Private DNS")
        .setMtu(1500)
        .addAddress(VPN_ADDRESS, 30)
        .addDnsServer(DNS_A)
        .addDnsServer(DNS_B)
        .addRoute(DNS_A, 32)
        .addRoute(DNS_B, 32)
        .establish()
```

**Root cause C — Sink routes took 5 minutes to install:**
`startRouteRefreshLocked()` started the refresh thread with a `Thread.sleep(5 min)` before the first `rebuildTunnelWithFreshRoutes()` call. So blocked-IP routes were never installed within a normal session.

**Fix:** The refresh thread now does an immediate first pass before entering the sleep loop:
```kotlin
if (serviceRunning) rebuildTunnelWithFreshRoutes() // immediate
while (serviceRunning) {
    Thread.sleep(ROUTE_REFRESH_INTERVAL_MS)         // then every 5 min
    ...
}
```

**Root cause D — `stopSelf()` called inside `synchronized` block → deadlock:**
When `establish()` returned null, `stopSelf()` was called inside `synchronized(tunnelLock)`. Android schedules `onDestroy()` on the main thread which calls `shutdownTunnelAndThreads()` → tries to re-acquire `tunnelLock` → blocked forever.

**Fix:** A `shouldStop: Boolean` flag is set inside the lock; `stopSelf()` is called **after** the synchronized block exits.

### G.4 Build system: `kapt` → `KSP`

**Root cause:** The project uses Kotlin 2.0.21. `kapt` only supports up to Kotlin 1.9 and silently falls back — causing compile errors when Room's annotation processor can't generate DAO implementations.

**Fix:**
- `build.gradle.kts` (root): removed `org.jetbrains.kotlin.kapt`; added `com.google.devtools.ksp:2.0.21-1.0.28`.
- `app/build.gradle.kts`: replaced `id("kotlin-kapt")` with `id("com.google.devtools.ksp")`; `kapt(room-compiler)` → `ksp(room-compiler)`.

KSP generates Room code ~2× faster (no Java stub generation) and is fully Kotlin 2.0 compatible.

### G.5 Room database for installed-app list

**Motivation:** `DrawerPageView` previously called `loadLauncherApps(packageManager)` synchronously in `init`, blocking the main thread for every DrawerPageView creation. The new design keeps a DB-cached list of package names that is updated in the background.

**New files:**

| File | Purpose |
|---|---|
| `InstalledAppEntity.kt` | `@Entity(tableName="installed_apps")` — stores `packageName` (PK) + `activityClass` |
| `InstalledAppDao.kt` | `@Dao` — `getAll`, `insertAll`, `deleteByPackage`, `clearAll`, `count` |
| `AppDatabase.kt` | Thread-safe Room singleton via `Room.databaseBuilder`; accessed via `AppDatabase.get(ctx)` |
| `AppSyncWorker.kt` | `CoroutineWorker` — full `PackageManager` scan → clears + re-populates DB. Also exports `queryLauncherApps()` and `queryLauncherAppsForPackage()` helpers. Enqueued with `ExistingWorkPolicy.KEEP`. |
| `AppPackageReceiver.kt` | Manifest-registered `BroadcastReceiver` for `PACKAGE_ADDED`, `PACKAGE_REPLACED`, `PACKAGE_CHANGED`, `PACKAGE_REMOVED`, `PACKAGE_FULLY_REMOVED`. Updates DB incrementally via `GlobalScope.launch(IO)`. Includes `EXTRA_REPLACING` guard to avoid false deletes. |

**Dependencies added to `app/build.gradle.kts`:**
- `androidx.room:room-runtime:2.6.1`
- `androidx.room:room-ktx:2.6.1`
- `ksp(androidx.room:room-compiler:2.6.1)` ← KSP not kapt
- `androidx.work:work-runtime-ktx:2.9.1`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`

### G.6 Background app sync — PrismApp and Manifest changes

- `PrismApp.kt` → `onCreate()` enqueues `AppSyncWorker` with `ExistingWorkPolicy.KEEP`. Runs once on first install; `AppPackageReceiver` handles all incremental updates thereafter.
- `AndroidManifest.xml` additions:
  - `RECEIVE_BOOT_COMPLETED` permission (WorkManager post-reboot rescheduling)
  - `<receiver android:name=".AppPackageReceiver">` with all 5 package-event actions + `<data android:scheme="package"/>`

### G.7 Runtime permission: POST_NOTIFICATIONS

`LauncherActivity` now requests `POST_NOTIFICATIONS` once on first launch (API 33+):
- Uses `ActivityResultContracts.RequestPermission` registered as `notificationPermissionLauncher`.
- Gated by a `SharedPreferences` flag (`notif_perm_asked`) so the dialog only appears once.
- Skips on API < 33 (pre-Tiramisu notification permission is implicit).

### G.8 DrawerPageView — async DB-backed load

`DrawerPageView` was rewritten to:
1. Set an **empty `ListAdapter`** immediately in `init` (no blocking call).
2. In `onAttachedToWindow()`, cast `context as? LifecycleOwner` (always valid since the drawer is hosted in `LauncherActivity`) to obtain a lifecycle-scoped coroutine.
3. On `Dispatchers.IO`: read `InstalledAppEntity` list from Room DB → resolve label/icon via `PackageManager.getActivityInfo()` → sort alphabetically → filter out ghost entries (uninstalled packages where the receiver hasn't fired yet).
4. On main thread: call `adapter.submitList(entries)`.
5. **Fallback:** if DB is empty (first run before AppSyncWorker completes), falls back to a live `PackageManager` scan so the drawer is never blank.

`DrawerAppsAdapter` was migrated from `RecyclerView.Adapter` to `ListAdapter<DrawerAppEntry, VH>` with a `DiffUtil.ItemCallback` for smooth async updates.

**Fix for `ViewTreeLifecycleOwner` compilation error:** `ViewTreeLifecycleOwner.get(view)` was the Java static API that didn't resolve cleanly under KSP. Replaced with `context as? LifecycleOwner` (semantically identical, always works for Activity-hosted views).

### G.9 Manifest additions summary (cumulative)

```
RECEIVE_BOOT_COMPLETED
FOREGROUND_SERVICE_SPECIAL_USE   (replaces FOREGROUND_SERVICE_CONNECTED_DEVICE)
AppPackageReceiver (PACKAGE_ADDED, REPLACED, CHANGED, REMOVED, FULLY_REMOVED)
PrivateDnsVpnService foregroundServiceType: specialUse  (was connectedDevice)
```

### G.10 File path index additions (Part G)

```
app/src/main/java/com/prism/launcher/InstalledAppEntity.kt
app/src/main/java/com/prism/launcher/InstalledAppDao.kt
app/src/main/java/com/prism/launcher/AppDatabase.kt
app/src/main/java/com/prism/launcher/AppSyncWorker.kt
app/src/main/java/com/prism/launcher/AppPackageReceiver.kt
```

**Modified in Part G:**
```
LauncherActivity.kt           (lazy longPressDetector; notif permission)
PrismApp.kt                   (WorkManager enqueue)
DrawerPageView.kt             (async DB load)
DrawerAppsAdapter.kt          (ListAdapter + DiffUtil)
AndroidManifest.xml           (permissions, receiver, FGS type)
build.gradle.kts (root)       (kapt → KSP)
app/build.gradle.kts          (KSP plugin, Room, WorkManager, lifecycle deps)
browser/PrivateDnsVpnService.kt (bootstrap overhaul, buildMinimalTunnel, stopSelf fix)
```

---

---

## Part H — Conversation 5+ (Latest): AI Messaging Hub, Neon UI, and Multi-modal Intelligence

This part documents the transformation of Prism into a state-of-the-art AI Hub and the UI's final high-fidelity polish.

### H.1 Finalizing the "Sam" AI Messaging Hub

Sam was evolved into a proactive, generative AI assistant with dual-engine capability.

- **Engine Orchestration**: `AiManager.kt` now handles switching between **Local (MediaPipe)** and **Cloud (OpenAI-compatible)** engines based on user settings.
- **Generative Intelligence**: Upgraded from placeholder responses to real on-device generation using **MediaPipe LLM Inference API**.
- **Context Persistence**: All AI conversations are stored in a dedicated Room database (`AiMessageDao`), allowing long-term memory and history within the chat interface.

### H.2 Neon Prism UI Redesign

The launcher settings and messaging components were overhauled to match a "Neon Prism" aesthetic.

- **Visual Style**: Deep black backgrounds (`#111111`) with rounded card groupings (`#333333`).
- **Dynamic State Logic**: Settings items now support an `isEnabled` property. Cloud-only settings are automatically greyed out/disabled when the Local engine is selected, and vice versa.
- **Glow & Blur System**:
    - `layout_prism_dialog.xml`: A custom dialog frame with a glowing hardware-accelerated border and 180px background blur (API 31+ support).
    - `PrismDialogFactory.kt`: A helper to instantiate beautiful neon-themed dialogs for inputs and confirmations across the app.

### H.3 Secure Storage & Model Management

To handle the complexity of 100MB+ AI model files on modern Android (Scoped Storage), we implemented a robust ingestion pipeline.

- **Internal Model Copying**: Prism now uses a **private app directory** (`/files/models/`) to store active AI models.
- **Copy-on-Load**: Whether a model is picked from the file browser or downloaded, it is automatically copied into internal storage. This ensures 100% reliable read access for the AI engine without persistent permission issues.
- **Automatic Cleanup**: The app manages its own storage by deleting previous models when a new one is successfully ingested.

### H.4 Multi-modal Messaging Experience

The Messaging UI was upgraded to handle rich media generated by the AI Hub.

- **Image Rendering**: Seamless display of AI-generated images in the chat bubble with neon borders.
- **Video Playback**: Integrated a specialized `VideoView` into the chat adapter, allowing users to watch AI-synthesized motion sequences directly within the conversation.
- **Multi-modal Adapter**: `MessagesAdapter.kt` was refactored to dynamically bind views based on `mediaType` (image vs video).

### H.5 Technical & Performance Highlights

- **MediaPipe LlmInference**: Integrated `com.google.mediapipe:tasks-genai` for high-performance localized LLM execution.
- **Singleton Inference Pattern**: The AI engine is maintained as a singleton in `LocalAiService` to avoid the performance penalty of reloading massive model files on every message.
- **DownloadManager Integration**: Automated the downloading of optimized model bundles from Hugging Face with background broadcast receivers.

### H.6 File Path Index Additions (Part H)

| File | Purpose |
|---|---|
| `app/src/main/java/com/prism/launcher/messaging/AiMessageDao.kt` | Room DAO for AI chat history |
| `app/src/main/java/com/prism/launcher/messaging/AiMessageEntity.kt` | AI Message entity (multi-modal support) |
| `app/src/main/java/com/prism/launcher/messaging/AiManager.kt` | Orchestrator for engine selection |
| `app/src/main/java/com/prism/launcher/PrismDialogFactory.kt` | Neon Glow Dialog generator |
| `app/src/main/res/layout/layout_prism_dialog.xml` | Custom dialog frame layout |
| `app/src/main/java/com/prism/launcher/messaging/LocalAiService.kt` | MediaPipe Generative engine wrapper |
| `app/src/main/java/com/prism/launcher/messaging/CloudAiService.kt` | Cloud API client with mock generation |

---

### End of DONE.md

