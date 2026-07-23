package com.suportex.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class NotificationCenterUiState(
    val hasNotifications: Boolean,
    val unreadCount: Int,
    val notifications: List<ClientNotificationUi>
) {
    companion object {
        val Empty = NotificationCenterUiState(
            hasNotifications = false,
            unreadCount = 0,
            notifications = emptyList()
        )
    }
}

data class ClientNotificationUi(
    val id: String,
    val title: String,
    val description: String,
    val badgeLabel: String?,
    val actionLabel: String?,
    val type: ClientNotificationType,
    val isRead: Boolean = false,
    val actionType: String = "NONE",
    val canDismiss: Boolean = true
)

enum class ClientNotificationType {
    REVIEW,
    SHARE,
    CREDIT_REWARD,
    SECURITY_NOTICE,
    LOW_CREDITS,
    INFO,
    WARNING
}

@Composable
fun NotificationBellButton(
    notificationState: NotificationCenterUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val brandYellow = MaterialTheme.colorScheme.primary
    val badgeRed = Color(0xFFE63A3A)
    val hasUnread = notificationState.hasNotifications && notificationState.unreadCount > 0
    val pulseTransition = rememberInfiniteTransition(label = "notification-bell-pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "notification-bell-pulse-scale"
    )
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "notification-bell-pulse-alpha"
    )

    Box(
        modifier = modifier.size(42.dp),
        contentAlignment = Alignment.Center
    ) {
        if (hasUnread) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .border(1.dp, brandYellow.copy(alpha = 0.55f), CircleShape)
            )
        }

        IconButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.NotificationsNone,
                contentDescription = "Abrir notificações",
                modifier = Modifier.size(24.dp),
                tint = brandYellow
            )
        }

        if (hasUnread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(badgeRed),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = notificationState.unreadCount.coerceAtMost(9).toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun NotificationCenterOverlay(
    visible: Boolean,
    uiState: NotificationCenterUiState,
    onDismiss: () -> Unit,
    onNotificationAction: (ClientNotificationUi) -> Unit,
    onNotificationDismiss: (ClientNotificationUi) -> Unit
) {
    HomeAnchoredOverlay(
        visible = visible,
        onDismiss = onDismiss,
        panelAlignment = Alignment.TopEnd
    ) {
        NotificationCenterPanel(
            uiState = uiState,
            onDismiss = onDismiss,
            onNotificationAction = onNotificationAction,
            onNotificationDismiss = onNotificationDismiss
        )
    }
}

@Composable
fun HomeAnchoredOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    panelAlignment: Alignment = Alignment.TopEnd,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = fadeOut(animationSpec = tween(durationMillis = 140))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier
                .align(panelAlignment)
                .padding(start = 20.dp, top = 88.dp, end = 20.dp),
            enter = fadeIn(animationSpec = tween(durationMillis = 180)) +
                expandVertically(
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top
                ) +
                slideInVertically(
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                    initialOffsetY = { height -> -height / 5 }
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)) +
                shrinkVertically(
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Top
                ) +
                slideOutVertically(
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    targetOffsetY = { height -> -height / 5 }
                )
        ) {
            content()
        }
    }
}

@Composable
private fun NotificationCenterPanel(
    uiState: NotificationCenterUiState,
    onDismiss: () -> Unit,
    onNotificationAction: (ClientNotificationUi) -> Unit,
    onNotificationDismiss: (ClientNotificationUi) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 430.dp)
            .animateContentSize(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ),
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            NotificationCenterHeader(onDismiss = onDismiss)
            Spacer(Modifier.height(8.dp))

            if (uiState.notifications.isEmpty()) {
                NotificationEmptyState(onDismiss = onDismiss)
            } else {
                NotificationList(
                    notifications = uiState.notifications,
                    onNotificationAction = onNotificationAction,
                    onNotificationDismiss = onNotificationDismiss
                )
            }
        }
    }
}

@Composable
private fun NotificationCenterHeader(onDismiss: () -> Unit) {
    val brandYellow = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Notificações",
            color = Color(0xFF151515),
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
            textAlign = TextAlign.Center
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Fechar notificações",
                modifier = Modifier.size(24.dp),
                tint = brandYellow
            )
        }
    }
}

@Composable
private fun NotificationEmptyState(onDismiss: () -> Unit) {
    val brandYellow = MaterialTheme.colorScheme.primary
    val muted = Color(0xFF85858C)
    val mutedLight = Color(0xFFA6A6AE)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(128.dp),
            contentAlignment = Alignment.Center
        ) {
            EmptyStateDot(Modifier.align(Alignment.TopStart).offset(x = 22.dp, y = 24.dp))
            EmptyStateDot(Modifier.align(Alignment.TopEnd).offset(x = (-24).dp, y = 30.dp))
            EmptyStateDot(Modifier.align(Alignment.CenterStart).offset(x = 12.dp, y = 8.dp))
            EmptyStateDot(Modifier.align(Alignment.CenterEnd).offset(x = (-14).dp, y = (-4).dp))
            Icon(
                imageVector = Icons.Outlined.NotificationsNone,
                contentDescription = null,
                modifier = Modifier.size(86.dp),
                tint = Color(0xFFB6B6BA).copy(alpha = 0.64f)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Nenhuma notificação\nno momento.",
            color = muted,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Quando houver avisos importantes,\neles aparecerão aqui.",
            color = mutedLight,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(58.dp))
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, brandYellow),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White,
                contentColor = brandYellow
            )
        ) {
            Text(
                text = "Entendi",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EmptyStateDot(modifier: Modifier) {
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(Color(0xFFD6D6DA).copy(alpha = 0.52f))
    )
}

@Composable
private fun NotificationList(
    notifications: List<ClientNotificationUi>,
    onNotificationAction: (ClientNotificationUi) -> Unit,
    onNotificationDismiss: (ClientNotificationUi) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        notifications.forEach { notification ->
            NotificationCard(
                notification = notification,
                onActionClick = { onNotificationAction(notification) },
                onDismissClick = { onNotificationDismiss(notification) }
            )
        }
    }
}

@Composable
private fun NotificationCard(
    notification: ClientNotificationUi,
    onActionClick: () -> Unit,
    onDismissClick: () -> Unit
) {
    val brandYellow = MaterialTheme.colorScheme.primary
    val borderSoft = Color(0xFFE6E7EB)
    val textPrimary = Color(0xFF171717)
    val textSecondary = Color(0xFF6F7178)
    val icon = notificationIcon(notification.type)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, borderSoft)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(brandYellow.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(27.dp),
                    tint = brandYellow
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = notification.title,
                    color = textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = notification.description,
                    color = textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (notification.badgeLabel != null || notification.actionLabel != null || notification.canDismiss) {
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier.widthIn(min = 92.dp, max = 112.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    notification.badgeLabel?.let { label ->
                        NotificationBadge(label = label)
                    }
                    if (notification.badgeLabel != null && notification.actionLabel != null) {
                        Spacer(Modifier.height(7.dp))
                    }
                    notification.actionLabel?.let { label ->
                        Button(
                            onClick = onActionClick,
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(9.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = brandYellow,
                                contentColor = Color(0xFF111111),
                                disabledContainerColor = Color(0xFFE6E7EB),
                                disabledContentColor = Color(0xFF6F7178)
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.5.sp,
                                lineHeight = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (notification.canDismiss) {
                        if (notification.badgeLabel != null || notification.actionLabel != null) {
                            Spacer(Modifier.height(2.dp))
                        }
                        TextButton(
                            onClick = onDismissClick,
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Dispensar",
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                color = textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = Color(0xFFFFF7DF),
        border = BorderStroke(1.dp, Color(0xFFF0E2B7))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = Color(0xFF4B3A00),
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun notificationIcon(type: ClientNotificationType): ImageVector {
    return when (type) {
        ClientNotificationType.REVIEW -> Icons.Filled.Star
        ClientNotificationType.SHARE -> Icons.Outlined.Share
        ClientNotificationType.CREDIT_REWARD -> Icons.Outlined.CardGiftcard
        ClientNotificationType.SECURITY_NOTICE,
        ClientNotificationType.INFO -> Icons.Outlined.Info
        ClientNotificationType.LOW_CREDITS,
        ClientNotificationType.WARNING -> Icons.Outlined.Warning
    }
}
