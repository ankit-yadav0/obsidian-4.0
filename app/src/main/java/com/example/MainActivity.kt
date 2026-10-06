package com.example

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AppTheme
import com.example.ui.theme.ObsidianRed
import com.example.weblite.network.NetworkObserver
import com.example.weblite.privacy.BrowsingDataWiper
import com.example.weblite.ui.components.AppWebView
import com.example.weblite.ui.components.BrowserTopBar
import com.example.weblite.ui.components.DownloadsSheet
import com.example.weblite.ui.components.HomeScreen
import com.example.weblite.ui.components.OfflineBanner
import com.example.weblite.ui.components.OfflineErrorView
import com.example.weblite.ui.components.PrivacyDashboardSheet
import com.example.weblite.ui.components.SplashScreen
import com.example.weblite.ui.components.VpnRequiredOverlay
import com.example.weblite.viewmodel.MainViewModel
import com.example.weblite.vpn.VpnStatusMonitor

class MainActivity : ComponentActivity() {

    private data class PendingDownload(
        val url: String,
        val fileName: String,
        val mimeType: String,
        val userAgent: String?,
        val cookie: String?,
        val contentLength: Long
    )

    private val viewModel: MainViewModel by viewModels()
    private lateinit var networkObserver: NetworkObserver

    // File Chooser state for Web Chrome Client
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Fullscreen video view state
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null

    // Camera / microphone request from a page, waiting for the user's decision and the OS permission
    private var pendingPermissionRequest: PermissionRequest? = null
    private var permissionDialog: AlertDialog? = null

    // Download confirmation and (Android 9 and older) the storage permission it may need
    private var downloadDialog: AlertDialog? = null
    private var afterStoragePermission: (() -> Unit)? = null

    // Double back press exit logic
    private var backPressedTime: Long = 0

    // Reference to the live WebView (null while no page is shown)
    private var webViewRef: WebView? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (filePathCallback != null) {
                val results = if (result.resultCode == RESULT_OK) {
                    val intent = result.data
                    if (intent != null) {
                        val dataString = intent.dataString
                        val clipData = intent.clipData
                        if (clipData != null) {
                            val count = clipData.itemCount
                            Array(count) { i -> clipData.getItemAt(i).uri }
                        } else if (dataString != null) {
                            arrayOf(Uri.parse(dataString))
                        } else null
                    } else null
                } else null

                filePathCallback?.onReceiveValue(results)
                filePathCallback = null
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val request = pendingPermissionRequest ?: return@registerForActivityResult
            // Grants whichever of the requested resources the OS now allows; denies the request if none.
            finishPermissionRequest(request, grant = true)
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = afterStoragePermission
            afterStoragePermission = null
            if (granted && action != null) {
                action()
            } else if (!granted) {
                Toast.makeText(this, "Storage permission is needed to save downloads", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must come before anything creates a WebView: removes what an earlier run left on disk.
        // (If the app was force-stopped or killed, onDestroy never ran and nothing else would clean up.)
        BrowsingDataWiper.wipeStoredFilesOnColdStart(this)

        enableEdgeToEdge()

        // Privacy: block screenshots/screen recording and hide the page
        // preview in Android's recent-apps switcher.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        networkObserver = NetworkObserver(this)

        setContent {
            AppTheme(darkTheme = true) {
                MainContent()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainContent() {
        val context = LocalContext.current

        val currentUrl by viewModel.currentUrl.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        val loadingProgress by viewModel.loadingProgress.collectAsStateWithLifecycle()
        val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
        val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
        val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
        val showSplashScreen by viewModel.showSplashScreen.collectAsStateWithLifecycle()
        val isBannerOffline by viewModel.isBannerOffline.collectAsStateWithLifecycle()
        val downloads by viewModel.downloads.collectAsStateWithLifecycle()
        val showDownloadsSheet by viewModel.showDownloadsSheet.collectAsStateWithLifecycle()
        val tabs by viewModel.tabs.collectAsStateWithLifecycle()
        val showHomeScreen by viewModel.showHomeScreen.collectAsStateWithLifecycle()
        val allowedDomain by viewModel.activeAllowedDomain.collectAsStateWithLifecycle()
        val activeTabIsShieldOff by viewModel.activeTabIsShieldOff.collectAsStateWithLifecycle()
        val activeTabIsIncognito by viewModel.activeTabIsIncognito.collectAsStateWithLifecycle()
        val blockedEvents by viewModel.blockedEvents.collectAsStateWithLifecycle()
        var showPrivacyDashboard by remember { mutableStateOf(false) }
        val hiddenTabsUnlocked by viewModel.hiddenTabsUnlocked.collectAsStateWithLifecycle()
        val trackersBlockedCount by viewModel.trackersBlockedCount.collectAsStateWithLifecycle()
        val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
        val isActiveTabBookmarked = bookmarks.any { it.url == currentUrl }
        val torEnabled by viewModel.torEnabled.collectAsStateWithLifecycle()
        val torStatusMessage by viewModel.torStatusMessage.collectAsStateWithLifecycle()
        val protonVpnActive by viewModel.protonVpnActive.collectAsStateWithLifecycle()
        val protonInstalled by viewModel.protonVpnInstalled.collectAsStateWithLifecycle()
        val orbotInstalled by viewModel.orbotInstalled.collectAsStateWithLifecycle()
        val networkBlocked by viewModel.networkBlocked.collectAsStateWithLifecycle()
        val pinSet by viewModel.pinSet.collectAsStateWithLifecycle()
        val navRequest by viewModel.navRequest.collectAsStateWithLifecycle()
        val blockedNavigation by viewModel.blockedNavigation.collectAsStateWithLifecycle()
        val isAlwaysExternal by viewModel.isAlwaysExternalForCurrentUrl.collectAsStateWithLifecycle()
        val isOnline by networkObserver.isOnline.collectAsStateWithLifecycle()

        val snackbarHostState = remember { SnackbarHostState() }

        // Immersive full-screen: hide the status/navigation bars while
        // browsing a site, restore them on the URL-entry/tab-switcher screen.
        LaunchedEffect(showHomeScreen, showSplashScreen) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (!showHomeScreen && !showSplashScreen) {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }

        // Sync network observer with ViewModel & auto-refresh on restoration
        LaunchedEffect(isOnline) {
            val wasOffline = isOffline
            viewModel.updateNetworkState(isOnline)
            if (isOnline && wasOffline) {
                viewModel.refreshPage()
            }
        }

        // A link the user tapped was refused by the site lock: say so, and offer to open it anyway.
        LaunchedEffect(blockedNavigation) {
            val blocked = blockedNavigation ?: return@LaunchedEffect
            val result = snackbarHostState.showSnackbar(
                message = "Blocked a link to ${blocked.host}",
                actionLabel = "Open",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.openBlockedNavigation(blocked.url)
            }
            viewModel.consumeBlockedNavigation()
        }

        // Sites the user marked "always open externally" are handed over as soon as they are opened.
        LaunchedEffect(Unit) {
            viewModel.externalOpenEvents.collect { url -> openUrlExternally(url) }
        }

        var isCustomViewShowing by remember { mutableStateOf(false) }

        // Back Handler
        BackHandler {
            if (isCustomViewShowing && customViewCallback != null) {
                hideCustomView()
                isCustomViewShowing = false
            } else if (!showHomeScreen && webViewRef?.canGoBack() == true) {
                webViewRef?.goBack()
            } else if (!showHomeScreen) {
                // No more history in this tab - go to the tab switcher
                // instead of exiting the app immediately.
                viewModel.goHome()
            } else {
                if (backPressedTime + 2000 > System.currentTimeMillis()) {
                    finish()
                } else {
                    Toast.makeText(context, "Press back again to exit Obsidian", Toast.LENGTH_SHORT).show()
                    backPressedTime = System.currentTimeMillis()
                }
            }
        }

        // Cold start: show the splash first, before deciding home vs. browsing.
        if (showSplashScreen) {
            SplashScreen(modifier = Modifier.fillMaxSize())
            return
        }

        if (showHomeScreen) {
            Box(modifier = Modifier.fillMaxSize()) {
                HomeScreen(
                    openTabs = tabs,
                    hiddenTabsUnlocked = hiddenTabsUnlocked,
                    isPinSet = pinSet,
                    trackersBlockedCount = trackersBlockedCount,
                    bookmarks = bookmarks,
                    torEnabled = torEnabled,
                    torStatusMessage = torStatusMessage,
                    isOrbotInstalled = orbotInstalled,
                    onOpenUrl = { url -> viewModel.openNewTab(url) },
                    onOpenIncognito = { url -> viewModel.openNewTab(url, incognito = true) },
                    onOpenPrivacyDashboard = { showPrivacyDashboard = true },
                    onOpenBookmark = { url -> viewModel.openNewTab(url) },
                    onDeleteBookmark = { url -> viewModel.removeBookmark(url) },
                    onToggleTor = { enabled -> viewModel.toggleTor(enabled) },
                    onResumeTab = { id -> viewModel.switchToTab(id) },
                    onCloseTab = { id -> viewModel.closeTab(id) },
                    onHideTab = { id -> viewModel.hideTab(id) },
                    onUnhideTab = { id -> viewModel.unhideTab(id) },
                    onSetPin = { pin -> viewModel.setPin(pin) },
                    onUnlockAttempt = { pin -> viewModel.unlockHiddenTabs(pin) },
                    onForgotPin = { viewModel.resetPinAndDeleteHiddenTabs() },
                    onRelock = { viewModel.relockHiddenTabs() },
                    modifier = Modifier.fillMaxSize()
                )

                // Rendered on the home screen too, because that branch returns before the browsing Scaffold below.
                PrivacyDashboardSheet(
                    isVisible = showPrivacyDashboard,
                    trackersBlockedCount = trackersBlockedCount,
                    isProtonVpnActive = protonVpnActive,
                    isIncognitoActive = activeTabIsIncognito,
                    events = blockedEvents,
                    onDismiss = { showPrivacyDashboard = false }
                )
            }
            return
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0E15)),
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Main Container when not in fullscreen video
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!isCustomViewShowing) {
                        BrowserTopBar(
                            currentUrl = currentUrl,
                            isShieldOff = activeTabIsShieldOff,
                            isBookmarked = isActiveTabBookmarked,
                            isHttps = currentUrl.startsWith("https://"),
                            isAlwaysExternal = isAlwaysExternal,
                            isIncognito = activeTabIsIncognito,
                            onNavigate = { input -> viewModel.navigateActiveTab(input) },
                            onToggleShield = { viewModel.toggleShieldForActiveTab() },
                            onToggleBookmark = {
                                if (isActiveTabBookmarked) {
                                    viewModel.removeBookmark(currentUrl)
                                } else if (!viewModel.addBookmark(currentUrl, webViewRef?.title ?: currentUrl)) {
                                    Toast.makeText(
                                        context,
                                        "Bookmarks aren't saved from incognito or hidden tabs",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onOpenExternally = { openUrlExternally(currentUrl) },
                            onToggleAlwaysExternal = {
                                viewModel.toggleAlwaysExternalForCurrentUrl()
                                Toast.makeText(
                                    context,
                                    if (isAlwaysExternal) "Will open in-app again" else "Will always open this site in an external app",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onTabsClick = { viewModel.goHome() }
                        )
                    }

                    // Top Progress Bar
                    AnimatedVisibility(
                        visible = isLoading && loadingProgress < 100,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        LinearProgressIndicator(
                            progress = { loadingProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = ObsidianRed,
                            trackColor = Color.Transparent
                        )
                    }

                    // Persistent Non-Intrusive Offline Banner
                    OfflineBanner(
                        isOffline = isBannerOffline && !isOffline,
                        onRetry = {
                            if (networkObserver.isOnlineNow()) {
                                viewModel.refreshPage()
                            } else {
                                Toast.makeText(context, "Still offline. Retrying connection...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onViewDownloads = {
                            viewModel.toggleDownloadsSheet(true)
                        }
                    )

                    // Pull-to-refresh Wrapper
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            if (isOnline) {
                                viewModel.refreshPage()
                            } else {
                                viewModel.onRefreshHandled()
                                Toast.makeText(context, "Cannot refresh: Device is offline", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Main WebView View
                        AppWebView(
                            urlToLoad = currentUrl,
                            navRequest = navRequest,
                            isOnline = isOnline,
                            isRefreshing = isRefreshing,
                            networkBlocked = networkBlocked,
                            allowedDomain = allowedDomain,
                            shieldOff = activeTabIsShieldOff,
                            isIncognito = activeTabIsIncognito,
                            onNavRequestHandled = { viewModel.onNavRequestHandled() },
                            onPageStarted = { url -> viewModel.onPageStarted(url) },
                            onUrlChanged = { url -> viewModel.onUrlChanged(url) },
                            onProgressChanged = { progress -> viewModel.onProgressChanged(progress) },
                            onPageFinished = { url, _ ->
                                viewModel.onPageFinished(url)
                                viewModel.updateActiveTabMeta(url, webViewRef?.title ?: "")
                            },
                            onError = { errorMsg -> viewModel.onWebError(errorMsg) },
                            onShowFileChooser = { callback, params ->
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = callback

                                val intent = params?.createIntent()
                                try {
                                    if (intent != null) {
                                        fileChooserLauncher.launch(intent)
                                    } else {
                                        val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = "*/*"
                                        }
                                        fileChooserLauncher.launch(Intent.createChooser(fallbackIntent, "Select File"))
                                    }
                                } catch (e: Exception) {
                                    filePathCallback = null
                                    Toast.makeText(context, "Cannot open file chooser", Toast.LENGTH_SHORT).show()
                                    return@AppWebView false
                                }
                                true
                            },
                            onPermissionRequest = { request -> handleWebPermissionRequest(request) },
                            onPermissionRequestCanceled = { request -> cancelWebPermissionRequest(request) },
                            onShowCustomView = { view, callback ->
                                customView = view
                                customViewCallback = callback
                                isCustomViewShowing = true
                                showCustomView(view)
                            },
                            onHideCustomView = {
                                hideCustomView()
                                isCustomViewShowing = false
                            },
                            onWebViewChanged = { webView -> webViewRef = webView },
                            onDownloadRequested = { url, fileName, mimeType, userAgent, cookie, contentLength ->
                                confirmDownload(PendingDownload(url, fileName, mimeType, userAgent, cookie, contentLength))
                            },
                            onTrackerBlocked = { host, category -> viewModel.onTrackerBlocked(host, category) },
                            onNavigationBlocked = { url -> viewModel.onNavigationBlocked(url) },
                            shouldOpenExternally = { host -> viewModel.shouldOpenExternally(host) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Browsing is stopped while Proton VPN isn't detected as active. The WebView itself is also
                // paused and its traffic is black-holed (see NetworkPolicyManager), so this is a real
                // kill-switch and not just something drawn on top of a page that keeps loading.
                if (networkBlocked) {
                    VpnRequiredOverlay(
                        isProtonVpnInstalled = protonInstalled,
                        onOpenProtonVpn = {
                            val protonPackage = VpnStatusMonitor.PROTON_VPN_PACKAGE
                            val launchIntent = packageManager.getLaunchIntentForPackage(protonPackage)
                            if (launchIntent != null) {
                                startActivity(launchIntent)
                            } else {
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$protonPackage")))
                                } catch (e: Exception) {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$protonPackage")))
                                }
                            }
                        }
                    )
                }

                // Fullscreen Video Layout Container
                AndroidView(
                    factory = {
                        FrameLayout(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            visibility = View.GONE
                            fullscreenContainer = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Floating Action Button for Downloads & Offline Library
                if (!showSplashScreen && !isCustomViewShowing) {
                    FloatingActionButton(
                        onClick = { viewModel.toggleDownloadsSheet(true) },
                        containerColor = ObsidianRed,
                        contentColor = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Downloads & Offline Library"
                        )
                    }
                }

                // Offline Error Page Overlay
                OfflineErrorView(
                    isVisible = isOffline,
                    errorMessage = errorMessage,
                    onRetry = {
                        if (networkObserver.isOnlineNow()) {
                            viewModel.refreshPage()
                        } else {
                            Toast.makeText(context, "Still offline. Please check connection.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onViewDownloads = {
                        viewModel.toggleDownloadsSheet(true)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Downloads & Offline Library Bottom Sheet
                DownloadsSheet(
                    isVisible = showDownloadsSheet,
                    downloads = downloads,
                    onDismiss = { viewModel.toggleDownloadsSheet(false) },
                    onDelete = { item, deleteFile -> viewModel.deleteDownload(item, deleteFile) },
                    onRetry = { item ->
                        viewModel.retryDownload(item) { success, message ->
                            if (!success) {
                                Toast.makeText(context, message ?: "Couldn't restart the download", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )

                // Privacy Dashboard - proof of what's actually being blocked.
                PrivacyDashboardSheet(
                    isVisible = showPrivacyDashboard,
                    trackersBlockedCount = trackersBlockedCount,
                    isProtonVpnActive = protonVpnActive,
                    isIncognitoActive = activeTabIsIncognito,
                    events = blockedEvents,
                    onDismiss = { showPrivacyDashboard = false }
                )
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun openUrlExternally(url: String) {
        try {
            val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Open with")
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "No browser app available to open this", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ---- camera / microphone: the user decides per request ----------------------------------------

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        // One request at a time; a second one is refused instead of silently replacing the first.
        if (pendingPermissionRequest != null) {
            request.deny()
            return
        }

        val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val wantsDrm = request.resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)

        if (!wantsCamera && !wantsMic) {
            // Protected-media (DRM) playback needs no access to any device; everything else (MIDI...) is refused.
            if (wantsDrm) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            } else {
                request.deny()
            }
            return
        }

        pendingPermissionRequest = request
        val host = request.origin?.host ?: "This site"
        val what = when {
            wantsCamera && wantsMic -> "camera and microphone"
            wantsCamera -> "camera"
            else -> "microphone"
        }
        permissionDialog?.dismiss()
        permissionDialog = AlertDialog.Builder(this)
            .setTitle("Allow $what?")
            .setMessage("$host wants to use your $what.")
            .setPositiveButton("Allow") { _, _ -> continuePermissionRequest(request) }
            .setNegativeButton("Block") { _, _ -> finishPermissionRequest(request, grant = false) }
            .setOnCancelListener { finishPermissionRequest(request, grant = false) }
            .show()
    }

    private fun continuePermissionRequest(request: PermissionRequest) {
        val needed = mutableListOf<String>()
        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
            !hasPermission(Manifest.permission.CAMERA)
        ) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
            !hasPermission(Manifest.permission.RECORD_AUDIO)
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isEmpty()) {
            finishPermissionRequest(request, grant = true)
        } else {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun finishPermissionRequest(request: PermissionRequest, grant: Boolean) {
        if (pendingPermissionRequest !== request) return // already answered or cancelled
        pendingPermissionRequest = null
        if (!grant) {
            request.deny()
            return
        }
        val allowed = mutableListOf<String>()
        for (resource in request.resources) {
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    if (hasPermission(Manifest.permission.CAMERA)) allowed.add(resource)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    if (hasPermission(Manifest.permission.RECORD_AUDIO)) allowed.add(resource)
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> allowed.add(resource)
            }
        }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    private fun cancelWebPermissionRequest(request: PermissionRequest) {
        if (pendingPermissionRequest === request) {
            pendingPermissionRequest = null
            permissionDialog?.dismiss()
        }
    }

    // ---- downloads: always confirmed first ----------------------------------------------------------

    private fun confirmDownload(download: PendingDownload) {
        if (!download.url.startsWith("https://") && !download.url.startsWith("http://")) {
            Toast.makeText(this, "This kind of download isn't supported", Toast.LENGTH_LONG).show()
            return
        }
        val blockReason = viewModel.downloadBlockReason()
        if (blockReason != null) {
            Toast.makeText(this, blockReason, Toast.LENGTH_LONG).show()
            return
        }
        if (downloadDialog?.isShowing == true) return

        val host = Uri.parse(download.url).host ?: "this site"
        val size = if (download.contentLength > 0) {
            Formatter.formatShortFileSize(this, download.contentLength)
        } else {
            "unknown size"
        }
        val isInstaller = download.fileName.endsWith(".apk", ignoreCase = true) ||
            download.mimeType == "application/vnd.android.package-archive"
        val warning = if (isInstaller) {
            "\n\nThis is an app installer (APK). Only download it if you trust this site."
        } else {
            ""
        }
        downloadDialog = AlertDialog.Builder(this)
            .setTitle("Download file?")
            .setMessage("${download.fileName}\n$size, from $host$warning")
            .setPositiveButton("Download") { _, _ -> ensureStoragePermissionThen { startDownload(download) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Android 9 and older need the storage permission to write into the public Downloads folder. */
    private fun ensureStoragePermissionThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P || hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            action()
            return
        }
        afterStoragePermission = action
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun startDownload(download: PendingDownload) {
        viewModel.startDownload(
            download.url, download.fileName, download.mimeType, download.userAgent, download.cookie
        ) { success, message ->
            if (success) {
                Toast.makeText(this, "Download started: ${download.fileName}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, message ?: "Unable to start the download", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- fullscreen video ------------------------------------------------------------------------

    private fun showCustomView(view: View) {
        fullscreenContainer?.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenContainer?.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun hideCustomView() {
        if (customView == null) return

        fullscreenContainer?.visibility = View.GONE
        fullscreenContainer?.removeAllViews()
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        // Installed apps and the VPN state may have changed while the app was in the background.
        viewModel.refreshInstalledApps()
        if (!viewModel.networkBlocked.value) {
            webViewRef?.onResume()
            webViewRef?.resumeTimers()
        }
    }

    override fun onStop() {
        super.onStop()
        // Nothing the page does (timers, scripts, media) should keep running while the app is in the background.
        webViewRef?.onPause()
        webViewRef?.pauseTimers()
    }

    override fun onPause() {
        super.onPause()
        // Privacy: relock hidden tabs the moment the app leaves the
        // foreground (app switcher, another app, screen off, etc.).
        viewModel.relockHiddenTabs()
    }

    override fun onDestroy() {
        permissionDialog?.dismiss()
        downloadDialog?.dismiss()
        super.onDestroy()
        networkObserver.unregister()
        // Only when the app is really being closed. A configuration change also destroys the Activity,
        // and wiping then would silently log the user out of every site.
        if (isFinishing) {
            BrowsingDataWiper.wipeLive(webViewRef)
        }
        webViewRef = null
    }
}
