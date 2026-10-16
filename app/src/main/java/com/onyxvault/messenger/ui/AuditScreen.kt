package com.onyxvault.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onyxvault.messenger.OnyxVaultApp
import com.onyxvault.messenger.data.AuditEntity
import com.onyxvault.messenger.security.AuditManager
import com.onyxvault.messenger.ui.theme.OnyxTurquoise

/**
 * AuditScreen – ONYX VAULT Forensik-Log
 * Zeigt alle sicherheitskritischen Ereignisse an.
 * FIX: Lösch-Funktion hinzugefügt, um Fehlalarme zu entfernen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OnyxVaultApp
    val logs by app.auditManager.getAllLogs().collectAsState(initial = emptyList())
    var showConfirmDelete by remember { mutableStateOf(false) }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("LOGS REINIGEN?") },
            text = { Text("Alle forensischen Einträge werden unwiderruflich aus der Datenbank gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    app.auditManager.clearLogs()
                    showConfirmDelete = false
                }) { Text("LÖSCHEN", color = Color.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) { Text("ABBRECHEN") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FORENSIK AUDIT LOG", fontWeight = FontWeight.Black, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = OnyxTurquoise)
                    }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = { showConfirmDelete = true }) {
                            Icon(Icons.Default.DeleteSweep, "Logs löschen", tint = Color.Gray)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color.Black)
        ) {
            if (logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Keine forensischen Ereignisse protokolliert.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs) { log ->
                        AuditLogItemRow(log)
                    }
                }
            }
        }
    }
}

@Composable
fun AuditLogItemRow(log: AuditEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (log.severity) {
                "CRITICAL" -> Color(0xFF440000)
                "WARNING"  -> Color(0xFF332200)
                else       -> Color(0xFF111111)
            }
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (log.severity == "CRITICAL" || log.severity == "WARNING") 
                                  Icons.Default.Warning else Icons.Default.Security,
                    contentDescription = null,
                    tint = when (log.severity) {
                        "CRITICAL" -> Color.Red
                        "WARNING"  -> Color(0xFFFFCC00)
                        else       -> OnyxTurquoise
                    },
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    log.category,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (log.severity == "CRITICAL") Color.Red else OnyxTurquoise
                )
                Spacer(Modifier.weight(1f))
                Text(
                    AuditManager.formatTime(log.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(log.message, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Text(log.deviceInfo, style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
        }
    }
}
