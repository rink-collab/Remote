package com.example.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ConnectionStatus
import com.example.ui.theme.Amber500
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald500
import com.example.ui.theme.Rose500
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate800

@Composable
fun ConnectionBadge(
    status: ConnectionStatus,
    modifier: Modifier = Modifier
) {
    val (bgColor, dotColor, text) = when (status) {
        ConnectionStatus.IDLE -> Triple(Slate800.copy(alpha = 0.6f), Slate600, "Idle")
        ConnectionStatus.CONNECTING_FIREBASE -> Triple(Amber500.copy(alpha = 0.15f), Amber500, "Signaling...")
        ConnectionStatus.WAITING_FOR_PEER -> Triple(Cyan400.copy(alpha = 0.15f), Cyan400, "Waiting for Peer")
        ConnectionStatus.CONNECTING_WEBRTC -> Triple(Amber500.copy(alpha = 0.15f), Amber500, "WebRTC Handshake")
        ConnectionStatus.CONNECTED -> Triple(Emerald500.copy(alpha = 0.15f), Emerald500, "P2P Connected")
        ConnectionStatus.DISCONNECTED -> Triple(Slate800.copy(alpha = 0.6f), Slate600, "Disconnected")
        ConnectionStatus.ERROR -> Triple(Rose500.copy(alpha = 0.15f), Rose500, "Connection Error")
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.testTag("connection_badge")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .alpha(if (status == ConnectionStatus.CONNECTING_FIREBASE || status == ConnectionStatus.CONNECTING_WEBRTC || status == ConnectionStatus.WAITING_FOR_PEER) pulseAlpha else 1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = dotColor
                )
            )
        }
    }
}

@Composable
fun MediaThumbnail(
    base64String: String?,
    isVideo: Boolean,
    durationText: String,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(base64String) {
        if (!base64String.isNullOrEmpty()) {
            try {
                val bytes = Base64.decode(base64String, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        } else null
    }

    Box(
        modifier = modifier
            .background(Slate800)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = if (isVideo) "Video thumbnail" else "Photo thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Skeleton Placeholder
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = Slate600,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Video Badge
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    if (durationText.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }
        }
    }
}
