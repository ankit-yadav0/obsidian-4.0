package com.example.weblite.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserTopBar(
    currentUrl: String,
    isShieldOff: Boolean,
    isBookmarked: Boolean,
    isHttps: Boolean,
    isAlwaysExternal: Boolean,
    isIncognito: Boolean,
    onNavigate: (String) -> Unit,
    onToggleShield: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenExternally: () -> Unit,
    onToggleAlwaysExternal: () -> Unit,
    onTabsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(currentUrl) }

    LaunchedEffect(currentUrl) {
        if (!isEditing) editText = currentUrl
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isIncognito) Color(0xFF2B1B3D) else Color(0xFF14151F))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isIncognito) {
            Icon(
                imageVector = Icons.Default.VisibilityOff,
                contentDescription = "Incognito tab active",
                tint = Color(0xFFCE93D8),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        IconButton(onClick = onToggleShield) {
            Icon(
                imageVector = if (isShieldOff) Icons.Default.GppMaybe else Icons.Default.GppGood,
                contentDescription = if (isShieldOff) "Shields off for this site" else "Shields on for this site",
                tint = if (isShieldOff) Color(0xFFFFA726) else Color(0xFF4CAF50)
            )
        }

        if (isEditing) {
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search or type a URL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    isEditing = false
                    onNavigate(editText)
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFE50914),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    cursorColor = Color(0xFFE50914)
                )
            )
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable {
                        editText = currentUrl
                        isEditing = true
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isHttps) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Secure",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = displayHost(currentUrl),
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
            }
        }

        IconButton(onClick = onToggleBookmark) {
            Icon(
                imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (isBookmarked) "Remove bookmark" else "Add bookmark",
                tint = if (isBookmarked) Color(0xFFFFC107) else Color.White.copy(alpha = 0.7f)
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    onClick = onOpenExternally,
                    onLongClick = onToggleAlwaysExternal
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.OpenInBrowser,
                contentDescription = if (isAlwaysExternal) {
                    "Always opens in external browser — tap to open now, long-press to change"
                } else {
                    "Open in external browser — long-press to always open this site externally"
                },
                tint = if (isAlwaysExternal) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.8f)
            )
        }

        IconButton(onClick = onTabsClick) {
            Icon(Icons.Default.Layers, contentDescription = "Tabs", tint = Color.White.copy(alpha = 0.8f))
        }
    }
}

private fun displayHost(url: String): String {
    return try {
        val uri = android.net.Uri.parse(url)
        (uri.host ?: url) + (uri.path?.takeIf { it.isNotEmpty() && it != "/" } ?: "")
    } catch (e: Exception) {
        url
    }
}
