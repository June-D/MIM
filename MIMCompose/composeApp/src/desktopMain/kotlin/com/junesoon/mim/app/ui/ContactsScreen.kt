package com.junesoon.mim.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junesoon.mim.app.domain.Contact

/**
 * 联系人与会话列表。
 *
 * 顶部是身份区（本机头像、账号、设备），中间是本地过滤框，
 * 下面是按最近消息排序的会话。未读角标以服务端计数为准。
 */
@Composable
fun ContactsScreen(
    selfName: String,
    deviceName: String,
    contacts: List<Contact>,
    pendingRequestCount: Int,
    onOpenChat: (String) -> Unit,
    onShowDevices: () -> Unit,
    onAddContact: () -> Unit,
    onOpenSecurity: () -> Unit,
    onLogout: () -> Unit,
    notice: String? = null,
    error: String? = null,
) {
    var query by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    val matched = if (query.isBlank()) {
        contacts
    } else {
        contacts.filter {
            it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
        }
    }
    // 最近说过话的排前面，其余按名字；稳定排序避免每次刷新跳动。
    val visible = matched.sortedWith(
        compareByDescending<Contact> { it.lastEpochMs }.thenBy { it.name },
    )

    Column(modifier = Modifier.fillMaxSize().background(MimPalette.Paper)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MimPalette.Card)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(name = selfName, size = 42.dp, online = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(selfName.ifBlank { "未登录" }, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MimIcon(MimIcon.Lock, size = 11.dp, tint = MimPalette.InkSoft)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "端到端加密 · $deviceName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HeaderAction(MimIcon.Plus, badge = pendingRequestCount) { onAddContact() }
            HeaderAction(MimIcon.Device) { onShowDevices() }
            Box {
                HeaderAction(MimIcon.More) { showMenu = true }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MimPalette.Card,
                ) {
                    DropdownMenuItem(
                        text = { Text("安全设置") },
                        onClick = {
                            showMenu = false
                            onOpenSecurity()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("退出登录") },
                        onClick = {
                            showMenu = false
                            onLogout()
                        },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MimPalette.Card)
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索联系人", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { MimIcon(MimIcon.Search, size = 15.dp, tint = MimPalette.InkSoft) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MimPalette.Teal,
                    unfocusedBorderColor = MimPalette.LineSoft,
                    focusedContainerColor = MimPalette.Paper,
                    unfocusedContainerColor = MimPalette.Paper,
                    cursorColor = MimPalette.Teal,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (notice != null || error != null) {
            Banner(
                text = error ?: notice.orEmpty(),
                danger = error != null,
            )
        }

        when {
            contacts.isEmpty() -> EmptyContacts(onAddContact)
            visible.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "没有匹配「$query」的联系人",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { contact ->
                    ContactRow(contact = contact, onClick = { onOpenChat(contact.id) })
                    HorizontalDivider(
                        color = MimPalette.LineSoft,
                        modifier = Modifier.padding(start = 76.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(if (contact.unread > 0) Color(0x66DDEBE6) else MimPalette.Card)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(name = contact.name, size = 44.dp, online = contact.online)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (contact.blocked) {
                    Spacer(Modifier.width(6.dp))
                    Tag("已屏蔽", MimPalette.InkSoft, MimPalette.Paper)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                contact.preview,
                style = MaterialTheme.typography.bodySmall,
                color = if (contact.blocked) {
                    MimPalette.InkSoft.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (contact.time.isNotBlank()) {
                Text(
                    contact.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (contact.unread > 0) {
                UnreadBadge(contact.unread)
            }
        }
    }
}

@Composable
fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MimPalette.Teal)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFDFDFB),
        )
    }
}

@Composable
fun Tag(text: String, color: Color, background: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun HeaderAction(
    icon: MimIcon,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        MimIcon(icon, size = 18.dp, tint = MimPalette.Ink)
        if (badge > 0) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 1.dp),
            ) {
                UnreadBadge(badge)
            }
        }
    }
}

@Composable
private fun Banner(text: String, danger: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (danger) MimPalette.DangerSoft else MimPalette.TealSoft)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (danger) MimPalette.Danger else MimPalette.TealDeep,
        )
    }
}

@Composable
private fun EmptyContacts(onAddContact: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MimPalette.TealSoft),
                contentAlignment = Alignment.Center,
            ) {
                MimIcon(MimIcon.Shield, size = 28.dp, tint = MimPalette.Teal)
            }
            Text("还没有联系人", style = MaterialTheme.typography.titleMedium)
            Text(
                "输入对方的用户名发送申请，同意后即可开始加密对话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MimPalette.Teal)
                    .clickable { onAddContact() }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MimIcon(MimIcon.Plus, size = 14.dp, tint = Color(0xFFFDFDFB))
                Text(
                    "添加联系人",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFFDFDFB),
                )
            }
        }
    }
}
