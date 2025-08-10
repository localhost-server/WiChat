package com.wichat.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.wichat.android.model.BitchatMessage
import com.wichat.android.wifi.WifiMeshService
import androidx.compose.material3.ColorScheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility functions for ChatScreen UI components
 * Extracted from ChatScreen.kt for better organization
 */

/**
 * Get RSSI-based color for signal strength visualization
 */
fun getRSSIColor(rssi: Int): Color {
    return when {
        rssi >= -50 -> Color(0xFF00FF00) // Bright green
        rssi >= -60 -> Color(0xFF80FF00) // Green-yellow
        rssi >= -70 -> Color(0xFFFFFF00) // Yellow
        rssi >= -80 -> Color(0xFFFF8000) // Orange
        else -> Color(0xFFFF4444) // Red
    }
}

/**
 * Format message as annotated string with proper styling
 */
fun formatMessageAsAnnotatedString(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: WifiMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    
    if (message.sender != "system") {
        // Timestamp (without square brackets)
        val timestampColor = colorScheme.primary.copy(alpha = 0.5f)
        builder.pushStyle(SpanStyle(
            color = timestampColor,
            fontSize = 11.sp
        ))
        builder.append("${timeFormatter.format(message.timestamp)} ")
        builder.pop()
        
        // Message content with mentions and hashtags highlighted (no username)
        appendFormattedContent(builder, message.content, message.mentions, currentUserNickname, colorScheme)
        
    } else {
        // System message
        builder.pushStyle(SpanStyle(
            color = Color.Gray,
            fontSize = 12.sp,
            fontStyle = FontStyle.Italic
        ))
        builder.append("* ${message.content} *")
        builder.pop()
    }
    
    return builder.toAnnotatedString()
}

/**
 * Append formatted content with hashtag and mention highlighting
 */
private fun appendFormattedContent(
    builder: AnnotatedString.Builder,
    content: String,
    mentions: List<String>?,
    currentUserNickname: String,
    colorScheme: ColorScheme
) {
    val isMentioned = mentions?.contains(currentUserNickname) == true
    
    // Parse hashtags and mentions
    val hashtagPattern = "#([a-zA-Z0-9_]+)".toRegex()
    val mentionPattern = "@([a-zA-Z0-9_]+)".toRegex()
    
    val hashtagMatches = hashtagPattern.findAll(content).toList()
    val mentionMatches = mentionPattern.findAll(content).toList()
    
    // If content is empty or only whitespace, add it as-is
    if (content.isBlank()) {
        builder.pushStyle(SpanStyle(
            color = colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = if (isMentioned) FontWeight.Bold else FontWeight.Normal
        ))
        builder.append(content)
        builder.pop()
        return
    }
    
    // Combine and sort all matches
    val allMatches = (hashtagMatches.map { it.range to "hashtag" } + 
                     mentionMatches.map { it.range to "mention" })
        .sortedBy { it.first.first }
    
    var lastEnd = 0
    
    // If no special matches, add all content as regular text
    if (allMatches.isEmpty()) {
        builder.pushStyle(SpanStyle(
            color = colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = if (isMentioned) FontWeight.Bold else FontWeight.Normal
        ))
        builder.append(content)
        builder.pop()
        return
    }
    
    for ((range, type) in allMatches) {
        // Add text before the match
        if (lastEnd < range.first) {
            val beforeText = content.substring(lastEnd, range.first)
            builder.pushStyle(SpanStyle(
                color = colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = if (isMentioned) FontWeight.Bold else FontWeight.Normal
            ))
            builder.append(beforeText)
            builder.pop()
        }
        
        // Add the styled match
        val matchText = content.substring(range.first, range.last + 1)
        when (type) {
            "hashtag" -> {
                builder.pushStyle(SpanStyle(
                    color = Color(0xFF0080FF), // Blue
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                ))
            }
            "mention" -> {
                builder.pushStyle(SpanStyle(
                    color = Color(0xFFFF9500), // Orange
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                ))
            }
        }
        builder.append(matchText)
        builder.pop()
        
        lastEnd = range.last + 1
    }
    
    // Add remaining text after the last match
    if (lastEnd < content.length) {
        val remainingText = content.substring(lastEnd)
        builder.pushStyle(SpanStyle(
            color = colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = if (isMentioned) FontWeight.Bold else FontWeight.Normal
        ))
        builder.append(remainingText)
        builder.pop()
    }
}
