package com.example.weblite.viewmodel

import android.app.Application
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.weblite.data.AppDatabase
import com.example.weblite.data.PinManager
import com.example.weblite.data.model.Bookmark
import com.example.weblite.data.model.DownloadItem
import com.example.weblite.data.repository.DownloadRepository
import com.example.weblite.network.NetworkPolicyManager
import com.example.weblite.network.ProxyMode
import com.example.weblite.privacy.BrowsingDataWiper
import com.example.weblite.privacy.ExternalHandoffManager
import com.example.weblite.util.UrlUtils
import com.example.weblite.vpn.VpnStatusMonitor
import com.example.weblite.webview.BlockCategory
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String = "",
    val isHidden: Boolean = false,
    val isIncognito: Boolean = false,
    val isShieldOff: Boolean = false
)

/** A request to load [url] in the visible WebView. Consumed exactly once. */
data class NavRequest(val id: Long, val url: String)

/** A link the user tapped that the site-lock policy refused; the UI offers to open it anyway. */
data class BlockedNavigation(val id: Long, val url: String, val host: String)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SPLASH_MS = 1000L
        private const val DOWNLOAD_POLL_MS = 1200L
        private const val COUNTER_PUBLISH_MS = 400L
        private const val MAX_BLOCKED_EVENTS = 200
    }

    private val app: Application = application

    // ------------------------------------------------------------------------------------------
    // PIN / hidden tabs
    // ------------------------------------------------------------------------------------------
    private val pinManager = PinManager(application)

    private val _pinSet = MutableStateFlow(pinManager.isPinSet())
    val pinSet: StateFlow<Boolean> = _pinSet.asStateFlow()

    fun setPin(pin: String) {
        pinManager.setPin(pin)
        _pinSet.value = true
    }

    // Session-only unlock: hidden tabs re-lock every time the app leaves the foreground.
    private val _hiddenTabsUnlocked = MutableStateFlow(false)
    val hiddenTabsUnlocked: StateFlow<Boolean> = _hiddenTabsUnlocked.asStateFlow()

    fun unlockHiddenTabs(pin: String): PinManager.VerifyResult {
        val result = pinManager.verifyPin(pin)
        if (result is PinManager.VerifyResult.Success) _hiddenTabsUnlocked.value = true
        return result
    }

    fun relockHiddenTabs() {
        _hiddenTabsUnlocked.value = false
    }

    /** "Forgot PIN": the PIN cannot be recovered, so the hidden tabs are closed and the PIN is removed. */
    fun resetPinAndDeleteHiddenTabs() {
        _tabs.value.filter { it.isHidden }.map { it.id }.forEach { closeTab(it) }
        pinManager.clearPin()
        _pinSet.value = false
        _hiddenTabsUnlocked.value = false
    }

    // ------------------------------------------------------------------------------------------
    // Network policy: VPN requirement + Tor, combined into ONE proxy mode
    // ------------------------------------------------------------------------------------------
    private val networkPolicy = NetworkPolicyManager(application)
    private val vpnStatusMonitor = VpnStatusMonitor(application)

    private val _protonInstalled = MutableStateFlow(vpnStatusMonitor.isProtonVpnInstalled())
    val protonVpnInstalled: StateFlow<Boolean> = _protonInstalled.asStateFlow()

    private val _orbotInstalled = MutableStateFlow(networkPolicy.isOrbotInstalled())
    val orbotInstalled: StateFlow<Boolean> = _orbotInstalled.asStateFlow()

    val protonVpnActive: StateFlow<Boolean> = combine(
        _protonInstalled, vpnStatusMonitor.isVpnActive
    ) { installed, active -> installed && active }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            _protonInstalled.value && vpnStatusMonitor.isVpnActive.value
        )

    /** True while browsing must not touch the network (VPN required but not connected). */
    val networkBlocked: StateFlow<Boolean> = protonVpnActive
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, !protonVpnActive.value)

    private val _torEnabled = MutableStateFlow(false)
    val torEnabled: StateFlow<Boolean> = _torEnabled.asStateFlow()
    private val _torStatusMessage = MutableStateFlow<String?>(null)
    val torStatusMessage: StateFlow<String?> = _torStatusMessage.asStateFlow()

    /** Called when the app returns to the foreground: app installs and VPN state may have changed. */
    fun refreshInstalledApps() {
        _protonInstalled.value = vpnStatusMonitor.isProtonVpnInstalled()
        _orbotInstalled.value = networkPolicy.isOrbotInstalled()
        vpnStatusMonitor.refresh()
    }

    fun toggleTor(enabled: Boolean) {
        if (!enabled) {
            _torEnabled.value = false
            _torStatusMessage.value = null
            return
        }
        if (!networkPolicy.isOrbotInstalled()) {
            _torStatusMessage.value = "Orbot isn't installed. Install it to use Tor routing."
            return
        }
        if (!networkPolicy.isProxyOverrideSupported()) {
            _torStatusMessage.value = "This device's WebView is too old to support Tor routing."
            return
        }
        _torStatusMessage.value = "Checking Orbot..."
        viewModelScope.launch {
            val listening = withContext(Dispatchers.IO) { networkPolicy.isOrbotListening() }
            if (listening) {
                _torEnabled.value = true
                _torStatusMessage.value =
                    "Tor routing is on for pages. Downloads are disabled while it is on, because Android's download manager can't use Tor."
            } else {
                _torEnabled.value = false
                _torStatusMessage.value =
                    "Orbot isn't connected yet. Open Orbot, tap Start, wait until it says connected, then try again."
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sites that are handed to an external browser instead of loading in-app
    // ------------------------------------------------------------------------------------------
    private val externalHandoffManager = ExternalHandoffManager(application)
    fun shouldOpenExternally(host: String): Boolean = externalHandoffManager.shouldOpenExternally(host)

    private val _externalOpen = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** URLs the host should open in an external browser (emitted when an "always external" site is opened). */
    val externalOpenEvents: SharedFlow<String> = _externalOpen.asSharedFlow()

    private fun handOffIfExternal(url: String): Boolean {
        val host: String = try {
            Uri.parse(url).host?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
        if (host.isNotEmpty() && externalHandoffManager.shouldOpenExternally(host)) {
            _externalOpen.tryEmit(url)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------------------------------
    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)

    // Host of the active tab. The WebView keeps its own up-to-date notion of "current site"; this is
    // only the seed it starts from.
    val activeAllowedDomain: StateFlow<String> = combine(
        _tabs, _activeTabId
    ) { tabs, id ->
        tabs.firstOrNull { it.id == id }?.let { Uri.parse(it.url).host } ?: ""
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val activeTabIsShieldOff: StateFlow<Boolean> = combine(
        _tabs, _activeTabId
    ) { tabs, id ->
        tabs.firstOrNull { it.id == id }?.isShieldOff ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val activeTabIsIncognito: StateFlow<Boolean> = combine(
        _tabs, _activeTabId
    ) { tabs, id ->
        tabs.firstOrNull { it.id == id }?.isIncognito ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True when no tab is open - shows the URL-entry home screen. */
    val showHomeScreen: StateFlow<Boolean> = _activeTabId
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _navRequest = MutableStateFlow<NavRequest?>(null)

    /** Pending "load this URL" request for the visible WebView (typed in the address bar, opened from a snackbar...). */
    val navRequest: StateFlow<NavRequest?> = _navRequest.asStateFlow()
    private var navCounter = 0L

    fun onNavRequestHandled() {
        _navRequest.value = null
    }

    fun openNewTab(rawUrl: String, incognito: Boolean = false) {
        val url = UrlUtils.normalizeInput(rawUrl) ?: return
        if (handOffIfExternal(url)) return
        val tab = BrowserTab(id = UUID.randomUUID().toString(), url = url, isIncognito = incognito)
        _tabs.value = _tabs.value + tab
        switchToTab(tab.id)
    }

    /** Navigate the currently active tab to a new URL/search, e.g. from the address bar. */
    fun navigateActiveTab(rawInput: String) {
        val id = _activeTabId.value ?: return
        val url = UrlUtils.normalizeInput(rawInput) ?: return
        if (handOffIfExternal(url)) return
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(url = url) else it }
        _currentUrl.value = url
        _isLoading.value = true
        _hasLoadedContentSuccessfully.value = false
        _errorMessage.value = null
        _isOffline.value = false
        _navRequest.value = NavRequest(++navCounter, url)
    }

    fun toggleShieldForActiveTab() {
        val id = _activeTabId.value ?: return
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(isShieldOff = !it.isShieldOff) else it }
    }

    fun switchToTab(id: String) {
        _activeTabId.value = id
        _navRequest.value = null
        _hiddenTabsUnlocked.value = false
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        _currentUrl.value = tab.url
        _isLoading.value = true
        _hasLoadedContentSuccessfully.value = false
        _errorMessage.value = null
        _isOffline.value = false

        // Incognito: while an incognito tab is active the shared cookie store stops accepting cookies.
        // The app has a single shared WebView profile, so this (plus clearing site data when an incognito
        // tab closes) is what "incognito" can honestly mean here; the home screen says so.
        try {
            CookieManager.getInstance().setAcceptCookie(!tab.isIncognito)
        } catch (e: Exception) {
            // WebView unavailable
        }
    }

    fun closeTab(id: String) {
        val closedTab = _tabs.value.firstOrNull { it.id == id }
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            val next = remaining.lastOrNull()
            if (next != null) switchToTab(next.id) else _activeTabId.value = null
        }
        // One shared browser profile: closing an incognito tab clears cookies and site storage for ALL tabs.
        if (closedTab?.isIncognito == true) {
            BrowsingDataWiper.wipeSiteData()
        }
    }

    fun goHome() {
        _activeTabId.value = null
        _navRequest.value = null
    }

    fun hideTab(id: String) {
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(isHidden = true) else it }
        if (_activeTabId.value == id) _activeTabId.value = null
    }

    fun unhideTab(id: String) {
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(isHidden = false) else it }
    }

    fun updateActiveTabMeta(url: String, title: String) {
        val id = _activeTabId.value ?: return
        _tabs.value = _tabs.value.map {
            if (it.id == id) it.copy(url = url, title = title.ifBlank { it.title }) else it
        }
    }

    // ------------------------------------------------------------------------------------------
    // Bookmarks
    // ------------------------------------------------------------------------------------------
    private val bookmarkDao by lazy { AppDatabase.getDatabase(application).bookmarkDao() }

    val bookmarks: StateFlow<List<Bookmark>> by lazy {
        bookmarkDao.getAllBookmarks().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    /** Incognito and hidden tabs are never bookmarked: the bookmark list is stored on disk and shown on the home screen. */
    fun canBookmarkActiveTab(): Boolean {
        val tab = _tabs.value.firstOrNull { it.id == _activeTabId.value } ?: return true
        return !tab.isIncognito && !tab.isHidden
    }

    fun addBookmark(url: String, title: String): Boolean {
        if (!canBookmarkActiveTab()) return false
        if (bookmarks.value.any { it.url == url }) return true
        viewModelScope.launch {
            bookmarkDao.insert(Bookmark(url = url, title = title))
        }
        return true
    }

    fun removeBookmark(url: String) {
        viewModelScope.launch {
            bookmarkDao.deleteByUrl(url)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------------------------------
    private val repository: DownloadRepository by lazy {
        val database = AppDatabase.getDatabase(application)
        DownloadRepository(application, database.downloadDao())
    }

    val downloads: StateFlow<List<DownloadItem>> by lazy {
        repository.downloadsFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    private val _showDownloadsSheet = MutableStateFlow(false)
    val showDownloadsSheet: StateFlow<Boolean> = _showDownloadsSheet.asStateFlow()

    fun toggleDownloadsSheet(show: Boolean) {
        _showDownloadsSheet.value = show
    }

    /**
     * Why a download must be refused right now, or null if it may go ahead. Android's DownloadManager is a
     * system service: it does not use the Tor proxy, and it is not covered by the WebView kill-switch.
     */
    fun downloadBlockReason(): String? = when {
        networkBlocked.value -> "Connect Proton VPN first. Downloads are paused until it is active."
        _torEnabled.value ->
            "Downloads are off while Tor routing is on: Android's download manager can't use Tor and would reveal your real IP address."
        else -> null
    }

    fun startDownload(
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String?,
        cookie: String?,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        viewModelScope.launch {
            val reason = downloadBlockReason()
            if (reason != null) {
                onResult(false, reason)
                return@launch
            }
            try {
                repository.enqueueDownload(url, fileName, mimeType, userAgent, cookie)
                ensureDownloadPolling()
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.localizedMessage ?: "Unable to start download")
            }
        }
    }

    fun deleteDownload(downloadItem: DownloadItem, deleteFile: Boolean) {
        viewModelScope.launch {
            repository.deleteDownload(downloadItem, deleteFile)
        }
    }

    fun retryDownload(downloadItem: DownloadItem, onResult: (success: Boolean, message: String?) -> Unit) {
        viewModelScope.launch {
            val reason = downloadBlockReason()
            if (reason != null) {
                onResult(false, reason)
                return@launch
            }
            // Cookies and the user agent are not stored with the download, so fetch fresh ones.
            val userAgent = try {
                UrlUtils.cleanUserAgent(WebSettings.getDefaultUserAgent(app))
            } catch (e: Exception) {
                null
            }
            val cookie = try {
                CookieManager.getInstance().getCookie(downloadItem.url)
            } catch (e: Exception) {
                null
            }
            try {
                repository.retryDownload(downloadItem, userAgent, cookie)
                ensureDownloadPolling()
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.localizedMessage ?: "Unable to restart download")
            }
        }
    }

    private var pollJob: Job? = null

    @Volatile
    private var pollAgain = false

    /** Polls DownloadManager only while something is downloading; the loop ends by itself afterwards. */
    private fun ensureDownloadPolling() {
        pollAgain = true
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                pollAgain = false
                val anyActive = try {
                    repository.syncActiveDownloads()
                } catch (e: Exception) {
                    false
                }
                if (anyActive) delay(DOWNLOAD_POLL_MS) else if (!pollAgain) break
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Current page state
    // ------------------------------------------------------------------------------------------
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    // SharedPreferences changes don't emit on their own, so this counter is bumped on every toggle
    // to force isAlwaysExternalForCurrentUrl to recompute.
    private val _alwaysExternalVersion = MutableStateFlow(0)

    val isAlwaysExternalForCurrentUrl: StateFlow<Boolean> = combine(
        _currentUrl, _alwaysExternalVersion
    ) { url, _ ->
        val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        externalHandoffManager.shouldOpenExternally(host)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleAlwaysExternalForCurrentUrl() {
        val host = try { Uri.parse(_currentUrl.value).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        if (host.isBlank()) return
        if (externalHandoffManager.shouldOpenExternally(host)) {
            externalHandoffManager.removeDomain(host)
        } else {
            externalHandoffManager.addDomain(host)
        }
        _alwaysExternalVersion.value += 1
    }

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0)
    val loadingProgress: StateFlow<Int> = _loadingProgress.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _showSplashScreen = MutableStateFlow(true)
    val showSplashScreen: StateFlow<Boolean> = _showSplashScreen.asStateFlow()

    private val _hasLoadedContentSuccessfully = MutableStateFlow(false)

    private val _isBannerOffline = MutableStateFlow(false)
    val isBannerOffline: StateFlow<Boolean> = _isBannerOffline.asStateFlow()

    // ------------------------------------------------------------------------------------------
    // Blocked-request bookkeeping (privacy dashboard)
    // ------------------------------------------------------------------------------------------
    data class BlockedEvent(val host: String, val category: String, val timestampMillis: Long)

    private val _trackersBlockedCount = MutableStateFlow(0)
    val trackersBlockedCount: StateFlow<Int> = _trackersBlockedCount.asStateFlow()

    private val _blockedEvents = MutableStateFlow<List<BlockedEvent>>(emptyList())
    val blockedEvents: StateFlow<List<BlockedEvent>> = _blockedEvents.asStateFlow()

    // Blocking happens on WebView's IO threads, many times per second on ad-heavy pages. The raw counters
    // are therefore atomic / locked, and the UI-facing flows are refreshed at most every 400 ms so that
    // each blocked request does not recompose the whole screen.
    private val trackerCount = AtomicInteger(0)
    private val eventLog = ArrayDeque<BlockedEvent>()
    private val publishPending = AtomicBoolean(false)

    private fun recordBlock(host: String, category: String, countAsTracker: Boolean) {
        if (countAsTracker) trackerCount.incrementAndGet()
        val entry = BlockedEvent(host.ifBlank { "(unknown)" }, category, System.currentTimeMillis())
        synchronized(eventLog) {
            eventLog.addFirst(entry)
            while (eventLog.size > MAX_BLOCKED_EVENTS) eventLog.removeLast()
        }
        schedulePublish()
    }

    private fun schedulePublish() {
        if (!publishPending.compareAndSet(false, true)) return
        viewModelScope.launch {
            delay(COUNTER_PUBLISH_MS)
            publishPending.set(false)
            _trackersBlockedCount.value = trackerCount.get()
            _blockedEvents.value = synchronized(eventLog) { eventLog.toList() }
        }
    }

    /** Safe to call from any thread. */
    fun onTrackerBlocked(host: String = "", category: String = BlockCategory.TRACKER) {
        recordBlock(host, category, countAsTracker = true)
    }

    private val _blockedNavigation = MutableStateFlow<BlockedNavigation?>(null)
    val blockedNavigation: StateFlow<BlockedNavigation?> = _blockedNavigation.asStateFlow()
    private var blockedNavCounter = 0L

    /** The user tapped a link to another site and the site lock refused it: logged, not counted as a tracker. */
    fun onNavigationBlocked(url: String) {
        val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        recordBlock(host, BlockCategory.LINK, countAsTracker = false)
        _blockedNavigation.value = BlockedNavigation(++blockedNavCounter, url, host.ifBlank { url })
    }

    fun consumeBlockedNavigation() {
        _blockedNavigation.value = null
    }

    fun openBlockedNavigation(url: String) {
        navigateActiveTab(url)
    }

    // ------------------------------------------------------------------------------------------
    // WebView callbacks
    // ------------------------------------------------------------------------------------------
    fun onPageStarted(url: String) {
        _currentUrl.value = url
        _isLoading.value = true
        _errorMessage.value = null
    }

    /** The visible URL changed without a new page load (single-page apps) or after a redirect. */
    fun onUrlChanged(url: String) {
        _currentUrl.value = url
        val id = _activeTabId.value ?: return
        _tabs.value = _tabs.value.map { if (it.id == id && it.url != url) it.copy(url = url) else it }
    }

    fun onProgressChanged(progress: Int) {
        _loadingProgress.value = progress
        if (progress >= 100) {
            _isLoading.value = false
            _isRefreshing.value = false
            _hasLoadedContentSuccessfully.value = true
            _isBannerOffline.value = false
            _isOffline.value = false
        }
    }

    fun onPageFinished(url: String) {
        _currentUrl.value = url
        _isLoading.value = false
        _isRefreshing.value = false
        _hasLoadedContentSuccessfully.value = true
        _isBannerOffline.value = false
        _isOffline.value = false
    }

    fun onWebError(description: String) {
        _isLoading.value = false
        _isRefreshing.value = false
        if (!_hasLoadedContentSuccessfully.value) {
            _isOffline.value = true
            _errorMessage.value = description
        } else {
            _isBannerOffline.value = true
        }
    }

    fun updateNetworkState(isOnline: Boolean) {
        if (isOnline) {
            _isBannerOffline.value = false
            _isOffline.value = false
            _errorMessage.value = null
        } else {
            _isBannerOffline.value = true
            if (!_hasLoadedContentSuccessfully.value) {
                _isOffline.value = true
                _errorMessage.value = "No Internet Connection"
            }
        }
    }

    fun refreshPage() {
        _isRefreshing.value = true
        _errorMessage.value = null
        _isOffline.value = false
    }

    fun onRefreshHandled() {
        _isRefreshing.value = false
    }

    // ------------------------------------------------------------------------------------------
    init {
        // Short branding splash.
        viewModelScope.launch {
            delay(SPLASH_MS)
            _showSplashScreen.value = false
        }

        // Bring stored download states up to date once (a download may have finished while the app was closed).
        ensureDownloadPolling()

        // One owner for the WebView proxy: VPN missing -> block everything, else Tor if enabled, else direct.
        // The first emission also clears any proxy override left over from an earlier ViewModel in this process.
        viewModelScope.launch {
            var previous: ProxyMode? = null
            combine(protonVpnActive, _torEnabled) { vpnActive, tor ->
                when {
                    !vpnActive -> ProxyMode.BLOCKED
                    tor -> ProxyMode.TOR
                    else -> ProxyMode.DIRECT
                }
            }.distinctUntilChanged().collect { mode ->
                val wasBlocked = previous == ProxyMode.BLOCKED
                previous = mode
                networkPolicy.apply(mode) {
                    // The VPN came back: pages that failed while it was missing can be reloaded now.
                    if (wasBlocked && mode != ProxyMode.BLOCKED && _activeTabId.value != null) refreshPage()
                }
            }
        }
    }

    override fun onCleared() {
        vpnStatusMonitor.unregister()
        super.onCleared()
    }
}
