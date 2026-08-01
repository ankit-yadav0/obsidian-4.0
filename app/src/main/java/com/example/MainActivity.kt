package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.example.weblite.network.NetworkObserver
import com.example.weblite.ui.components.AppWebView
import com.example.weblite.ui.components.BrowserTopBar
import com.example.weblite.ui.components.DownloadsSheet
import com.example.weblite.ui.components.HomeScreen
import com.example.weblite.ui.components.OfflineBanner
import com.example.weblite.ui.components.VpnRequiredOverlay
import com.example.weblite.ui.components.OfflineErrorView
import com.example.weblite.ui.components.PrivacyDashboardSheet
import com.example.weblite.ui.components.SplashScreen
import com.example.weblite.viewmodel.MainViewModel
import com.example.ui.theme.AppTheme
import com.example.ui.theme.CineRed

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var networkObserver: NetworkObserver

    // File Chooser state for Web Chrome Client
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Fullscreen video view state
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null

    // Pending WebRTC permission request
    private var pendingPermissionRequest: PermissionRequest? = null

    // Double back press exit logic
    private var backPressedTime: Long = 0

    // Reference to WebView
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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach { entry ->
                if (!entry.value) allGranted = false
            }

            if (allGranted && pendingPermissionRequest != null) {
                pendingPermissionRequest?.grant(pendingPermissionRequest?.resources)
            } else if (pendingPermissionRequest != null) {
                pendingPermissionRequest?.deny()
            }
            pendingPermissionRequest = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val currentUrl by viewModel.currentUrl.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val loadingProgress by viewModel.loadingProgress.collectAsState()
        val isOffline by viewModel.isOffline.collectAsState()
        val errorMessage by viewModel.errorMessage.collectAsState()
        val canGoBack by viewModel.canGoBack.collectAsState()
        val isRefreshing by viewModel.isRefreshing.collectAsState()
        val showSplashScreen by viewModel.showSplashScreen.collectAsState()
        val isBannerOffline by viewModel.isBannerOffline.collectAsState()
        val downloads by viewModel.downloads.collectAsState()
        val showDownloadsSheet by viewModel.showDownloadsSheet.collectAsState()
        val tabs by viewModel.tabs.collectAsState()
        val showHomeScreen by viewModel.showHomeScreen.collectAsState()
        val allowedDomain by viewModel.activeAllowedDomain.collectAsState()
        val activeTabIsShieldOff by viewModel.activeTabIsShieldOff.collectAsState()
        val activeTabIsIncognito by viewModel.activeTabIsIncognito.collectAsState()
        val blockedEvents by viewModel.blockedEvents.collectAsState()
        val adultSitesBlockedCount by viewModel.adultSitesBlockedCount.collectAsState()
        var showPrivacyDashboard by remember { mutableStateOf(false) }
        val hiddenTabsUnlocked by viewModel.hiddenTabsUnlocked.collectAsState()
        val trackersBlockedCount by viewModel.trackersBlockedCount.collectAsState()
        val bookmarks by viewModel.bookmarks.collectAsState()
        val isActiveTabBookmarked = bookmarks.any { it.url == currentUrl }
        val torEnabled by viewModel.torEnabled.collectAsState()
        val torStatusMessage by viewModel.torStatusMessage.collectAsState()
        val adultBlockEnabled by viewModel.adultBlockEnabled.collectAsState()
        val protonVpnActive by viewModel.protonVpnActive.collectAsState()
        val isAlwaysExternal by viewModel.isAlwaysExternalForCurrentUrl.collectAsState()

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

        val isOnline by networkObserver.isOnline.collectAsState()

        // Sync network observer with ViewModel & auto-refresh on restoration
        LaunchedEffect(isOnline) {
            val wasOffline = isOffline
            viewModel.updateNetworkState(isOnline)
            if (isOnline && wasOffline) {
                viewModel.refreshPage()
                webViewRef?.reload()
            }
        }

        var isCustomViewShowing by remember { mutableStateOf(false) }

        // Back Handler
        BackHandler {
            if (isCustomViewShowing && customViewCallback != null) {
                hideCustomView()
                isCustomViewShowing = false
            } else if (webViewRef?.canGoBack() == true) {
                webViewRef?.goBack()
            } else if (!showHomeScreen) {
                // No more history in this tab — go to the tab switcher
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

        // Cold start: show the splash first, before deciding home vs.
        // browsing. Previously this check was ordered so that, while the
        // splash was still visible, the code fell through to the browsing
        // Scaffold and rendered AppWebView with an empty URL — fixed by
        // checking splash first and returning early.
        if (showSplashScreen) {
            SplashScreen(isVisible = true, modifier = Modifier.fillMaxSize())
            return
        }

        if (showHomeScreen) {
            HomeScreen(
                openTabs = tabs,
                hiddenTabsUnlocked = hiddenTabsUnlocked,
                isPinSet = viewModel.isPinSet(),
                trackersBlockedCount = trackersBlockedCount,
                bookmarks = bookmarks,
                torEnabled = torEnabled,
                torStatusMessage = torStatusMessage,
                isOrbotInstalled = viewModel.isOrbotInstalled(),
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
                onRelock = { viewModel.relockHiddenTabs() },
                modifier = Modifier.fillMaxSize()
            )
            return
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0E15))
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
                            adultBlockEnabled = adultBlockEnabled,
                            isAlwaysExternal = isAlwaysExternal,
                            isIncognito = activeTabIsIncognito,
                            onNavigate = { input -> viewModel.navigateActiveTab(input) },
                            onToggleShield = { viewModel.toggleShieldForActiveTab() },
                            onToggleBookmark = {
                                if (isActiveTabBookmarked) {
                                    viewModel.removeBookmark(currentUrl)
                                } else {
                                    viewModel.addBookmark(currentUrl, webViewRef?.title ?: currentUrl)
                                }
                            },
                            onToggleAdultBlock = { viewModel.toggleAdultBlock(!adultBlockEnabled) },
                            onOpenExternally = {
                                try {
                                    val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)), "Open with")
                                    startActivity(chooser)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No browser app available to open this", Toast.LENGTH_SHORT).show()
                                }
                            },
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
                            color = CineRed,
                            trackColor = Color.Transparent
                        )
                    }

                    // Persistent Non-Intrusive Offline Banner
                    OfflineBanner(
                        isOffline = isBannerOffline && !isOffline,
                        onRetry = {
                            if (networkObserver.checkInitialConnection()) {
                                viewModel.refreshPage()
                                webViewRef?.reload()
                            } else {
                                Toast.makeText(context, "Still offline. Retrying connection...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onViewDownloads = {
                            viewModel.toggleDownloadsSheet(true)
                        }
                    )

                    // (VPN status is now enforced via the full-screen
                    // VpnRequiredOverlay below instead of a small banner.)

                    // Pull-to-refresh Wrapper
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            if (isOnline) {
                                viewModel.refreshPage()
                                webViewRef?.reload()
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
                            isOnline = isOnline,
                            isRefreshing = isRefreshing,
                            allowedDomain = allowedDomain,
                            shieldOff = activeTabIsShieldOff,
                            onPageStarted = { url ->
                                viewModel.onPageStarted(url)
                            },
                            onProgressChanged = { progress ->
                                viewModel.onProgressChanged(progress)
                            },
                            onPageFinished = { url, canBack ->
                                viewModel.onPageFinished(url, canBack)
                                viewModel.updateActiveTabMeta(url, webViewRef?.title ?: "")
                            },
                            onError = { errorMsg ->
                                viewModel.onWebError(errorMsg)
                            },
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
                            onPermissionRequest = { request ->
                                pendingPermissionRequest = request
                                val requestedResources = request.resources
                                val permissionsToRequest = mutableListOf<String>()

                                if (requestedResources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                                    permissionsToRequest.add(Manifest.permission.CAMERA)
                                }
                                if (requestedResources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                                    permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                                }

                                if (permissionsToRequest.isNotEmpty()) {
                                    requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                                } else {
                                    request.grant(requestedResources)
                                }
                            },
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
                            onWebViewCreated = { webView ->
                                webViewRef = webView
                            },
                            onDownloadRequested = { url, fileName, mimeType, userAgent, cookie ->
                                viewModel.startDownload(url, fileName, mimeType, userAgent, cookie)
                            },
                            onTrackerBlocked = { host -> viewModel.onTrackerBlocked(host) },
                            isAdultContentHost = { host -> viewModel.isAdultContentHost(host) },
                            onAdultContentBlocked = { host -> viewModel.onAdultContentBlocked(host) },
                            shouldOpenExternally = { host -> viewModel.shouldOpenExternally(host) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Browsing is paused while Proton VPN isn't detected as
                // active — covers the toolbar + page content, but doesn't
                // stop the fullscreen-video container below it.
                if (!protonVpnActive) {
                    VpnRequiredOverlay(
                        isProtonVpnInstalled = viewModel.isProtonVpnInstalled(),
                        onOpenProtonVpn = {
                            val protonPackage = com.example.weblite.vpn.VpnStatusMonitor.PROTON_VPN_PACKAGE
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
                        containerColor = CineRed,
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
                        if (networkObserver.checkInitialConnection()) {
                            viewModel.refreshPage()
                            webViewRef?.reload()
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
                    onDelete = { item -> viewModel.deleteDownload(item) },
                    onRetry = { item -> viewModel.retryDownload(item) }
                )

                // Privacy Dashboard - proof of what's actually being blocked
                PrivacyDashboardSheet(
                    isVisible = showPrivacyDashboard,
                    trackersBlockedCount = trackersBlockedCount,
                    adultSitesBlockedCount = adultSitesBlockedCount,
                    isProtonVpnActive = protonVpnActive,
                    isIncognitoActive = activeTabIsIncognito,
                    events = blockedEvents,
                    onDismiss = { showPrivacyDashboard = false }
                )
            }
        }
    }

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

    override fun onPause() {
        super.onPause()
        // Privacy: relock hidden tabs the moment the app leaves the
        // foreground (app switcher, another app, screen off, etc.).
        viewModel.relockHiddenTabs()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkObserver.unregister()
        clearBrowsingData()
        webViewRef = null
    }

    private fun clearBrowsingData() {
        // Privacy: wipe cookies, cache, and browsing history so nothing about
        // what you viewed persists on the device after the app is closed.
        webViewRef?.apply {
            clearHistory()
            clearCache(true)
            clearFormData()
        }
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
    }
}
