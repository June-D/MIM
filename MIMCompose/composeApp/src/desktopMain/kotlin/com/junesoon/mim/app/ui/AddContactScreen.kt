package com.junesoon.mim.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junesoon.mim.app.domain.FriendRequest
import com.junesoon.mim.app.domain.UserSummary

/**
 * 添加联系人：搜索用户 + 处理好友申请。
 *
 * 申请必须对方同意才建立关系；对方的申请也能在这里同意或拒绝。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    results: List<UserSummary>,
    searched: Boolean,
    requests: List<FriendRequest>,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onRequest: (String) -> Unit,
    onRespond: (Long, Boolean) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val incoming = requests.filter { it.incoming && it.pending }
    val outgoing = requests.filter { !it.incoming && it.pending }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                title = { Text("添加联系人") },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSearch(keyword) }) { Text("搜索") }
                }
            }

            if (searched) {
                item { SectionTitle("搜索结果") }
                if (results.isEmpty()) {
                    item { Hint("没有可添加的用户（可能已是好友或账号不存在）") }
                }
                items(results, key = { "u:" + it.username }) { user ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user.nickname, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                user.username + if (user.canReceive) "" else "（尚未建立加密身份）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onRequest(user.username) }) { Text("发送申请") }
                    }
                }
            }

            item { SectionTitle("待我处理") }
            if (incoming.isEmpty()) {
                item { Hint("暂无好友申请") }
            }
            items(incoming, key = { "in:" + it.id }) { request ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(request.username, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "请求加你为联系人",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRespond(request.id, true) }) { Text("同意") }
                    TextButton(onClick = { onRespond(request.id, false) }) { Text("拒绝") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }

            if (outgoing.isNotEmpty()) {
                item { SectionTitle("我发出的") }
                items(outgoing, key = { "out:" + it.id }) { request ->
                    Hint("${request.username}：等待对方同意")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
