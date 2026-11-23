package com.junesoon.mim.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import com.junesoon.mim.app.domain.ChatMessage
import com.junesoon.mim.app.domain.Contact
import com.junesoon.mim.app.domain.SafetyEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onSendImage: (ByteArray, String, String?) -> Unit = { _, _, _ -> },
    onSendVoice: (ByteArray, Long) -> Unit = { _, _ -> },
    onSendFile: (ByteArray, String, String) -> Unit = { _, _, _ -> },
    loadMedia: suspend (ChatMessage) -> ByteArray? = { null },
    scrollToMessageId: String? = null,
    onScrolled: () -> Unit = {},
    pinned: Boolean = false,
    onTogglePin: () -> Unit = {},
    onRecall: (String) -> Unit,
    onClear: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onToggleBlock: () -> Unit,
    onRemove: () -> Unit,
    safetyNumbers: List<SafetyEntry>,
    /** 对方头像字节（服务端下发）；为空时回落到首字母头像。 */
    peerAvatar: ByteArray? = null,
    /** 分栏布局里列表一直可见，不需要返回箭头。 */
    showBack: Boolean = true,
    /** 本次打开会话的第一条未读：在它上面画「以下为新消息」。 */
    firstUnreadId: String? = null,
    /** 安全码与上次记录不一致的设备 id。 */
    safetyChanged: Set<String> = emptySet(),
    onAcknowledgeSafety: () -> Unit = {},
    error: String? = null,
    notice: String? = null,
) {
    // 用 TextFieldValue 而不是 String：中文输入法拼写过程中 composition 非空，
    // 此时回车是「选词」，绝不能当成发送。
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var localError by remember { mutableStateOf<String?>(null) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var recordSeconds by remember { mutableStateOf(0) }
    var viewerImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    var showSafety by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 往上翻过历史时露出「回到最新」，否则新消息到达后用户会不知道自己在哪。
    val canScrollBack = listState.canScrollBackward && messages.isNotEmpty()

    LaunchedEffect(recorder) {
        if (recorder == null) {
            recordSeconds = 0
        } else {
            while (recorder != null) {
                delay(1_000)
                recordSeconds += 1
            }
        }
    }

    // 记住上次处理过的消息条数：锚点消费后不能让「滚到底部」把定位结果顶掉。
    var lastListSize by remember { mutableStateOf(0) }

    LaunchedEffect(scrollToMessageId, messages.size) {
        val target = scrollToMessageId
        if (target != null) {
            val index = messages.indexOfFirst { it.id == target }
            if (index >= 0) {
                listState.animateScrollToItem(index)
                lastListSize = messages.size
                onScrolled()
            } else if (messages.isNotEmpty()) {
                // 目标已不在缓存里（例如被清除过）：消费掉锚点，恢复「新消息滚到底部」。
                lastListSize = messages.size
                onScrolled()
            }
            return@LaunchedEffect
        }
        // 没有待定位目标时：消息条数变化就滚到底部。
        if (messages.isNotEmpty() && messages.size != lastListSize) {
            lastListSize = messages.size
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
            if (showBack) {
                MimIconAction(MimIcon.Back, tooltip = "返回列表", onClick = onBack)
            }
            Avatar(
                name = contact.name,
                size = 36.dp,
                online = contact.online,
                image = rememberAvatar(peerAvatar),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        contact.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (pinned) {
                        Spacer(Modifier.width(6.dp))
                        MimIcon(MimIcon.Pin, size = 11.dp, tint = MimPalette.Brass)
                    }
                }
                Text(
                    when {
                        contact.blocked -> "已屏蔽 · 对方消息不再显示"
                        contact.online -> "在线"
                        else -> "端到端加密"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (contact.online && !contact.blocked) MimPalette.Teal else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MimIconAction(MimIcon.Shield, tooltip = "安全码") { showSafety = true }
            Box {
                MimIconAction(MimIcon.More, tooltip = "更多") { showMenu = true }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MimPalette.Card,
                ) {
                    DropdownMenuItem(
                        text = {
                            MenuLabel(
                                MimIcon.Pin,
                                if (pinned) "取消置顶" else "置顶会话",
                            )
                        },
                        onClick = { showMenu = false; onTogglePin() },
                    )
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

        HorizontalDivider(color = MimPalette.LineSoft)

        val banner = localError ?: error ?: notice
        if (banner != null) {
            val danger = localError != null || error != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (danger) MimPalette.DangerSoft else MimPalette.TealSoft)
                    .padding(horizontal = 20.dp, vertical = 9.dp),
            ) {
                Text(
                    banner,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (danger) MimPalette.Danger else MimPalette.TealDeep,
                )
            }
        }

        if (safetyChanged.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MimPalette.DangerSoft)
                    .padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MimIcon(MimIcon.Shield, size = 14.dp, tint = MimPalette.Danger)
                Text(
                    "安全码与上次记录不一致（${safetyChanged.joinToString("、")}）。" +
                        "可能是对方换了设备或密钥，也可能是本机重装；请重新线下核对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MimPalette.Danger,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "我已核对",
                    style = MaterialTheme.typography.labelLarge,
                    color = MimPalette.Danger,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onAcknowledgeSafety() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
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
                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
                ) {
                    itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                        val previous = messages.getOrNull(index - 1)
                        val next = messages.getOrNull(index + 1)
                        val label = dayLabel(message.epochMs)
                        val newDay = label.isNotBlank() && dayKey(previous?.epochMs) != dayKey(message.epochMs)
                        if (newDay) {
                            DaySeparator(label = label)
                        }
                        if (message.id == firstUnreadId) {
                            UnreadSeparator()
                        }
                        // 同步来的那一段单独标出来：用户要知道这些不是在本机产生的。
                        if (message.syncedFrom != null && previous?.syncedFrom == null) {
                            SyncSeparator(count = messages.count { it.syncedFrom != null })
                        }
                        // 同一人、同一分钟内的连续消息算一组：组内贴紧，组间留白。
                        val grouped = !newDay && previous != null &&
                            previous.fromMe == message.fromMe &&
                            message.epochMs - previous.epochMs <= GROUP_WINDOW_MS &&
                            message.kind == "text" && previous.kind == "text"
                        val lastOfGroup = next == null ||
                            next.fromMe != message.fromMe ||
                            next.epochMs - message.epochMs > GROUP_WINDOW_MS ||
                            dayKey(next.epochMs) != dayKey(message.epochMs) ||
                            next.kind != "text" || message.kind != "text"
                        MessageRow(
                            message = message,
                            loadMedia = loadMedia,
                            onOpenImage = { viewerImage = it },
                            onRecall = { onRecall(message.id) },
                            grouped = grouped,
                            lastOfGroup = lastOfGroup,
                        )
                    }
                }
                ScrollToLatestPill(
                    visible = canScrollBack,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp),
                    onClick = { scope.launch { listState.animateScrollToItem(messages.lastIndex) } },
                )
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
                ComposerIcon(MimIcon.Image, tooltip = "发送图片", enabled = recorder == null) {
                    localError = runCatching {
                        val picked = MediaTools.pickImage()
                        if (picked != null) {
                            onSendImage(picked.bytes, picked.mime, picked.name)
                        }
                        null
                    }.getOrElse { it.message ?: "图片读取失败" }
                }
                ComposerIcon(MimIcon.Clip, tooltip = "发送文件（≤8MB）", enabled = recorder == null) {
                    localError = runCatching {
                        val picked = MediaTools.pickFile()
                        if (picked != null) {
                            onSendFile(picked.bytes, picked.name, picked.mime)
                        }
                        null
                    }.getOrElse { it.message ?: "文件读取失败" }
                }
                ComposerIcon(
                    MimIcon.Mic,
                    tooltip = if (recorder == null) "录一段语音" else "停止录音并发送",
                    enabled = true,
                    active = recorder != null,
                ) {
                    if (recorder == null) {
                        val fresh = VoiceRecorder()
                        if (fresh.start()) {
                            recorder = fresh
                            localError = null
                        } else {
                            localError = "麦克风不可用或系统未授权"
                        }
                    } else {
                        val recorded = recorder?.stop()
                        recorder = null
                        if (recorded == null) {
                            localError = "没有录到有效音频"
                        } else {
                            onSendVoice(recorded.wav, recorded.durationMs)
                        }
                    }
                }
            if (recorder != null) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MimPalette.DangerSoft)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MimPalette.Danger))
                    Text(
                        "正在录音 ${formatDuration(recordSeconds * 1_000L)} · 点麦克风停止",
                        style = MaterialTheme.typography.bodySmall,
                        color = MimPalette.Danger,
                    )
                }
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = {
                        Text("输入消息…", style = MaterialTheme.typography.bodySmall)
                    },
                    shape = RoundedCornerShape(13.dp),
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MimPalette.Teal.copy(alpha = 0.65f),
                        unfocusedBorderColor = MimPalette.LineSoft,
                        focusedContainerColor = MimPalette.Paper,
                        unfocusedContainerColor = MimPalette.Paper,
                        cursorColor = MimPalette.Teal,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (draft.text.isNotBlank()) {
                                onSend(draft.text)
                                draft = TextFieldValue("")
                            }
                        },
                    ),
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) {
                            return@onPreviewKeyEvent false
                        }
                        val modifierPressed = event.isShiftPressed || event.isCtrlPressed ||
                            event.isAltPressed || event.isMetaPressed
                        if (modifierPressed || draft.composition != null) {
                            // 组合键换行交给输入框；输入法选词也不拦截。
                            false
                        } else {
                            if (draft.text.isNotBlank()) {
                                onSend(draft.text)
                                draft = TextFieldValue("")
                            }
                            true
                        }
                    },
                )
                MimTooltip("Enter 发送 · Shift+Enter 换行") {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (draft.text.isBlank()) MimPalette.LineSoft else MimPalette.TealFill,
                        )
                        .mimHoverable(
                            shape = CircleShape,
                            hoverColor = if (draft.text.isBlank()) Color.Transparent else Color(0x1FFFFFFF),
                            pressColor = if (draft.text.isBlank()) Color.Transparent else Color(0x38FFFFFF),
                            enabled = draft.text.isNotBlank(),
                            onClick = {
                                onSend(draft.text)
                                draft = TextFieldValue("")
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    MimIcon(
                        MimIcon.Send,
                        size = 17.dp,
                        tint = if (draft.text.isBlank()) MimPalette.InkSoft else MimPalette.OnTeal,
                    )
                }
                }
                }
            }
            }
        }

    viewerImage?.let { bitmap ->
        AlertDialog(
            onDismissRequest = { viewerImage = null },
            title = { Text("图片") },
            text = {
                Image(
                    bitmap = bitmap,
                    contentDescription = "图片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.sizeIn(maxWidth = 560.dp, maxHeight = 440.dp),
                )
            },
            confirmButton = { TextButton(onClick = { viewerImage = null }) { Text("关闭") } },
            containerColor = MimPalette.Card,
        )
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "设备 ${entry.deviceId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (entry.deviceId in safetyChanged) {
                                    Text(
                                        "已变更",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MimPalette.Danger,
                                    )
                                }
                            }
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
private fun MessageRow(
    message: ChatMessage,
    loadMedia: suspend (ChatMessage) -> ByteArray?,
    onOpenImage: (ImageBitmap) -> Unit,
    onRecall: () -> Unit,
    grouped: Boolean = false,
    lastOfGroup: Boolean = true,
) {
    val mine = message.fromMe
    // 一组消息只在两侧「组首/组尾」收圆，中间用 6dp，读起来是一段话而不是一叠卡片。
    val small = 6.dp
    val large = 15.dp
    val shape = RoundedCornerShape(
        topStart = if (mine) large else if (grouped) small else large,
        topEnd = if (mine) (if (grouped) small else large) else large,
        bottomStart = if (mine) large else (if (lastOfGroup) small else large),
        bottomEnd = if (mine) (if (lastOfGroup) small else large) else large,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (grouped) 3.dp else 12.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 468.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(shape)
                    // 对方气泡分层只取一种：浅色用极淡投影「浮」起来，
                    // 深色投影看不见，改用 1px 边框。两者同时上就是「幽灵卡片」。
                    .then(
                        if (mine) {
                            Modifier
                        } else if (MimPalette.dark) {
                            Modifier.border(1.dp, MimPalette.Line, shape)
                        } else {
                            Modifier.shadow(
                                elevation = 1.dp,
                                shape = shape,
                                ambientColor = MimPalette.Shadow.copy(alpha = 0.08f),
                                spotColor = MimPalette.Shadow.copy(alpha = 0.14f),
                            )
                        },
                    )
                    .background(if (mine) MimPalette.BubbleMine else MimPalette.Card)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                when {
                    message.recalled -> Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mine) Color(0xCCFDFDFB) else MimPalette.InkSoft,
                    )
                    message.kind == "image" -> MessageImage(message, loadMedia, onOpenImage)
                    message.kind == "voice" -> MessageVoice(message, loadMedia)
                    message.kind == "file" -> MessageFile(message, loadMedia)
                    else -> Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mine) Color(0xFFFDFDFB) else MimPalette.Ink,
                    )
                }
            }
            // 时间与状态退到气泡下方，只在本组最后一条显示，避免同一句话重复四遍。
            if (lastOfGroup) {
                Row(
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (message.canRecall) {
                        Text(
                            "撤回",
                            style = MaterialTheme.typography.labelSmall,
                            color = MimPalette.InkSoft,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .mimHoverable(
                                    shape = RoundedCornerShape(4.dp),
                                    onClick = onRecall,
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        buildString {
                            append(message.time)
                            if (message.pending) append(" · 发送中")
                            if (message.read) append(" · 已读")
                            if (message.recalled) append(" · 已撤回")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MimPalette.InkSoft.copy(alpha = 0.85f),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/** 距底部有一段距离时出现的浮动按钮；点一下回到最新一条。 */
@Composable
private fun ScrollToLatestPill(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { it / 3 },
        exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 3 },
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MimPalette.Card)
                .border(1.dp, MimPalette.Line, CircleShape)
                .mimHoverable(shape = CircleShape, onClick = onClick)
                .padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MimIcon(MimIcon.Down, size = 13.dp, tint = MimPalette.Teal)
            Text(
                "回到最新",
                style = MaterialTheme.typography.labelSmall,
                color = MimPalette.Teal,
            )
        }
    }
}

private sealed interface ImageState {
    data object Loading : ImageState
    data object Missing : ImageState
    data class Ready(val bitmap: ImageBitmap) : ImageState
}

@Composable
private fun MessageImage(
    message: ChatMessage,
    loadMedia: suspend (ChatMessage) -> ByteArray?,
    onOpenImage: (ImageBitmap) -> Unit,
) {
    var state by remember(message.id) { mutableStateOf<ImageState>(ImageState.Loading) }
    LaunchedEffect(message.id) {
        val bytes = runCatching { loadMedia(message) }.getOrNull()
        val bitmap = bytes?.let(MediaTools::decodeImage)
        state = if (bitmap == null) ImageState.Missing else ImageState.Ready(bitmap)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val current = state) {
            ImageState.Loading -> MediaPlaceholder("图片加载中…", message.fromMe)
            ImageState.Missing -> MediaPlaceholder("图片不可用：可能已过期或被清理", message.fromMe)
            is ImageState.Ready -> Image(
                bitmap = current.bitmap,
                contentDescription = "图片消息",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .sizeIn(maxWidth = 260.dp, maxHeight = 260.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenImage(current.bitmap) },
            )
        }
        val caption = captionOf(message, "[图片]")
        if (caption.isNotBlank()) {
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.fromMe) Color(0xFFFDFDFB) else MimPalette.Ink,
            )
        }
    }
}

@Composable
private fun MessageVoice(
    message: ChatMessage,
    loadMedia: suspend (ChatMessage) -> ByteArray?,
) {
    var bytes by remember(message.id) { mutableStateOf<ByteArray?>(null) }
    var loading by remember(message.id) { mutableStateOf(false) }
    var playing by remember(message.id) { mutableStateOf(false) }
    var failed by remember(message.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tint = if (message.fromMe) Color(0xFFFDFDFB) else MimPalette.Teal

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.clickable {
            if (playing) {
                MediaTools.stopVoice()
                playing = false
                return@clickable
            }
            scope.launch {
                loading = true
                val loaded = bytes ?: runCatching { loadMedia(message) }.getOrNull()
                loading = false
                if (loaded == null) {
                    failed = true
                    return@launch
                }
                failed = false
                bytes = loaded
                MediaTools.playVoice(loaded)
                playing = true
                delay((message.media?.durationMs ?: 3_000L) + 300)
                playing = false
            }
        },
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).background(
                if (message.fromMe) Color(0x33FDFDFB) else MimPalette.TealSoft,
            ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> Text("…", color = tint, style = MaterialTheme.typography.bodyMedium)
                playing -> MimIcon(MimIcon.Stop, size = 14.dp, tint = tint)
                else -> Text("▶", color = tint, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column {
            Text(
                if (failed) "语音不可用或已过期" else "语音 " + formatDuration(message.media?.durationMs ?: 0),
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) {
                    if (message.fromMe) Color(0xCCFDFDFB) else MimPalette.Danger
                } else {
                    if (message.fromMe) Color(0xFFFDFDFB) else MimPalette.Ink
                },
            )
            val caption = captionOf(message, "[语音]")
            if (caption.isNotBlank()) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.fromMe) Color(0xCCFDFDFB) else MimPalette.InkSoft,
                )
            }
        }
    }
}

private enum class FileStatus { Idle, Saving, Saved, Failed }

/**
 * 文件消息。
 *
 * 密文只在本地缓存里，点一下才会解密并落到用户选的路径；
 * 保存失败要说明是「解密不出来」还是「写盘失败」，不假装成功。
 */
@Composable
private fun MessageFile(message: ChatMessage, loadMedia: suspend (ChatMessage) -> ByteArray?) {
    val attachment = message.media
    val mine = message.fromMe
    var status by remember(message.id) { mutableStateOf(FileStatus.Idle) }
    val scope = rememberCoroutineScope()
    val ink = if (mine) Color(0xFFFDFDFB) else MimPalette.Ink
    val soft = if (mine) Color(0xCCFDFDFB) else MimPalette.InkSoft
    val badge = if (mine) Color(0x22FFFFFF) else MimPalette.TealSoft

    Row(
        modifier = Modifier
            .widthIn(min = 188.dp, max = 252.dp)
            .clip(RoundedCornerShape(8.dp))
            .mimHoverable(
                shape = RoundedCornerShape(8.dp),
                hoverColor = if (mine) Color(0x1FFFFFFF) else MimPalette.Hover,
                enabled = attachment != null && status != FileStatus.Saving,
                onClick = {
                    val media = attachment ?: return@mimHoverable
                    scope.launch {
                        status = FileStatus.Saving
                        val bytes = runCatching { loadMedia(message) }.getOrNull()
                        if (bytes == null) {
                            status = FileStatus.Failed
                            return@launch
                        }
                        val saved = runCatching { MediaTools.saveFile(media.name ?: "mim-file", bytes) }
                            .getOrNull()
                        status = if (saved != null) FileStatus.Saved else FileStatus.Failed
                    }
                },
            )
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(badge),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                FileStatus.Saving -> Text("…", style = MaterialTheme.typography.bodyMedium, color = soft)
                FileStatus.Saved -> MimIcon(
                    MimIcon.Check,
                    size = 15.dp,
                    tint = if (mine) Color(0xFFFDFDFB) else MimPalette.Teal,
                )
                FileStatus.Failed -> MimIcon(
                    MimIcon.Close,
                    size = 15.dp,
                    tint = if (mine) Color(0xFFFDFDFB) else MimPalette.Danger,
                )
                FileStatus.Idle -> MimIcon(
                    MimIcon.Clip,
                    size = 15.dp,
                    tint = if (mine) Color(0xFFFDFDFB) else MimPalette.Teal,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                attachment?.name ?: message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (status) {
                    FileStatus.Saving -> "解密中…"
                    FileStatus.Saved -> "已保存到本机"
                    FileStatus.Failed -> "文件不可用或已过期，点一下重试"
                    FileStatus.Idle -> MediaTools.humanSize((attachment?.size ?: 0).toLong()) + " · 点击保存"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (status == FileStatus.Failed) {
                    if (mine) Color(0xFFFFD9D4) else MimPalette.Danger
                } else {
                    soft
                },
            )
        }
    }
}

@Composable
private fun MediaPlaceholder(text: String, fromMe: Boolean) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 160.dp, minHeight = 72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (fromMe) Color(0x22FFFFFF) else MimPalette.Paper),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (fromMe) Color(0xCCFDFDFB) else MimPalette.InkSoft,
        )
    }
}

private fun captionOf(message: ChatMessage, placeholder: String): String =
    message.text.takeIf { it.isNotBlank() && it != placeholder }.orEmpty()

@Composable
private fun ComposerIcon(
    icon: MimIcon,
    enabled: Boolean,
    active: Boolean = false,
    tooltip: String? = null,
    onClick: () -> Unit,
) {
    MimTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (active) MimPalette.DangerSoft else MimPalette.Paper)
                .mimHoverable(shape = CircleShape, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            MimIcon(
                icon,
                size = 16.dp,
                tint = when {
                    !enabled -> MimPalette.Line
                    active -> MimPalette.Danger
                    else -> MimPalette.InkSoft
                },
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
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

/** 同步分界：说明这一段是从本账号另一台设备同步来的。 */
@Composable
private fun SyncSeparator(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MimPalette.Brass.copy(alpha = 0.4f))
        Text(
            "以下 $count 条来自另一台设备",
            style = MaterialTheme.typography.labelSmall,
            color = MimPalette.Brass,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MimPalette.Brass.copy(alpha = 0.4f))
    }
}

/** 未读分界：告诉用户从哪条开始是这次打开前没看过的消息。 */
@Composable
private fun UnreadSeparator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MimPalette.Teal.copy(alpha = 0.35f))
        Text(
            "以下为新消息",
            style = MaterialTheme.typography.labelSmall,
            color = MimPalette.Teal,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MimPalette.Teal.copy(alpha = 0.35f))
    }
}

/** 同一人相隔多久仍算一组。超过这个间隔就重新起组，时间也重新显示。 */
private const val GROUP_WINDOW_MS = 3 * 60 * 1000L

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
