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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.junesoon.mim.app.domain.TwoFactorSetup

/**
 * 安全设置：开启 / 关闭二次验证，管理一次性恢复码。
 *
 * 开启后服务端会作废其它设备并给本机换发令牌；恢复码只在生成时展示一次。
 * 这里不提供「查看已有恢复码」——服务端只存哈希，本来就取不回。
 */
@Composable
fun SecurityScreen(
    enabled: Boolean,
    setup: TwoFactorSetup?,
    recoveryCodes: List<String>,
    busy: Boolean,
    error: String?,
    notice: String?,
    onBack: () -> Unit,
    onStartSetup: () -> Unit,
    onConfirmSetup: (String) -> Unit,
    onCancelSetup: () -> Unit,
    onDisable: (String, String) -> Unit,
    onRegenerate: (String, String) -> Unit,
    onDismissCodes: () -> Unit,
) {
    var showDisable by remember { mutableStateOf(false) }
    var showRegenerate by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(MimPalette.Paper)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MimPalette.Card)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                MimIcon(MimIcon.Back, size = 17.dp, tint = MimPalette.Ink)
            }
            Column {
                Text("安全设置", style = MaterialTheme.typography.titleMedium)
                Text(
                    "二次验证与恢复码",
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

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MimPalette.Card)
                    .padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(MimPalette.TealSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    MimIcon(MimIcon.Shield, size = 20.dp, tint = MimPalette.Teal)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("二次验证（TOTP）", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Tag(
                            if (enabled) "已开启" else "未开启",
                            if (enabled) MimPalette.Teal else MimPalette.InkSoft,
                            if (enabled) MimPalette.TealSoft else MimPalette.Paper,
                        )
                    }
                    Text(
                        "登录时除密码外，再输入验证器里的 6 位动态码；丢失验证器时可用一次性恢复码登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    if (!enabled) {
                        Button(
                            onClick = onStartSetup,
                            enabled = !busy,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MimPalette.Teal),
                        ) {
                            Text("开启二次验证", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showRegenerate = true }, enabled = !busy) {
                                Text("重新生成恢复码")
                            }
                            TextButton(onClick = { showDisable = true }, enabled = !busy) {
                                Text("关闭二次验证", color = MimPalette.Danger)
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MimPalette.BrassSoft)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MimIcon(MimIcon.Lock, size = 15.dp, tint = MimPalette.Brass)
                Text(
                    "开启或关闭二次验证后，其它设备会被强制重新登录；恢复码只显示一次，请离线保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF3E2B12),
                )
            }
        }
    }

    if (setup != null) {
        SetupDialog(
            setup = setup,
            busy = busy,
            onCancel = onCancelSetup,
            onConfirm = onConfirmSetup,
        )
    }

    if (recoveryCodes.isNotEmpty()) {
        RecoveryCodesDialog(codes = recoveryCodes, onClose = onDismissCodes)
    }

    if (showDisable) {
        SecondFactorDialog(
            title = "关闭二次验证",
            description = "需要当前密码，以及 6 位动态验证码或一条恢复码。",
            busy = busy,
            onDismiss = { showDisable = false },
            onConfirm = { password, code ->
                showDisable = false
                onDisable(password, code)
            },
        )
    }

    if (showRegenerate) {
        SecondFactorDialog(
            title = "重新生成恢复码",
            description = "旧恢复码会全部失效。需要当前密码与 6 位动态验证码或一条恢复码。",
            busy = busy,
            onDismiss = { showRegenerate = false },
            onConfirm = { password, code ->
                showRegenerate = false
                onRegenerate(password, code)
            },
        )
    }
}


@Composable
private fun SetupDialog(
    setup: TwoFactorSetup,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("开启二次验证") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "1. 在验证器（Google Authenticator、1Password 等）中添加下面的密钥。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CopyRow(
                    label = "共享密钥",
                    value = setup.secret,
                    onCopy = { clipboard.setText(AnnotatedString(setup.secret)) },
                )
                CopyRow(
                    label = "otpauth 链接",
                    value = setup.otpauthUri,
                    onCopy = { clipboard.setText(AnnotatedString(setup.otpauthUri)) },
                )
                Text(
                    "2. 输入验证器当前显示的 6 位验证码完成启用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("6 位验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
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
        confirmButton = {
            TextButton(onClick = { onConfirm(code) }, enabled = !busy && code.length == 6) {
                Text("启用")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
        containerColor = MimPalette.Card,
    )
}

@Composable
private fun CopyRow(label: String, value: String, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MimPalette.Paper)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "复制",
                style = MaterialTheme.typography.labelSmall,
                color = MimPalette.Teal,
                modifier = Modifier.clickable { onCopy() },
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MimPalette.TealDeep,
        )
    }
}

@Composable
private fun RecoveryCodesDialog(codes: List<String>, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("恢复码（只显示这一次）") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "每行只能用一次。丢失验证器时，用其中一条代替 6 位验证码登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MimPalette.Danger,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MimPalette.Paper)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    codes.forEach { code ->
                        Text(
                            code,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
                Text(
                    "全选复制",
                    style = MaterialTheme.typography.labelMedium,
                    color = MimPalette.Teal,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(codes.joinToString("\n")))
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("我已保存") } },
        containerColor = MimPalette.Card,
    )
}

@Composable
private fun SecondFactorDialog(
    title: String,
    description: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String, code: String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("当前密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MimPalette.Teal,
                        unfocusedBorderColor = MimPalette.Line,
                        cursorColor = MimPalette.Teal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("6 位验证码或恢复码") },
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
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password, code) },
                enabled = !busy && password.isNotEmpty() && code.isNotBlank(),
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = MimPalette.Card,
    )
}
