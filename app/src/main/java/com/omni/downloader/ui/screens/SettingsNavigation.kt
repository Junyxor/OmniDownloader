package com.omni.downloader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.omni.downloader.ui.design.LocalGlassEnabled
import com.omni.downloader.ui.design.glassChrome
import com.omni.downloader.ui.design.GlassIconButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omni.downloader.ui.localization.AppStrings
import com.omni.downloader.ui.localization.LocalAppStrings

internal enum class SettingsPage {
    ROOT, APPEARANCE, DOWNLOADS, NETWORK, RELAY, ACCOUNTS, ENGINE, ABOUT;

    fun title(s: AppStrings): String = when (this) {
        ROOT -> s.settingsTitle
        APPEARANCE -> s.settingsAppearanceTitle
        DOWNLOADS -> s.settingsDownloadsTitle
        NETWORK -> s.settingsNetworkTitle
        RELAY -> s.settingsRelayTitle
        ACCOUNTS -> s.settingsAccountsTitle
        ENGINE -> s.settingsEngineTitle
        ABOUT -> s.settingsAboutTitle
    }

    fun summary(s: AppStrings): String = when (this) {
        APPEARANCE -> s.settingsAppearanceSummary
        DOWNLOADS -> s.settingsDownloadsSummary
        NETWORK -> s.settingsNetworkSummary
        RELAY -> s.settingsRelaySummary
        ACCOUNTS -> s.settingsAccountsSummary
        ENGINE -> s.settingsEngineSummary
        ABOUT -> s.settingsAboutSummary
        ROOT -> ""
    }

    val icon: ImageVector get() = when (this) {
        APPEARANCE -> Icons.Outlined.Palette
        DOWNLOADS -> Icons.Outlined.Folder
        NETWORK -> Icons.Outlined.Language
        RELAY -> Icons.Outlined.Dns
        ACCOUNTS -> Icons.Outlined.AccountCircle
        ENGINE -> Icons.Outlined.CloudDownload
        else -> Icons.Outlined.Info
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsToolbar(page: SettingsPage, onBack: () -> Unit) {
    val strings = LocalAppStrings.current
    TopAppBar(
        title = { Text(page.title(strings), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (page != SettingsPage.ROOT) GlassIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, strings.navigateBack)
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = if (LocalGlassEnabled.current) Color.Transparent else MaterialTheme.colorScheme.background)
    )
}

@Composable
internal fun SettingsCategoryRow(page: SettingsPage, onClick: () -> Unit) {
    val strings = LocalAppStrings.current
    ListItem(
        headlineContent = { Text(page.title(strings)) },
        supportingContent = { Text(page.summary(strings), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(page.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp)) },
        colors = ListItemDefaults.colors(containerColor = if (LocalGlassEnabled.current) Color.Transparent else MaterialTheme.colorScheme.background),
        modifier = Modifier
            .padding(horizontal = if (LocalGlassEnabled.current) 14.dp else 0.dp, vertical = if (LocalGlassEnabled.current) 5.dp else 0.dp)
            .glassChrome(RoundedCornerShape(20.dp))
            .testTag("settings_category_" + page.name)
            .clickable(role = Role.Button, onClick = onClick)
    )
}
