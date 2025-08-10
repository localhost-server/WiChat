package com.wichat.android.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.wichat.android.model.BitchatMessage
import com.wichat.android.model.DeliveryStatus
import com.wichat.android.model.VoiceCallState
import com.wichat.android.ui.voice.*
import com.wichat.android.wifi.WifiMeshService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main ChatScreen - REFACTORED to use component-based architecture
 * This is now a coordinator that orchestrates the following UI components:
 * - ChatHeader: App bar, navigation, peer counter
 * - MessageComponents: Message display and formatting
 * - InputComponents: Message input and command suggestions
 * - SidebarComponents: Navigation drawer with channels and people
 * - DialogComponents: Password prompts and modals
 * - ChatUIUtils: Utility functions for formatting and colors
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val messages by viewModel.messages.observeAsState(emptyList())
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val nickname by viewModel.nickname.observeAsState("")
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.observeAsState()
    val currentChannel by viewModel.currentChannel.observeAsState()
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptySet())
    val hasUnreadChannels by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
    val privateChats by viewModel.privateChats.observeAsState(emptyMap())
    val channelMessages by viewModel.channelMessages.observeAsState(emptyMap())
    val showSidebar by viewModel.showSidebar.observeAsState(false)
    val showCommandSuggestions by viewModel.showCommandSuggestions.observeAsState(false)
    val commandSuggestions by viewModel.commandSuggestions.observeAsState(emptyList())
    val showAppInfo by viewModel.showAppInfo.observeAsState(false)
    
    // Voice call state
    val currentVoiceCallState by viewModel.state.currentVoiceCallState.observeAsState(VoiceCallState.IDLE)
    val currentVoiceCall by viewModel.state.currentVoiceCall.observeAsState()
    val incomingCallInfo by viewModel.state.incomingCallInfo.observeAsState()
    val isSpeakerphoneOn by viewModel.state.isSpeakerphoneOn.observeAsState(false)
    val isMuted by viewModel.state.isMuted.observeAsState(false)
    val microphoneLevel by viewModel.state.microphoneLevel.observeAsState(0f)
    
    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    
    // Show password dialog when needed
    LaunchedEffect(showPasswordPrompt) {
        showPasswordDialog = showPasswordPrompt
    }
    
    val isConnected by viewModel.isConnected.observeAsState(false)
    val passwordPromptChannel by viewModel.passwordPromptChannel.observeAsState(null)
    
    // Determine what messages to show
    val displayMessages = when {
        selectedPrivatePeer != null -> privateChats[selectedPrivatePeer] ?: emptyList()
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> messages
    }
    
    // Use WindowInsets to handle keyboard properly
    Box(modifier = Modifier.fillMaxSize()) {
        val headerHeight = 42.dp
        
        // Main content area that responds to keyboard/window insets
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .windowInsetsPadding(WindowInsets.ime) // This handles keyboard insets
        ) {
            // Header spacer - creates space for the floating header
            Spacer(modifier = Modifier.height(headerHeight))
            
            // Messages area - takes up available space, will compress when keyboard appears
            MessagesList(
                messages = displayMessages,
                currentUserNickname = nickname,
                meshService = viewModel.meshService,
                isPrivateChat = selectedPrivatePeer != null,
                modifier = Modifier.weight(1f)
            )
            
            // Input area - stays at bottom
            ChatInputSection(
                messageText = messageText,
                onMessageTextChange = { newText: TextFieldValue ->
                    messageText = newText
                    viewModel.updateCommandSuggestions(newText.text)
                },
                onSend = {
                    if (messageText.text.trim().isNotEmpty()) {
                        viewModel.sendMessage(messageText.text.trim())
                        messageText = TextFieldValue("")
                    }
                },
                showCommandSuggestions = showCommandSuggestions,
                commandSuggestions = commandSuggestions,
                onSuggestionClick = { suggestion: CommandSuggestion ->
                    val commandText = viewModel.selectCommandSuggestion(suggestion)
                    messageText = TextFieldValue(
                        text = commandText,
                        selection = TextRange(commandText.length)
                    )
                },
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                colorScheme = colorScheme
            )
        }
        
        // Floating header - positioned absolutely at top, ignores keyboard
        ChatFloatingHeader(
            headerHeight = headerHeight,
            selectedPrivatePeer = selectedPrivatePeer,
            currentChannel = currentChannel,
            nickname = nickname,
            viewModel = viewModel,
            colorScheme = colorScheme,
            onSidebarToggle = { viewModel.showSidebar() },
            onShowAppInfo = { viewModel.showAppInfo() },
            onPanicClear = { viewModel.panicClearAllData() }
        )
        
        // Sidebar overlay
        AnimatedVisibility(
            visible = showSidebar,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300, easing = EaseOutCubic)
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(250, easing = EaseInCubic)
            ) + fadeOut(animationSpec = tween(250)),
            modifier = Modifier.zIndex(2f) 
        ) {
            SidebarOverlay(
                viewModel = viewModel,
                onDismiss = { viewModel.hideSidebar() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    
    // Dialogs
    ChatDialogs(
        showPasswordDialog = showPasswordDialog,
        passwordPromptChannel = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = { passwordInput = it },
        onPasswordConfirm = {
            if (passwordInput.isNotEmpty()) {
                val success = viewModel.joinChannel(passwordPromptChannel!!, passwordInput)
                if (success) {
                    showPasswordDialog = false
                    passwordInput = ""
                }
            }
        },
        onPasswordDismiss = {
            showPasswordDialog = false
            passwordInput = ""
        },
        showAppInfo = showAppInfo,
        onAppInfoDismiss = { viewModel.hideAppInfo() }
    )
    
    // Voice Call Overlays
    VoiceCallOverlays(
        voiceCallState = currentVoiceCallState,
        currentVoiceCall = currentVoiceCall,
        incomingCallInfo = incomingCallInfo,
        isSpeakerphoneOn = isSpeakerphoneOn,
        isMuted = isMuted,
        microphoneLevel = microphoneLevel,
        onAnswerCall = { viewModel.answerVoiceCall() },
        onRejectCall = { viewModel.rejectVoiceCall() },
        onEndCall = { viewModel.endVoiceCall() },
        onToggleSpeakerphone = { viewModel.toggleSpeakerphone() },
        onToggleMute = { viewModel.toggleMute() },
        viewModel = viewModel
    )
}

@Composable
private fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    onSuggestionClick: (CommandSuggestion) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    colorScheme: ColorScheme
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colorScheme.background,
        shadowElevation = 8.dp
    ) {
        Column {
            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
            
            // Command suggestions box
            if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
                CommandSuggestionsBox(
                    suggestions = commandSuggestions,
                    onSuggestionClick = onSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }
            
            MessageInput(
                value = messageText,
                onValueChange = onMessageTextChange,
                onSend = onSend,
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatFloatingHeader(
    headerHeight: Dp,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    colorScheme: ColorScheme,
    onSidebarToggle: () -> Unit,
    onShowAppInfo: () -> Unit,
    onPanicClear: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .zIndex(1f)
            .windowInsetsPadding(WindowInsets.statusBars), // Only respond to status bar
        color = colorScheme.background.copy(alpha = 0.95f),
        shadowElevation = 8.dp
    ) {
        TopAppBar(
            title = {
                ChatHeaderContent(
                    selectedPrivatePeer = selectedPrivatePeer,
                    currentChannel = currentChannel,
                    nickname = nickname,
                    viewModel = viewModel,
                    onBackClick = {
                        when {
                            selectedPrivatePeer != null -> viewModel.endPrivateChat()
                            currentChannel != null -> viewModel.switchToChannel(null)
                        }
                    },
                    onSidebarClick = onSidebarToggle,
                    onTripleClick = onPanicClear,
                    onShowAppInfo = onShowAppInfo
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )
    }
    
    // Divider under header
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = headerHeight)
            .zIndex(1f),
        color = colorScheme.outline.copy(alpha = 0.3f)
    )
}

@Composable
private fun ChatDialogs(
    showPasswordDialog: Boolean,
    passwordPromptChannel: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirm: () -> Unit,
    onPasswordDismiss: () -> Unit,
    showAppInfo: Boolean,
    onAppInfoDismiss: () -> Unit
) {
    // Password dialog
    PasswordPromptDialog(
        show = showPasswordDialog,
        channelName = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = onPasswordChange,
        onConfirm = onPasswordConfirm,
        onDismiss = onPasswordDismiss
    )
    
    // App info dialog
    AppInfoDialog(
        show = showAppInfo,
        onDismiss = onAppInfoDismiss
    )
}

@Composable
private fun VoiceCallOverlays(
    voiceCallState: VoiceCallState,
    currentVoiceCall: Map<String, Any>?,
    incomingCallInfo: Triple<String, String, String>?, // nickname, peerID, callId
    isSpeakerphoneOn: Boolean,
    isMuted: Boolean,
    microphoneLevel: Float,
    onAnswerCall: () -> Unit,
    onRejectCall: () -> Unit,
    onEndCall: () -> Unit,
    onToggleSpeakerphone: () -> Unit,
    onToggleMute: () -> Unit,
    viewModel: ChatViewModel
) {
    // Incoming call overlay (highest priority)
    incomingCallInfo?.let { (callerNickname, _, _) ->
        IncomingCallOverlay(
            callerNickname = callerNickname,
            onAnswer = onAnswerCall,
            onReject = onRejectCall,
            modifier = Modifier.zIndex(10f)
        )
    }
    
    // Debug logging for call states
    LaunchedEffect(voiceCallState, currentVoiceCall, incomingCallInfo) {
        android.util.Log.d("ChatScreen", "Call State Debug - voiceCallState: $voiceCallState, currentVoiceCall: $currentVoiceCall, incomingCallInfo: $incomingCallInfo")
    }
    
    // Active call interface
    if (voiceCallState == VoiceCallState.ACTIVE && currentVoiceCall != null) {
        val peerID = currentVoiceCall["peerID"] as? String
        val fallbackNickname = currentVoiceCall["nickname"] as? String ?: "Unknown"
        // Use ViewModel to resolve actual nickname
        val peerNickname = peerID?.let { viewModel.resolvePeerNickname(it) } ?: fallbackNickname
        val startTime = currentVoiceCall["startTime"] as? Long ?: System.currentTimeMillis()
        
        // Live call duration timer that updates every second
        var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(startTime) {
            while (voiceCallState == VoiceCallState.ACTIVE) {
                currentTime = System.currentTimeMillis()
                delay(1000) // Update every second
            }
        }
        
        val callDuration = remember(currentTime, startTime) {
            val duration = maxOf(0, (currentTime - startTime) / 1000)
            val minutes = duration / 60
            val seconds = duration % 60
            "${minutes}:${String.format("%02d", seconds)}"
        }
        
        ActiveCallInterface(
            peerNickname = peerNickname,
            callDuration = callDuration,
            isSpeakerphoneOn = isSpeakerphoneOn,
            isMuted = isMuted,
            microphoneLevel = microphoneLevel,
            onToggleSpeakerphone = onToggleSpeakerphone,
            onToggleMute = onToggleMute,
            onEndCall = onEndCall,
            modifier = Modifier.zIndex(9f)
        )
    }
    
    // Connecting call interface (show connecting state)
    if (voiceCallState == VoiceCallState.CONNECTING && currentVoiceCall != null) {
        val peerID = currentVoiceCall["peerID"] as? String
        val fallbackNickname = currentVoiceCall["nickname"] as? String ?: "Unknown"
        val peerNickname = peerID?.let { viewModel.resolvePeerNickname(it) } ?: fallbackNickname
        
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).zIndex(9f),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Connecting to $peerNickname...",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(color = Color.White)
                Spacer(modifier = Modifier.height(32.dp))
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
    
    // Outgoing call interface (CALLING state only)  
    if (voiceCallState == VoiceCallState.CALLING && currentVoiceCall != null) {
        val peerID = currentVoiceCall["peerID"] as? String
        val fallbackNickname = currentVoiceCall["nickname"] as? String ?: "Unknown"
        val peerNickname = peerID?.let { viewModel.resolvePeerNickname(it) } ?: fallbackNickname
        
        OutgoingCallInterface(
            peerNickname = peerNickname,
            onEndCall = onEndCall,
            modifier = Modifier.zIndex(9f)
        )
    }
}
