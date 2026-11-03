package com.example.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app.ui.theme.Orange
import com.example.app.ui.theme.StatusGrey
import com.example.app.ui.theme.Teal
import com.example.app.ui.theme.TealLight

@Composable
fun ReBandLogo(modifier: Modifier = Modifier, large: Boolean = false) {
    val badge = if (large) 48.dp else 34.dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(badge).clip(RoundedCornerShape(if (large) 14.dp else 10.dp)).background(Teal),
            contentAlignment = Alignment.Center,
        ) {
            Text("R", color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (large) 26.sp else 19.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Teal)) { append("Re") }
                withStyle(SpanStyle(color = Orange)) { append("Band") }
            },
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 56.dp,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(16.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun ReBandCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) { content() }
}

@Composable
fun IconBadge(icon: ImageVector, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(TealLight),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Teal, modifier = Modifier.size(size / 2))
    }
}

/**
 * Wearable connection status. BLE is not on this branch yet, so callers use the default
 * `connected = false`. Pass the real BLE connection state here once it is merged.
 */
@Composable
fun WearableStatusCard(modifier: Modifier = Modifier, connected: Boolean = false) {
    ReBandCard(modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(ReBandIcons.Bluetooth)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    if (connected) "Wearable: Connected" else "Wearable: Not Connected",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (connected) "Your ReBand is ready" else "Device pairing coming soon",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) Teal else StatusGrey))
        }
    }
}

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = modifier)
}

@Composable
fun ComingSoon(title: String, icon: ImageVector, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, size = 96.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            "Coming Soon",
            style = MaterialTheme.typography.labelLarge,
            color = Orange,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Orange.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
