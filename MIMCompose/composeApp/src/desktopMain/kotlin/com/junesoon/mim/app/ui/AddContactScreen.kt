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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junesoon.mim.app.domain.FriendRequest
import com.junesoon.mim.app.domain.UserSummary

/**
 * 添加联系人：搜索用户 + 处理好友申请。
 *
 * 申请必须对方同意才建立关系；对方的申请也能在这里同意或拒绝。
 */
@Composable
fun AddContactScreen(
    results: List<UserSummary>,
    searched: Boolean,
    requests: List<FriendRequest>,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onRequest: (String) -> Unit,
    onRespond: (Long, Boolean) -> Unit,
    error: String? = null,
    notice: String? = null,
) {
    var keyword by remember { mutableStateOf("") }
    val incoming = requests.filter { it.incoming && it.pending }
    val outgoing = requests.filter { !it.incoming && it.pending }

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
                Text("添加联系人", style = MaterialTheme.typography.titleMedium)
                Text(
                    "对方同意后才会建立联系",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (error != null || notice != null) {
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MimSearchField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = "输入对方的用户名",
                onSubmit = { onSearch(keyword) },
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (keyword.isBlank()) MimPalette.LineSoft else MimPalette.Teal,
                    )
                    .mimHoverable(
                        shape = RoundedCornerShape(10.dp),
                        hoverColor = Color(0x1FFFFFFF),
                        pressColor = Color(0x38FFFFFF),
                        enabled = keyword.isNotBlank(),
                        onClick = { onSearch(keyword) },
                    )
                    .padding(horizontal = 15.dp, vertical = 11.dp),
            ) {
                Text(
                    "搜索",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (keyword.isBlank()) MimPalette.InkSoft else MimPalette.Card,
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (searched) {
                item { SectionTitle("搜索结果") }
                if (results.isEmpty()) {
                    item { Hint("没有可添加的用户：对方不存在，或已经是你的联系人。") }
                }
                items(results, key = { "u:" + it.username }) { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(name = user.nickname, size = 40.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                user.nickname,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                user.username + if (user.canReceive) "" else " · 尚未建立加密身份",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onRequest(user.username) }) { Text("发送申请") }
                    }
                    HorizontalDivider(color = MimPalette.LineSoft, modifier = Modifier.padding(start = 72.dp))
                }
            }

            item { SectionTitle("待我处理") }
            if (incoming.isEmpty()) {
                item { Hint("暂无新的好友申请。") }
            }
            items(incoming, key = { "in:" + it.id }) { request ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(name = request.username, size = 40.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(request.username, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "请求加你为联系人",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRespond(request.id, true) }) { Text("同意") }
                    TextButton(onClick = { onRespond(request.id, false) }) {
                        Text("拒绝", color = MimPalette.InkSoft)
                    }
                }
                HorizontalDivider(color = MimPalette.LineSoft, modifier = Modifier.padding(start = 72.dp))
            }

            if (outgoing.isNotEmpty()) {
                item { SectionTitle("我发出的") }
                items(outgoing, key = { "out:" + it.id }) { request ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(name = request.username, size = 36.dp)
                        Text(
                            request.username,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Tag("等待同意", MimPalette.Brass, MimPalette.BrassSoft)
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MimPalette.Teal,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
