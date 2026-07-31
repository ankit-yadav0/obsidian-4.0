package com.example.weblite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.weblite.viewmodel.BrowserTab

@Composable
fun HomeScreen(
    openTabs: List<BrowserTab>,
    hiddenTabsUnlocked: Boolean,
    isPinSet: Boolean,
    trackersBlockedCount: Int,
    bookmarks: List<com.example.weblite.data.model.Bookmark>,
    torEnabled: Boolean,
    torStatusMessage: String?,
    isOrbotInstalled: Boolean,
    onOpenUrl: (String) -> Unit,
    onOpenIncognito: (String) -> Unit,
    onOpenPrivacyDashboard: () -> Unit,
    onOpenBookmark: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onToggleTor: (Boolean) -> Unit,
    onResumeTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onHideTab: (String) -> Unit,
    onUnhideTab: (String) -> Unit,
    onSetPin: (String) -> Unit,
    onUnlockAttempt: (String) -> Boolean,
    onRelock: () -> Unit,
    modifier: Modifier = Modifier
) {
    var urlText by remember { mutableStateOf("") }
    var showSetPinDialog by remember { mutableStateOf(false) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var pendingHideTabId by remember { mutableStateOf<String?>(null) }

    val visibleTabs = openTabs.filter { !it.isHidden }
    val hiddenTabs = openTabs.filter { it.isHidden }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E15))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            text = "Obsidian",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Enter a website to open it full screen",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp
        )

        if (trackersBlockedCount > 0) {
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onOpenPrivacyDashboard() }
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier
                        .size(14.dp)
                        .padding(end = 4.dp)
                )
                Text(
                    text = "$trackersBlockedCount ads/trackers blocked this session — tap to see details",
                    color = Color(0xFF4CAF50),
                    fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (urlText.isNotBlank()) onOpenUrl(urlText) }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFE50914),
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                cursorColor = Color(0xFFE50914)
            )
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { if (urlText.isNotBlank()) onOpenUrl(urlText) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Done", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = { if (urlText.isNotBlank()) onOpenIncognito(urlText) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.width(6.dp))
            Text("Open in Incognito", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp)
        ) {
            if (visibleTabs.isNotEmpty()) {
                item {
                    Text(
                        text = "Open tabs",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                }
                items(visibleTabs, key = { it.id }) { tab ->
                    TabRow(
                        tab = tab,
                        onClick = { onResumeTab(tab.id) },
                        onClose = { onCloseTab(tab.id) },
                        trailingAction = {
                            IconButton(onClick = {
                                if (!isPinSet) {
                                    pendingHideTabId = tab.id
                                    showSetPinDialog = true
                                } else {
                                    onHideTab(tab.id)
                                }
                            }) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = "Hide tab", tint = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    )
                }
            }

            if (hiddenTabs.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(if (visibleTabs.isNotEmpty()) 16.dp else 0.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                            .clickable {
                                if (hiddenTabsUnlocked) onRelock() else showUnlockDialog = true
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (hiddenTabsUnlocked) "Hidden tabs (${hiddenTabs.size}) — tap to lock"
                            else "Hidden tabs (${hiddenTabs.size}) — enter PIN",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (hiddenTabsUnlocked) {
                    items(hiddenTabs, key = { it.id }) { tab ->
                        TabRow(
                            tab = tab,
                            onClick = { onResumeTab(tab.id) },
                            onClose = { onCloseTab(tab.id) },
                            trailingAction = {
                                TextButton(onClick = { onUnhideTab(tab.id) }) {
                                    Text("Unhide", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                }
                            }
                        )
                    }
                }
            }

            if (bookmarks.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Bookmarks",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    )
                }
                items(bookmarks, key = { it.id }) { bookmark ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                            .clickable { onOpenBookmark(bookmark.url) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFC107))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = bookmark.title.ifBlank { bookmark.url },
                            color = Color.White,
                            fontSize = 14.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDeleteBookmark(bookmark.url) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove bookmark", tint = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = if (torEnabled) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tor routing (via Orbot)", color = Color.White, fontSize = 14.sp)
                        if (!isOrbotInstalled) {
                            Text("Orbot isn't installed", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        } else if (torStatusMessage != null) {
                            Text(torStatusMessage, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        }
                    }
                    Switch(
                        checked = torEnabled,
                        onCheckedChange = { onToggleTor(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50))
                    )
                }
            }
        }
    }

    if (showSetPinDialog) {
        SetPinDialog(
            onDismiss = { showSetPinDialog = false; pendingHideTabId = null },
            onConfirm = { pin ->
                onSetPin(pin)
                pendingHideTabId?.let { onHideTab(it) }
                showSetPinDialog = false
                pendingHideTabId = null
            }
        )
    }

    if (showUnlockDialog) {
        UnlockPinDialog(
            onDismiss = { showUnlockDialog = false },
            onSubmit = { pin ->
                val ok = onUnlockAttempt(pin)
                if (ok) showUnlockDialog = false
                ok
            }
        )
    }
}

@Composable
private fun TabRow(
    tab: BrowserTab,
    onClick: () -> Unit,
    onClose: () -> Unit,
    trailingAction: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Language, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.width(10.dp))
        Text(
            text = tab.title.ifBlank { tab.url },
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        trailingAction()
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close tab", tint = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun SetPinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a 6-digit PIN") },
        text = {
            Column {
                Text(
                    "This PIN protects your hidden tabs. Choose 6 digits you'll remember — it can't be recovered if you forget it.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                    label = { Text("New PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) confirmPin = it },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE50914), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length != 6 -> error = "PIN must be 6 digits"
                    pin != confirmPin -> error = "PINs don't match"
                    else -> onConfirm(pin)
                }
            }) { Text("Set PIN") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun UnlockPinDialog(onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                    label = { Text("6-digit PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE50914), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pin.length != 6) {
                    error = "Enter all 6 digits"
                } else if (!onSubmit(pin)) {
                    error = "Incorrect PIN"
                    pin = ""
                }
            }) { Text("Unlock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
