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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junesoon.mim.app.domain.MessageHit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 消息搜索。
 *
 * 只搜索本机已缓存的加密消息——服务端没有明文，换设备或清空保险库后搜不到旧消息。
 * 命中后进入对应会话并滚动到那条消息。
 */
@Composable
fun SearchScreen(
    hits: List<MessageHit>,
    query: String,
    searched: Boolean,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenHit: (contactId: String, messageId: String) -> Unit,
) {
    var keyword by remember { mutableStateOf(query) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MimPalette.Paper)
            .escapeBack { onBack() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MimPalette.Card)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MimIconAction(MimIcon.Back, tooltip = "返回") { onBack() }
            Column {
                Text("搜索消息", style = MaterialTheme.typography.titleMedium)
                Text(
                    "仅搜索本机缓存的加密消息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MimSearchField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = "输入关键词",
                focusRequester = focus,
                onSubmit = { onSearch(keyword) },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onSearch(keyword) },
                enabled = keyword.isNotBlank(),
            ) { Text("搜索") }
        }

        when {
            !searched -> Hint("输入关键词后回车。可搜索文字、图片说明与语音说明。")
            hits.isEmpty() -> Hint("没有找到包含「$query」的消息。")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        "共 ${hits.size} 条结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                    )
                }
                items(hits, key = { it.messageId }) { hit ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .mimHoverable(shape = RoundedCornerShape(10.dp)) {
                                onOpenHit(hit.contactId, hit.messageId)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(name = hit.contactName, size = 38.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    hit.contactName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    timeLabel(hit.epochMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (hit.kind != "text") {
                                    Spacer(Modifier.width(6.dp))
                                    Tag(
                                        if (hit.kind == "image") "图片" else "语音",
                                        MimPalette.Teal,
                                        MimPalette.TealSoft,
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildString {
                                    if (hit.fromMe) append("我：")
                                    append(hit.text)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MimPalette.LineSoft,
                        modifier = Modifier.padding(start = 70.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("M-d HH:mm")

private fun timeLabel(epochMs: Long): String =
    if (epochMs <= 0) {
        ""
    } else {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFormatter)
    }
