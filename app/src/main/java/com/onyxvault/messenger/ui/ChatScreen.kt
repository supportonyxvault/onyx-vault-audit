package com.onyxvault.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onyxvault.messenger.chat.ChatManager
import com.onyxvault.messenger.ui.theme.OnyxGreen
import com.onyxvault.messenger.ui.theme.OnyxTurquoise

/**
 * ChatScreen – ONYX VAULT (Forensic sovereign Edition)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatPartner:  String,
    chatManager:  ChatManager,
    onBackPressed: () -> Unit
) {
    val listState = rememberLazyListState()
    val messages by chatManager.getMessagesForChatFlow(chatPartner).collectAsState(initial = emptyList())
    
    var messageText      by remember { mutableStateOf("") }
    var selectedTimer    by remember { mutableStateOf(ChatManager.SelfDestructTime.SECONDS_60) }
    var showTimerPicker  by remember { mutableStateOf(false) }
    var showPinDialog    by remember { mutableStateOf(false) }
    var currentPin       by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("@$chatPartner", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text("Souveräne Verbindung", style = MaterialTheme.typography.labelSmall, color = OnyxTurquoise)
                    }
                },
                navigationIcon = { IconButton(onClick = onBackPressed) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = OnyxTurquoise) } },
                actions = {
                    IconButton(onClick = { showTimerPicker = !showTimerPicker }) {
                        Icon(Icons.Default.Timer, null, tint = OnyxTurquoise)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showTimerPicker) {
                Surface(color = Color(0xFF111111)) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ChatManager.SelfDestructTime.entries.forEach { t ->
                            FilterChip(
                                selected = selectedTimer == t,
                                onClick  = { selectedTimer = t; showTimerPicker = false },
                                label    = { Text(t.label, color = if(selectedTimer == t) Color.Black else Color.Gray) },
                                colors   = FilterChipDefaults.filterChipColors(selectedContainerColor = OnyxTurquoise)
                            )
                        }
                    }
                }
            }

            LazyColumn(
                state    = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { msg -> 
                    MessageBubble(msg)
                }
            }

            Surface(color = Color.Black) {
                Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding().imePadding(), verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = { showPinDialog = true }) {
                        Icon(Icons.Default.Lock, null, tint = if (currentPin != null) OnyxTurquoise else Color.Gray)
                    }
                    TextField(
                        value = messageText, onValueChange = { messageText = it },
                        placeholder = { Text("Nachricht...", color = Color.Gray) },
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF111111), unfocusedContainerColor = Color(0xFF111111), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Spacer(Modifier.width(6.dp))
                    FloatingActionButton(
                        onClick = {
                            val text = messageText.trim()
                            if (text.isNotBlank()) {
                                chatManager.sendMessage(chatPartner, text, ChatManager.MessageOptions(selectedTimer, currentPin))
                                messageText = ""; currentPin = null
                            }
                        },
                        modifier = Modifier.size(46.dp), shape = CircleShape, containerColor = OnyxTurquoise
                    ) { Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatManager.CachedMessage) {
    val isMe = msg.senderUsername == "Me"
    val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isMe) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp) else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp), contentAlignment = alignment) {
        Column(Modifier.widthIn(max = 280.dp).clip(shape).background(if (isMe) Color(0xFF004D40) else Color(0xFF111111)).border(1.dp, if(isMe) OnyxTurquoise.copy(alpha = 0.3f) else Color.DarkGray, shape).padding(10.dp)) {
            Text(msg.content, color = Color.White, fontSize = 14.sp)
        }
    }
}
