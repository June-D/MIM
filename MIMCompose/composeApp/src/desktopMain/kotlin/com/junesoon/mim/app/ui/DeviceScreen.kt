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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junesoon.mim.app.domain.DeviceInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备管理：查看本账号设备，注销其它设备。
 *
 * 注销是软删除：被注销设备的令牌立即失效，需要重新登录并登记新设备。
 */
@Composable
fun DeviceScreen(
    devices: List<DeviceInfo>,
    onBack: () -> Unit,
    onRevoke: (String) -> Unit,
    /** 已批准历史同步的设备；只在本机记录。 */
    trustedDevices: Set<String> = emptySet(),
    onRevokeTrust: (String) -> Unit = {},
    notice: String? = null,
    error: String? = null,
) {
    Column(modifier = Modifier.fillMaxSize().background(MimPalette.Paper)) {
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
                Text("设备", style = MaterialTheme.typography.titleMedium)
                Text(
                    "每台设备有独立的密钥与会话",
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

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(devices, key = { it.deviceId }) { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MimPalette.TealSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        MimIcon(MimIcon.Device, size = 19.dp, tint = MimPalette.Teal)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                device.deviceName.ifBlank { device.deviceId },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (device.current) {
                                Spacer(Modifier.height(0.dp))
                                Box(Modifier.padding(start = 8.dp)) {
                                    Tag("本机", MimPalette.Teal, MimPalette.TealSoft)
                                }
                            }
                        }
                        Text(
                            "标识 ${device.deviceId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (device.deviceId in trustedDevices) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Tag("已批准历史同步", MimPalette.Teal, MimPalette.TealSoft)
                                Text(
                                    "撤销授权",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MimPalette.InkSoft,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .mimHoverable(
                                            shape = RoundedCornerShape(4.dp),
                                            onClick = { onRevokeTrust(device.deviceId) },
                                        )
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                        device.lastSeen?.let {
                            Text(
                                "最近活跃 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(Date(it)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!device.current) {
                        TextButton(onClick = { onRevoke(device.deviceId) }) {
                            Text("注销", color = MimPalette.Danger)
                        }
                    }
                }
                HorizontalDivider(color = MimPalette.LineSoft, modifier = Modifier.padding(start = 72.dp))
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MimPalette.Card)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MimIcon(MimIcon.Shield, size = 15.dp, tint = MimPalette.Teal)
                    Text(
                        "注销后该设备立即失去访问能力。若这里不是你的设备，建议同时修改密码。" +
                            "「已批准历史同步」只影响它能否拉取历史，与本机缓存和消息收发无关。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
