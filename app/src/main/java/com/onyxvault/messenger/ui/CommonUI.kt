package com.onyxvault.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onyxvault.messenger.ui.theme.OnyxTurquoise

@Composable
fun WipeOverlay(progress: Float, status: String) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(progress = { progress }, color = OnyxTurquoise, strokeWidth = 6.dp, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = OnyxTurquoise, fontWeight = FontWeight.ExtraBold)) { append("ONYX ") }
                    withStyle(style = SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) { append("VAULT ") }
                    withStyle(style = SpanStyle(color = OnyxTurquoise, fontWeight = FontWeight.ExtraBold)) { append("REINIGUNG") }
                },
                fontSize = 18.sp
            )
            Text(status, color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp)
        }
    }
}

@Composable
fun KillSwitchProgressOverlay(progress: Float, status: String) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A0000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Shield, null, tint = Color.Red, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                text = buildAnnotatedString {
                    withStyle(style = SpanStyle(color = Color.Red, fontWeight = FontWeight.Bold)) { append("VERNICHTUNG LÄUFT") }
                },
                fontSize = 20.sp
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { progress }, color = Color.Red, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), trackColor = Color.DarkGray)
            Spacer(Modifier.height(8.dp))
            Text(status, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}
