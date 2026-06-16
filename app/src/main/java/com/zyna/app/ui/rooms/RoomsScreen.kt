package com.zyna.app.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyna.app.data.matrix.MatrixLastOwnMessageStatus
import com.zyna.app.data.matrix.MatrixRoomSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(
    rooms: List<MatrixRoomSummary>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenRoom: (MatrixRoomSummary) -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats") },
                actions = {
                    TextButton(
                        onClick = onRefresh,
                        enabled = !isRefreshing
                    ) {
                        Text(if (isRefreshing) "Syncing" else "Refresh")
                    }
                    TextButton(onClick = onLogout) {
                        Text("Log out")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (rooms.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isRefreshing) "Loading chats" else "No chats",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(
                    items = rooms,
                    key = { it.id }
                ) { room ->
                    RoomRow(
                        room = room,
                        onClick = { onOpenRoom(room) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RoomRow(
    room: MatrixRoomSummary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = room.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = room.previewText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            room.lastMessageAtMillis?.let { timestampMillis ->
                Text(
                    text = timestampMillis.formatRoomTimestamp(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            room.lastOwnMessageStatus?.let { status ->
                Text(
                    text = status.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status == MatrixLastOwnMessageStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1
                )
            }
            UnreadIndicator(room)
        }
    }
}

@Composable
private fun UnreadIndicator(room: MatrixRoomSummary) {
    if (!room.showsUnreadIndicator()) return

    val color = if (room.unreadMentionCount > 0) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val shape: Shape = if (room.unreadCount > 0) {
        RoundedCornerShape(10.dp)
    } else {
        CircleShape
    }

    Box(
        modifier = Modifier
            .then(
                if (room.unreadCount > 0) {
                    Modifier
                        .height(20.dp)
                        .defaultMinSize(minWidth = 20.dp)
                        .background(color = color, shape = shape)
                        .padding(horizontal = 6.dp)
                } else {
                    Modifier
                        .size(10.dp)
                        .background(color = color, shape = shape)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        room.unreadBadgeText()?.let { badgeText ->
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = if (room.unreadMentionCount > 0) {
                    MaterialTheme.colorScheme.onError
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                maxLines = 1
            )
        }
    }
}

private fun MatrixRoomSummary.previewText(): String {
    return lastMessageText?.takeIf { it.isNotBlank() } ?: "No messages"
}

private fun MatrixRoomSummary.showsUnreadIndicator(): Boolean {
    return unreadCount > 0 || isMarkedUnread
}

private fun MatrixRoomSummary.unreadBadgeText(): String? {
    if (unreadCount <= 0) return null
    return if (unreadCount > 99) "99+" else unreadCount.toString()
}

private fun MatrixLastOwnMessageStatus.label(): String {
    return when (this) {
        MatrixLastOwnMessageStatus.PENDING -> "Sending"
        MatrixLastOwnMessageStatus.SENT -> "Sent"
        MatrixLastOwnMessageStatus.READ -> "Read"
        MatrixLastOwnMessageStatus.FAILED -> "Failed"
    }
}

private fun Long.formatRoomTimestamp(): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(this).atZone(zone)
    val today = LocalDate.now(zone)
    return when (dateTime.toLocalDate()) {
        today -> ROOM_TIME_FORMATTER.format(dateTime)
        today.minusDays(1) -> "Yesterday"
        else -> ROOM_DATE_FORMATTER.format(dateTime)
    }
}

private val ROOM_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val ROOM_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
