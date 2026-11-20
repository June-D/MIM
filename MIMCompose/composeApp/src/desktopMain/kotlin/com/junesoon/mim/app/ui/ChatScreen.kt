package com.junesoon.mim.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.Contact
import com.junesoon.mim.app.domain.SafetyEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 聊天页。
 *
 * 顶部是身份与安全入口（安全码、备注、屏蔽、移除），中间是按日期分组的消息，
 * 底部是输入区。已屏蔽时输入区替换为说明条。
 */
@Composable
fun ChatScreen(
    contact: Contact,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onRecall: (String) -> Unit,
    onClear: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onToggleBlock: () -> Unit,
    onRemove: () -> Unit,
    safetyNumbers: List<SafetyEntry>,
    error: String? = null,
    notice: String? = null,
) {
    var draft by remember { mutableStateOf("") }
    var showSafety by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MimPalette.Paper)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MimPalette.Card)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeaderIcon(MimIcon.Back, onClick = onBack)
            Avatar(name = contact.name, size = 38.dp, online = contact.online)
            Column(modifier = Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        contact.blocked -> "已屏蔽 · 对方消息不再显示"
                        contact.online -> "在线"
                        else -> "端到端加密"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HeaderIcon(MimIcon.Shield, onClick = { showSafety = true })
            Box {
                HeaderIcon(MimIcon.More, onClick = { showMenu = true })
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MimPalette.Card,
                ) {
                    DropdownMenuItem(
                        text = { MenuLabel(MimIcon.Edit, "设置备注") },
                        onClick = { showMenu = false; showRename = true },
                    )
                    DropdownMenuItem(
                        text = {
                            MenuLabel(
                                MimIcon.Block,
                                if (contact.blocked) "解除屏蔽" else "屏蔽此人",
                            )
                        },
                        onClick = { showMenu = false; onToggleBlock() },
                    )
                    DropdownMenuItem(
                        text = { MenuLabel(MimIcon.Close, "清除会话") },
                        onClick = { showMenu = false; showClear = true },
                    )
                    HorizontalDivider(color = MimPalette.LineSoft)
                    DropdownMenuItem(
                        text = { MenuLabel(MimIcon.Logout, "移除联系人", danger = true) },
                        onClick = { showMenu = false; showRemove = true },
                    )
                }
            }
        }

        if (notice != null || error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (error != null) MimPalette.DangerSoft else MimPalette.TealSoft)
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            ) {
                Text(
                    error ?: notice.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MimPalette.Danger else MimPalette.TealDeep,
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MimPalette.TealSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        MimIcon(MimIcon.Lock, size = 24.dp, tint = MimPalette.Teal)
                    }
                    Text("还没有消息", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "核对安全码后再开始对话，内容只有对方能解开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                        val previous = messages.getOrNull(index - 1)
                        val label = dayLabel(message.epochMs)
                        if (label.isNotBlank() && dayKey(previous?.epochMs) != dayKey(message.epochMs)) {
                            DaySeparator(label = label)
                        }
                        MessageRow(message = message, onRecall = { onRecall(message.id) })
                    }
                }
            }
        }

        if (contact.blocked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MimPalette.Card)
                    .padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MimIcon(MimIcon.Block, size = 16.dp, tint = MimPalette.InkSoft)
                Text(
                    "已屏蔽此联系人。解除屏蔽后可以继续发送。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggleBlock) { Text("解除屏蔽") }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MimPalette.Card)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = {
                        Text("输入消息…", style = MaterialTheme.typography.bodySmall)
                    },
                    shape = RoundedCornerShape(14.dp),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MimPalette.Teal,
                        unfocusedBorderColor = MimPalette.LineSoft,
                        focusedContainerColor = MimPalette.Paper,
                        unfocusedContainerColor = MimPalette.Paper,
                        cursorColor = MimPalette.Teal,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (draft.isNotBlank()) {
                                onSend(draft)
                                draft = ""
                            }
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (draft.isBlank()) MimPalette.Line else MimPalette.Teal,
                        )
                        .clickable(enabled = draft.isNotBlank()) {
                            onSend(draft)
                            draft = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MimIcon(
                        MimIcon.Send,
                        size = 18.dp,
                        tint = if (draft.isBlank()) MimPalette.InkSoft else Color(0xFFFDFDFB),
                    )
                }
            }
        }
    }

    if (showSafety) {
        AlertDialog(
            onDismissRequest = { showSafety = false },
            title = { Text("安全码") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "与对方当面或通过可信渠道逐位核对。不一致说明密钥可能被替换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (safetyNumbers.isEmpty()) {
                        Text(
                            "还没有拿到对方的设备密钥，等对方上线后再试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MimPalette.Danger,
                        )
                    }
                    safetyNumbers.forEach { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MimPalette.Paper)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "设备 ${entry.deviceId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                entry.code,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    letterSpacing = 0.6.sp,
                                ),
                                color = MimPalette.TealDeep,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSafety = false }) { Text("关闭") } },
            containerColor = MimPalette.Card,
        )
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清除会话") },
            text = {
                Text("「只清我的」不影响对方；「双方清除」会让对方也看不到这段消息。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    onClear(true)
                }) { Text("双方清除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClear = false
                    onClear(false)
                }) { Text("只清我的") }
            },
            containerColor = MimPalette.Card,
        )
    }

    if (showRename) {
        RenameDialog(
            current = contact.name,
            onDismiss = { showRename = false },
            onConfirm = {
                showRename = false
                onRename(it)
            },
        )
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("移除联系人") },
            text = {
                Text("对方也会从联系人列表中消失。本机的历史消息会保留，但之后无法再发消息。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemove = false
                    onRemove()
                }) { Text("移除", color = MimPalette.Danger) }
            },
            dismissButton = { TextButton(onClick = { showRemove = false }) { Text("取消") } },
            containerColor = MimPalette.Card,
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("联系人备注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "备注只保存在服务端的联系人关系里，只对你可见。留空表示清除备注。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MimPalette.Teal,
                        unfocusedBorderColor = MimPalette.Line,
                        cursorColor = MimPalette.Teal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = MimPalette.Card,
    )
}

@Composable
private fun MessageRow(message: ChatMessage, onRecall: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.fromMe) 16.dp else 4.dp,
                        bottomEnd = if (message.fromMe) 4.dp else 16.dp,
                    ),
                )
                .background(if (message.fromMe) MimPalette.BubbleMine else MimPalette.Card)
                .border(
                    width = if (message.fromMe) 0.dp else 1.dp,
                    color = if (message.fromMe) Color.Transparent else MimPalette.LineSoft,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.fromMe) 16.dp else 4.dp,
                        bottomEnd = if (message.fromMe) 4.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    message.recalled -> if (message.fromMe) {
                        Color(0xCCFDFDFB)
                    } else {
                        MimPalette.InkSoft
                    }
                    message.fromMe -> Color(0xFFFDFDFB)
                    else -> MimPalette.Ink
                },
                fontWeight = if (message.recalled) FontWeight.Normal else FontWeight.Normal,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (message.canRecall) {
                    Text(
                        "撤回",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.fromMe) Color(0xCCFDFDFB) else MimPalette.Teal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onRecall() }
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    buildString {
                        append(message.time)
                        if (message.pending) append(" · 发送中")
                        if (message.read) append(" · 已读")
                        if (message.recalled) append(" · 已撤回")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.fromMe) Color(0xB3FDFDFB) else MimPalette.InkSoft,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun DaySeparator(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MimPalette.LineSoft)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MimPalette.InkSoft,
            )
        }
    }
}

@Composable
private fun HeaderIcon(icon: MimIcon, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        MimIcon(icon, size = 17.dp, tint = MimPalette.Ink)
    }
}

@Composable
private fun MenuLabel(icon: MimIcon, text: String, danger: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MimIcon(
            icon,
            size = 15.dp,
            tint = if (danger) MimPalette.Danger else MimPalette.InkSoft,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (danger) MimPalette.Danger else MimPalette.Ink,
        )
    }
}

private val dayFormatter = DateTimeFormatter.ofPattern("M 月 d 日")

private fun dayKey(epochMs: Long?): LocalDate? =
    epochMs?.takeIf { it > 0 }?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
    }

private fun dayLabel(epochMs: Long): String {
    val date = dayKey(epochMs) ?: return ""
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> date.format(dayFormatter)
    }
}
