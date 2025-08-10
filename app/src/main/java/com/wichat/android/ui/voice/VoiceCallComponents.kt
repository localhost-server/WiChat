package com.wichat.android.ui.voice

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wichat.android.model.VoiceCallState

/**
 * Incoming call notification overlay
 */
@Composable
fun IncomingCallOverlay(
    callerNickname: String,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulseAnimation = rememberInfiniteTransition(label = "pulse")
    val scale by pulseAnimation.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Call icon with pulse animation
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = "Incoming Call",
                    modifier = Modifier
                        .size(64.dp)
                        .scale(scale),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    text = "Incoming Voice Call",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = callerNickname,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reject button
                    FloatingActionButton(
                        onClick = onReject,
                        containerColor = Color.Red,
                        contentColor = Color.White,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Reject Call",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    // Answer button
                    FloatingActionButton(
                        onClick = onAnswer,
                        containerColor = Color.Green,
                        contentColor = Color.White,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Answer Call",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Active call interface
 */
@Composable
fun ActiveCallInterface(
    peerNickname: String,
    callDuration: String,
    isSpeakerphoneOn: Boolean,
    isMuted: Boolean,
    microphoneLevel: Float,
    onToggleSpeakerphone: () -> Unit,
    onToggleMute: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            // Peer info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Avatar placeholder
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = peerNickname.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 36.sp
                        ),
                        color = Color.White
                    )
                }
                
                Text(
                    text = peerNickname,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White
                )
                
                Text(
                    text = callDuration,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
            
            // Microphone level indicator
            MicrophoneLevelIndicator(
                level = microphoneLevel,
                modifier = Modifier.size(width = 200.dp, height = 20.dp)
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Call controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute toggle
                FloatingActionButton(
                    onClick = onToggleMute,
                    containerColor = if (isMuted) Color.Red else Color.Gray,
                    contentColor = Color.White,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "Unmute Microphone" else "Mute Microphone",
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // Speakerphone toggle
                FloatingActionButton(
                    onClick = onToggleSpeakerphone,
                    containerColor = if (isSpeakerphoneOn) MaterialTheme.colorScheme.primary else Color.Gray,
                    contentColor = Color.White,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (isSpeakerphoneOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "Toggle Speakerphone",
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // End call button
                FloatingActionButton(
                    onClick = onEndCall,
                    containerColor = Color.Red,
                    contentColor = Color.White,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

/**
 * Outgoing call interface
 */
@Composable
fun OutgoingCallInterface(
    peerNickname: String,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulseAnimation = rememberInfiniteTransition(label = "calling_pulse")
    val alpha by pulseAnimation.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            // Avatar placeholder with animation
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peerNickname.take(2).uppercase(),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 36.sp
                    ),
                    color = Color.White
                )
            }
            
            Text(
                text = peerNickname,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
            
            Text(
                text = "Calling...",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = Color.White.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // End call button
            FloatingActionButton(
                onClick = onEndCall,
                containerColor = Color.Red,
                contentColor = Color.White,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "End Call",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

/**
 * Microphone level indicator
 */
@Composable
fun MicrophoneLevelIndicator(
    level: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Gray.copy(alpha = 0.3f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when {
                        level > 0.8f -> Color.Red
                        level > 0.5f -> Color.Yellow
                        level > 0.2f -> Color.Green
                        else -> Color.Gray
                    }
                )
        )
    }
}

/**
 * Call button for initiating calls
 */
@Composable
fun CallButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = if (enabled) Color.Green else Color.Gray,
        contentColor = Color.White,
        modifier = modifier.size(56.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Call,
            contentDescription = "Start Call",
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Voice call state indicator
 */
@Composable
fun VoiceCallStateIndicator(
    state: VoiceCallState,
    modifier: Modifier = Modifier
) {
    if (state == VoiceCallState.IDLE) return
    
    Card(
        modifier = modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                VoiceCallState.ACTIVE -> Color.Green
                VoiceCallState.RINGING, VoiceCallState.CALLING -> Color(0xFFFF9500)
                VoiceCallState.CONNECTING -> Color.Blue
                VoiceCallState.ENDING -> Color.Red
                else -> Color.Gray
            }.copy(alpha = 0.8f)
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = when (state) {
                    VoiceCallState.ACTIVE -> Icons.Default.Phone
                    VoiceCallState.RINGING -> Icons.Default.PhoneAndroid
                    VoiceCallState.CALLING -> Icons.Default.PhoneInTalk
                    VoiceCallState.CONNECTING -> Icons.Default.PhoneCallback
                    VoiceCallState.ENDING -> Icons.Default.CallEnd
                    else -> Icons.Default.Phone
                },
                contentDescription = state.name,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            
            Text(
                text = when (state) {
                    VoiceCallState.ACTIVE -> "In Call"
                    VoiceCallState.RINGING -> "Incoming"
                    VoiceCallState.CALLING -> "Calling"
                    VoiceCallState.CONNECTING -> "Connecting"
                    VoiceCallState.ENDING -> "Ending"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
        }
    }
}