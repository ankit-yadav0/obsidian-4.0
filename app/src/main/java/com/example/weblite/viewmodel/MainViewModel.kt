package com.example.weblite.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.weblite.data.AppDatabase
import com.example.weblite.data.model.DownloadItem
import com.example.weblite.data.repository.DownloadRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.net.Uri
import java.util.UUID
import com.example.weblite.data.PinManager
import com.example.weblite.privacy.AdultContentFilter
import com.example.weblite.privacy.ExternalHandoffManager
import com.example.weblite.vpn.VpnStatusMonitor

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String = "",
    val isHidden: Boolean = false,
    val isIncognito: Boolean = false,
    val isShieldOff: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private fun normalizeUrl(input: String): String {
            val trimmed = input.trim()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return trimmed
            }
            // Looks like a bare domain (has a dot, no spaces, no obvious
            // search-query punctuation) — treat as a URL. Otherwise treat
            // it as a search query.
            val looksLikeUrl = !trimmed.contains(" ") &&
                trimmed.contains(".") &&
                !trimmed.contains("?") &&
                Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$").matches(trimmed)
            return if (looksLikeUrl) {
                "https://$trimmed"
            } else {
                "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
            }
        }
    }

    private val pinManager = PinManager(application)

    // --- Tor (via Orbot) ---
    private val torManager = com.example.weblite.tor.TorManager(application)
    private val _torEnabled = MutableStateFlow(false)
    val torEnabled: StateFlow<Boolean> = _torEnabled.asStateFlow()
    private val _torStatusMessage = MutableStateFlow<String?>(null)
    val torStatusMessage: StateFlow<String?> = _torStatusMessage.asStateFlow()

    fun isOrbotInstalled(): Boolean = torManager.isOrbotInstalled()

    fun toggleTor(enabled: Boolean) {
        if (enabled) {
            if (!torManager.isOrbotInstalled()) {
                _torStatusMessage.value = "Orbot isn't installed. Install it to use Tor routing."
                return
            }
            if (!torManager.isProxyOverrideSupported()) {
                _torStatusMessage.value = "This device's WebView is too old to support Tor routing."
                return
            }
            torManager.enableTorRouting {
                _torEnabled.value = true
                _torStatusMessage.value = "Tor routing on. Make sure Orbot is open and connected (its icon shows connected), or pages won't load."
            }
        } else {
            torManager.disableTorRouting {
                _torEnabled.value = false
                _torStatusMessage.value = null
            }
        }
    }
    fun isPinSet(): Boolean = pinManager.isPinSet()
    fun setPin(pin: String) = pinManager.setPin(pin)
    fun verifyPin(pin: String): Boolean = pinManager.verifyPin(pin)

    // --- Adult content filter ---
    private val adultContentFilter = AdultContentFilter(application)
    private val _adultBlockEnabled = MutableStateFlow(adultContentFilter.isEnabled())
    val adultBlockEnabled: StateFlow<Boolean> = _adultBlockEnabled.asStateFlow()

    fun toggleAdultBlock(enabled: Boolean) {
        adultContentFilter.setEnabled(enabled)
        _adultBlockEnabled.value = enabled
    }

    fun isAdultContentHost(host: String): Boolean = adultContentFilter.isBlockedHost(host)

    // Same visible-proof pattern as trackersBlockedCount below.
    private val _adultSitesBlockedCount = MutableStateFlow(0)
    val adultSitesBlockedCount: StateFlow<Int> = _adultSitesBlockedCount.asStateFlow()

    fun onAdultContentBlocked(host: String) {
        _adultSitesBlockedCount.value += 1
    }

    // --- Sites that hand off to an external browser instead of loading in-app ---
    private val externalHandoffManager = ExternalHandoffManager(application)
    fun shouldOpenExternally(host: String): Boolean = externalHandoffManager.shouldOpenExternally(host)

    // --- Proton VPN status ---
    private val vpnStatusMonitor = VpnStatusMonitor(application)
    fun isProtonVpnInstalled(): Boolean = vpnStatusMonitor.isProtonVpnInstalled()

    private val _protonVpnActive = MutableStateFlow(vpnStatusMonitor.isProtonVpnActive())
    val protonVpnActive: StateFlow<Boolean> = _protonVpnActive.asStateFlow()

    // Session-only unlock: hidden tabs re-lock every time you leave the
    // hidden section or reopen the app — entering the PIN never persists.
    private val _hiddenTabsUnlocked = MutableStateFlow(false)
    val hiddenTabsUnlocked: StateFlow<Boolean> = _hiddenTabsUnlocked.asStateFlow()

    fun unlockHiddenTabs(pin: String): Boolean {
        val ok = pinManager.verifyPin(pin)
        if (ok) _hiddenTabsUnlocked.value = true
        return ok
    }

    fun relockHiddenTabs() {
        _hiddenTabsUnlocked.value = false
    }

    fun hideTab(id: String) {
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(isHidden = true) else it }
        if (_activeTabId.value == id) _activeTabId.value = null
    }

    fun unhideTab(id: String) {
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(isHidden = false) else it }
    }

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    // Domain of the active tab — only pages on this host load inside the
    // app; links to other domains open in the system browser instead.
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

    /** True when no tab is open — shows the URL-entry home screen. */
    val showHomeScreen: StateFlow<Boolean> = _activeTabId
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun openNewTab(rawUrl: String, incognito: Boolean = false) {
        val url = normalizeUrl(rawUrl)
        val tab = BrowserTab(id = UUID.randomUUID().toString(), url = url, isIncognito = incognito)
        _tabs.value = _tabs.value + tab
        switchToTab(tab.id)
    }

    /** Navigate the currently active tab to a new URL/search, e.g. from the address bar. */
    fun navigateActiveTab(rawInput: String) {
        val id = _activeTabId.value ?: return
        val url = normalizeUrl(rawInput)
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(url = url) else it }
        _currentUrl.value = url
        _isLoading.value = true
        _hasLoadedContentSuccessfully.value = false
    }

    fun toggleShieldForActiveTab() {
        val id = _activeTabId.value ?: return
        _tabs.value = _tabs.value.map { if (it.id == id) it.copy(isShieldOff = !it.isShieldOff) else it }
    }

    fun switchToTab(id: String) {
        _activeTabId.value = id
        _hiddenTabsUnlocked.value = false
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        _currentUrl.value = tab.url
        _isLoading.value = true
        _hasLoadedContentSuccessfully.value = false
        _errorMessage.value = null
        _isOffline.value = false
    }

    fun closeTab(id: String) {
        val closedTab = _tabs.value.firstOrNull { it.id == id }
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            val next = remaining.lastOrNull()
            if (next != null) switchToTab(next.id) else _activeTabId.value = null
        }
        // Note: WebView has a single shared cookie store across all tabs in
        // this app (Android doesn't give per-tab isolation without much
        // heavier engineering). So closing an Incognito tab clears cookies
        // for ALL tabs, not just that one — this is disclosed to the user
        // in the UI rather than silently only "half" honoring incognito.
        if (closedTab?.isIncognito == true) {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }
    }

    fun goHome() {
        _activeTabId.value = null
    }

    fun updateActiveTabMeta(url: String, title: String) {
        val id = _activeTabId.value ?: return
        _tabs.value = _tabs.value.map {
            if (it.id == id) it.copy(url = url, title = title.ifBlank { it.title }) else it
        }
    }

    private val repository: DownloadRepository by lazy {
        val database = AppDatabase.getDatabase(application)
        DownloadRepository(application, database.downloadDao())
    }

    private val bookmarkDao by lazy { AppDatabase.getDatabase(application).bookmarkDao() }

    val bookmarks: StateFlow<List<com.example.weblite.data.model.Bookmark>> by lazy {
        bookmarkDao.getAllBookmarks().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun addBookmark(url: String, title: String) {
        viewModelScope.launch {
            bookmarkDao.insert(com.example.weblite.data.model.Bookmark(url = url, title = title))
        }
    }

    fun removeBookmark(url: String) {
        viewModelScope.launch {
            bookmarkDao.deleteByUrl(url)
        }
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

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    // SharedPreferences changes don't emit on their own, so this counter is
    // bumped on every toggle to force isAlwaysExternalForCurrentUrl to
    // recompute — same trick as elsewhere in this file for prefs-backed state.
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

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _showSplashScreen = MutableStateFlow(true)
    val showSplashScreen: StateFlow<Boolean> = _showSplashScreen.asStateFlow()

    private val _hasLoadedContentSuccessfully = MutableStateFlow(false)
    val hasLoadedContentSuccessfully: StateFlow<Boolean> = _hasLoadedContentSuccessfully.asStateFlow()

    private val _isBannerOffline = MutableStateFlow(false)
    val isBannerOffline: StateFlow<Boolean> = _isBannerOffline.asStateFlow()

    // Visible proof that blocking is actually happening, since there's no
    // way to check dev-tools/network logs from inside a stripped-down
    // WebView. Counts for this app session only (resets on restart).
    private val _trackersBlockedCount = MutableStateFlow(0)
    val trackersBlockedCount: StateFlow<Int> = _trackersBlockedCount.asStateFlow()

    fun onTrackerBlocked() {
        _trackersBlockedCount.value += 1
    }

    init {
        // Auto-dismiss splash screen after a minimum smooth branding display duration
        viewModelScope.launch {
            delay(1800)
            _showSplashScreen.value = false
        }

        // Periodically sync download progress in background
        viewModelScope.launch {
            while (isActive) {
                try {
                    repository.syncActiveDownloads()
                } catch (_: Exception) {}
                delay(1200)
            }
        }

        // Periodically re-check Proton VPN status (there's no OS callback
        // for "a VPN connected/disconnected", so this is polled instead).
        viewModelScope.launch {
            while (isActive) {
                _protonVpnActive.value = vpnStatusMonitor.isProtonVpnActive()
                delay(3000)
            }
        }
    }

    fun startDownload(
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String?,
        cookie: String?
    ) {
        viewModelScope.launch {
            try {
                repository.enqueueDownload(url, fileName, mimeType, userAgent, cookie)
            } catch (_: Exception) {}
        }
    }

    fun deleteDownload(downloadItem: DownloadItem) {
        viewModelScope.launch {
            repository.deleteDownload(downloadItem)
        }
    }

    fun retryDownload(downloadItem: DownloadItem) {
        viewModelScope.launch {
            repository.retryDownload(downloadItem)
        }
    }

    fun toggleDownloadsSheet(show: Boolean) {
        _showDownloadsSheet.value = show
    }

    fun onPageStarted(url: String) {
        _currentUrl.value = url
        _isLoading.value = true
        _errorMessage.value = null
    }

    fun onProgressChanged(progress: Int) {
        _loadingProgress.value = progress
        if (progress >= 100) {
            _isLoading.value = false
            _isRefreshing.value = false
            _hasLoadedContentSuccessfully.value = true
            // Bug fix: a successful load means we're no longer offline —
            // clear the banner even if it was left on from an earlier error.
            _isBannerOffline.value = false
            _isOffline.value = false
        }
    }

    fun onPageFinished(url: String, canBack: Boolean) {
        _currentUrl.value = url
        _isLoading.value = false
        _isRefreshing.value = false
        _canGoBack.value = canBack
        _hasLoadedContentSuccessfully.value = true
        // Bug fix: same as above — page finished fine, so drop any
        // stale "offline" banner state.
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

    fun updateCanGoBack(canBack: Boolean) {
        _canGoBack.value = canBack
    }

    fun refreshPage() {
        _isRefreshing.value = true
        _errorMessage.value = null
        _isOffline.value = false
    }

    fun onRefreshHandled() {
        _isRefreshing.value = false
    }

    fun dismissSplash() {
        _showSplashScreen.value = false
    }
}

