package com.wichat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wichat.android.model.BitchatMessage
import com.wichat.android.model.DeliveryStatus
import com.wichat.android.wifi.WifiMeshService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: WifiMeshService,
    isPrivateChat: Boolean = false,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    // Removed SelectionContainer to enable single-click instead of double-click
    LazyColumn(
            state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), // Reduced vertical padding
        verticalArrangement = Arrangement.spacedBy((-1).dp), // Negative spacing for tighter layout
        modifier = modifier
    ) {
        items(messages.size) { index ->
            val message = messages[index]
            val previousMessage = if (index > 0) messages[index - 1] else null
            val showUsername = shouldShowUsername(message, previousMessage, currentUserNickname, isPrivateChat)
            
            MessageItem(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                showUsername = showUsername
            )
        }
    }
}

/**
 * Determines if username should be shown for this message based on smart grouping
 */
private fun shouldShowUsername(
    message: BitchatMessage,
    previousMessage: BitchatMessage?,
    currentUserNickname: String,
    isPrivateChat: Boolean
): Boolean {
    // Never show username for system messages or messages sent by current user
    if (message.sender == "system" || message.sender == currentUserNickname) {
        return false
    }
    
    // Never show usernames in private chats
    if (isPrivateChat) {
        return false
    }
    
    // In group chats, show username for first message or if sender changed
    return previousMessage == null || previousMessage.sender != message.sender
}

@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: WifiMeshService,
    showUsername: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isSentByMe = message.sender == currentUserNickname
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 0.dp)
    ) {
        // Show username for received messages when needed
        if (showUsername && !isSentByMe && message.sender != "system") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = "@${message.sender}",
                    fontSize = 10.sp,
                    color = Color.Red,
                    modifier = Modifier.padding(start = 4.dp),
                    lineHeight = 10.sp // Tight line height
                )
            }
        }
        
        // Message content row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isSentByMe) Arrangement.End else Arrangement.Start
        ) {
            if (isSentByMe) {
                // Sent messages: delivery status + message on the right
                if (message.isPrivate) {
                    message.deliveryStatus?.let { status ->
                        DeliveryStatusIcon(
                            status = status,
                            modifier = Modifier
                                .align(Alignment.Bottom)
                                .padding(end = 4.dp)
                        )
                    }
                }
                
                Text(
                    text = formatMessageAsAnnotatedString(
                        message = message,
                        currentUserNickname = currentUserNickname,
                        meshService = meshService,
                        colorScheme = colorScheme,
                        timeFormatter = timeFormatter
                    ),
                    modifier = Modifier
                        .widthIn(min = 90.dp, max = 280.dp)
                        .clickable { 
                            // Single-click message interaction
                            android.util.Log.d("MessageClick", "Clicked message: ${message.content}")
                        },
                    fontFamily = FontFamily.Monospace,
                    softWrap = true,
                    overflow = TextOverflow.Visible
                )
            } else {
                // Received messages: message on the left
                Text(
                    text = formatMessageAsAnnotatedString(
                        message = message,
                        currentUserNickname = currentUserNickname,
                        meshService = meshService,
                        colorScheme = colorScheme,
                        timeFormatter = timeFormatter
                    ),
                    modifier = Modifier
                        .widthIn(min = 90.dp, max = 280.dp)
                        .clickable { 
                            // Single-click message interaction
                            android.util.Log.d("MessageClick", "Clicked message: ${message.content}")
                        },
                    fontFamily = FontFamily.Monospace,
                    softWrap = true,
                    overflow = TextOverflow.Visible
                )
            }
        }
    }
}

@Composable
fun DeliveryStatusIcon(
    status: DeliveryStatus,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    when (status) {
        is DeliveryStatus.Sending -> {
            Text(
                text = "○",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f),
                modifier = modifier
            )
        }
        is DeliveryStatus.Sent -> {
            Text(
                text = "✓",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f),
                modifier = modifier
            )
        }
        is DeliveryStatus.Delivered -> {
            Text(
                text = "✓",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f),
                modifier = modifier
            )
        }
        is DeliveryStatus.Read -> {
            Text(
                text = "✓",
                fontSize = 10.sp,
                color = Color(0xFF007AFF), // Blue
                fontWeight = FontWeight.Bold,
                modifier = modifier
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = "⚠",
                fontSize = 10.sp,
                color = Color.Red.copy(alpha = 0.8f),
                modifier = modifier
            )
        }
        is DeliveryStatus.PartiallyDelivered -> {
            Text(
                text = "✓${status.reached}/${status.total}",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}
