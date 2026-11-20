package com.junesoon.mim.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junesoon.mim.app.domain.Contact
import com.junesoon.mim.app.domain.sortedForList

/**
 * 联系人与会话列表。
 *
 * 顶部是身份区（本机头像、账号、设备），中间是本地过滤框，
 * 下面是会话列表：置顶优先，其次按最近消息时间倒序。
 * 未读角标以服务端计数为准。
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
    onOpenSearch: () -> Unit,
    onOpenSecurity: () -> Unit,
    onLogout: () -> Unit,
    onTogglePin: (String) -> Unit = {},
    /** 分栏布局里当前打开的会话；用于高亮选中行。 */
    selectedId: String? = null,
    modifier: Modifier = Modifier,
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
    val visible = matched.sortedForList()

    Column(modifier = modifier.fillMaxSize().background(MimPalette.Card)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(name = selfName, size = 34.dp, online = true)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    selfName.ifBlank { "未登录" },
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 只留设备名：9dp 的锁图标在 1x 屏上只是一团墨点，加密提示
                // 由聊天页头部与右栏空态承担，这里不再叠加视觉噪声。
                Text(
                    deviceName.ifBlank { "本机" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MimPalette.InkSoft.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MimIconAction(MimIcon.Search, tooltip = "搜索消息") { onOpenSearch() }
            MimIconAction(
                MimIcon.Plus,
                tooltip = "添加联系人",
                badge = pendingRequestCount,
            ) { onAddContact() }
            Box {
                MimIconAction(MimIcon.More, tooltip = "更多") { showMenu = true }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MimPalette.Card,
                ) {
                    DropdownMenuItem(
                        text = { MenuRow(MimIcon.Device, "我的设备") },
                        onClick = {
                            showMenu = false
                            onShowDevices()
                        },
                    )
                    DropdownMenuItem(
                        text = { MenuRow(MimIcon.Shield, "安全设置") },
                        onClick = {
                            showMenu = false
                            onOpenSecurity()
                        },
                    )
                    HorizontalDivider(color = MimPalette.LineSoft)
                    DropdownMenuItem(
                        text = { MenuRow(MimIcon.Logout, "退出登录", danger = true) },
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
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        ) {
            MimSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索联系人",
            )
        }

        HorizontalDivider(color = MimPalette.LineSoft)

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
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visible, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        selected = contact.id == selectedId,
                        onClick = { onOpenChat(contact.id) },
                        onTogglePin = { onTogglePin(contact.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    contact: Contact,
    selected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    var showRowMenu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    // 选中用底色 + 主色文字，未读只靠字重与角标：两者不再互相冒充。
    val background = when {
        selected && hovered -> MimPalette.Pressed
        selected -> MimPalette.Selected
        hovered -> MimPalette.Hover
        else -> Color.Transparent
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(background, shape)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = { showRowMenu = true },
                )
                .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(name = contact.name, size = 38.dp, online = contact.online)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (contact.pinned) {
                        MimIcon(MimIcon.Pin, size = 11.dp, tint = MimPalette.Brass)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        contact.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected || contact.unread > 0) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Medium
                        },
                        color = if (selected) MimPalette.TealDeep else MimPalette.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (contact.blocked) {
                        Spacer(Modifier.width(6.dp))
                        Tag("已屏蔽", MimPalette.InkSoft, MimPalette.Paper)
                    }
                }
                Text(
                    contact.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        contact.blocked -> MimPalette.InkSoft.copy(alpha = 0.7f)
                        contact.unread > 0 -> MimPalette.Ink.copy(alpha = 0.82f)
                        else -> MimPalette.InkSoft
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 时间与角标让位给悬停时出现的「更多」，避免两套右栏挤在一起。
            Box(
                modifier = Modifier.widthIn(min = 34.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (hovered) {
                    MimIconAction(MimIcon.More, tooltip = "会话操作") { showRowMenu = true }
                } else {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (contact.time.isNotBlank()) {
                            Text(
                                contact.time,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (contact.unread > 0) MimPalette.Teal else MimPalette.InkSoft,
                            )
                        }
                        if (contact.unread > 0) {
                            UnreadBadge(contact.unread)
                        }
                    }
                }
            }
        }
        DropdownMenu(
            expanded = showRowMenu,
            onDismissRequest = { showRowMenu = false },
            containerColor = MimPalette.Card,
        ) {
            DropdownMenuItem(
                text = { Text(if (contact.pinned) "取消置顶" else "置顶会话") },
                onClick = {
                    showRowMenu = false
                    onTogglePin()
                },
            )
        }
    }
}

@Composable
fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .height(18.dp)
            .widthIn(min = 18.dp)
            .clip(CircleShape)
            .background(MimPalette.Teal)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = Color(0xFFFDFDFB),
        )
    }
}

/** 菜单项：图标 + 文字，危险操作用红色。与聊天页的菜单保持同一套视觉。 */
@Composable
private fun MenuRow(icon: MimIcon, text: String, danger: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MimIcon(
            icon,
            size = 14.dp,
            tint = if (danger) MimPalette.Danger else MimPalette.InkSoft,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (danger) MimPalette.Danger else MimPalette.Ink,
        )
    }
}

@Composable
fun Tag(text: String, color: Color, background: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun Banner(text: String, danger: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (danger) MimPalette.DangerSoft else MimPalette.TealSoft)
            .padding(horizontal = 20.dp, vertical = 9.dp),
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
    // 空态渲染在 312dp 的列表栏里：必须自己收住宽度，否则正文会顶到两边缘。
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MimPalette.TealSoft),
                contentAlignment = Alignment.Center,
            ) {
                MimIcon(MimIcon.Shield, size = 23.dp, tint = MimPalette.Teal)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "还没有联系人",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                // 在 228dp 宽里手动断行：让两行长度接近，避免把「开始」这类词拆开。
                "输入对方用户名并发送申请，\n同意后即可开始加密对话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 228.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MimPalette.Teal)
                    .mimHoverable(
                        shape = RoundedCornerShape(10.dp),
                        hoverColor = Color(0x1FFFFFFF),
                        pressColor = Color(0x33FFFFFF),
                        onClick = onAddContact,
                    )
                    .padding(horizontal = 15.dp, vertical = 9.dp),
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
