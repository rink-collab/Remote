package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        ConnectionStatus.CONNECTING_FIREBASE -> Triple(Amber500.copy(alpha = 0.15f), Amber500, "Broadcasting...")
        ConnectionStatus.WAITING_FOR_PEER -> Triple(Cyan400.copy(alpha = 0.15f), Cyan400, "Ready for Client")
        ConnectionStatus.CONNECTING_WEBRTC -> Triple(Amber500.copy(alpha = 0.15f), Amber500, "Connecting...")
        ConnectionStatus.CONNECTED -> Triple(Emerald500.copy(alpha = 0.15f), Emerald500, "Streaming Active")
        ConnectionStatus.DISCONNECTED -> Triple(Slate800.copy(alpha = 0.6f), Slate600, "Offline")
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
