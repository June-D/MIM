package com.junesoon.mim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.junesoon.mim.app.state.AuthMode

/**
 * 登录 / 注册 / 找回密码。
 *
 * 左侧是品牌与安全说明，右侧是表单：三种模式共用同一套壳，
 * 切换时不清空已输入的账号，减少重复输入。
 */
@Composable
fun AuthScreen(
    mode: AuthMode,
    busy: Boolean,
    error: String?,
    notice: String?,
    initialUsername: String,
    initialPassword: String,
    initialDeviceName: String,
    rememberMe: Boolean,
    onModeChange: (AuthMode) -> Unit,
    onLogin: (String, String, String, Boolean) -> Unit,
    onRegister: (String, String, String, String) -> Unit,
    onSendCode: (String) -> Unit,
    onReset: (String, String, String) -> Unit,
    onClearFeedback: () -> Unit,
) {
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember(initialPassword) { mutableStateOf(initialPassword) }
    var confirm by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var deviceName by remember(initialDeviceName) {
        mutableStateOf(initialDeviceName.ifBlank { "Desktop" })
    }
    var remember by remember(rememberMe) { mutableStateOf(rememberMe) }
    var agreed by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier.fillMaxSize().background(MimPalette.Paper),
    ) {
        BrandPanel(modifier = Modifier.weight(0.44f).fillMaxHeight())
        Box(
            modifier = Modifier.weight(0.56f).fillMaxHeight().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 392.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (mode != AuthMode.Recover) {
                    AuthTabs(mode = mode, onChange = onModeChange)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when (mode) {
                            AuthMode.Login -> "欢迎回来"
                            AuthMode.Register -> "创建账号"
                            AuthMode.Recover -> "找回密码"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        when (mode) {
                            AuthMode.Login -> "用你的账号继续一段加密对话。"
                            AuthMode.Register -> "账号创建后会在这台设备生成专属密钥。"
                            AuthMode.Recover -> "通过账号绑定的邮箱验证身份。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(2.dp))
                MimField(
                    value = username,
                    onValueChange = { username = it; localError = null; onClearFeedback() },
                    label = "用户名",
                )

                when (mode) {
                    AuthMode.Login -> {
                        MimField(
                            value = password,
                            onValueChange = { password = it; localError = null; onClearFeedback() },
                            label = "密码",
                            secret = true,
                        )
                        MimField(
                            value = deviceName,
                            onValueChange = { deviceName = it },
                            label = "这台设备的名称",
                        )
                        RememberRow(remember = remember, onChange = { remember = it })
                        PrimaryButton(
                            text = "登录",
                            busy = busy,
                            onClick = {
                                localError = null
                                onLogin(username, password, deviceName, remember)
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            LinkText("忘记密码？") { onModeChange(AuthMode.Recover) }
                            LinkText("还没有账号？注册") { onModeChange(AuthMode.Register) }
                        }
                    }

                    AuthMode.Register -> {
                        MimField(
                            value = password,
                            onValueChange = { password = it; localError = null; onClearFeedback() },
                            label = "密码",
                            secret = true,
                        )
                        MimField(
                            value = confirm,
                            onValueChange = { confirm = it; localError = null },
                            label = "确认密码",
                            secret = true,
                        )
                        MimField(
                            value = email,
                            onValueChange = { email = it },
                            label = "邮箱（可选，用于找回密码）",
                        )
                        MimField(
                            value = deviceName,
                            onValueChange = { deviceName = it },
                            label = "这台设备的名称",
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = agreed,
                                onCheckedChange = { agreed = it; localError = null },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MimPalette.Teal,
                                    uncheckedColor = MimPalette.Line,
                                ),
                            )
                            Text(
                                "我已阅读并同意",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinkText("《用户协议》") { showTerms = true }
                        }
                        PrimaryButton(
                            text = "注册并登录",
                            busy = busy,
                            onClick = {
                                localError = when {
                                    password.length < 8 -> "密码至少 8 位"
                                    password != confirm -> "两次输入的密码不一致"
                                    !agreed -> "请先阅读并同意用户协议"
                                    else -> null
                                }
                                if (localError == null) {
                                    onRegister(username, password, email, deviceName)
                                }
                            },
                        )
                        LinkText("已有账号？返回登录") { onModeChange(AuthMode.Login) }
                    }

                    AuthMode.Recover -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(Modifier.weight(1f)) {
                                MimField(
                                    value = code,
                                    onValueChange = { code = it; localError = null },
                                    label = "邮箱验证码",
                                )
                            }
                            TextButton(
                                onClick = { onSendCode(username) },
                                enabled = !busy,
                            ) { Text("发送验证码") }
                        }
                        MimField(
                            value = password,
                            onValueChange = { password = it; localError = null },
                            label = "新密码",
                            secret = true,
                        )
                        PrimaryButton(
                            text = "重置密码",
                            busy = busy,
                            onClick = {
                                localError = if (password.length < 8) "新密码至少 8 位" else null
                                if (localError == null) {
                                    onReset(username, code, password)
                                }
                            },
                        )
                        Text(
                            "需要账号绑定邮箱，且服务端已配置邮件服务；否则会明确提示不可用。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinkText("返回登录") { onModeChange(AuthMode.Login) }
                    }
                }

                Feedback(localError ?: error, notice)
            }
        }
    }

    if (showTerms) {
        TermsDialog(
            onDismiss = { showTerms = false },
            onAccept = {
                agreed = true
                localError = null
                showTerms = false
            },
        )
    }
}

@Composable
private fun BrandPanel(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(Color(0xFF0A3B33), Color(0xFF10614F))),
        ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val base = Color(0x18FFFFFF)
            for (index in 1..4) {
                drawCircle(
                    color = base,
                    radius = size.minDimension * (0.18f + index * 0.11f),
                    center = Offset(size.width * 0.92f, size.height * 0.12f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(40.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                "MiM",
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFFFDFDFB),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "私密对话，只属于你们。",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFDDEBE6),
            )
            Spacer(Modifier.height(28.dp))
            SecurityPoint("密钥只在本机", "服务器不保存私钥，也无法代你解密。")
            SecurityPoint("消息端到端加密", "每条会话都有独立的双棘轮状态。")
            SecurityPoint("可核对安全码", "首次对话前，当面对照 60 位安全码。")
            Spacer(Modifier.height(26.dp))
            Text(
                "服务器只转发密文，并保存收发所必需的元数据。",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB9D4CC),
            )
        }
    }
}

@Composable
private fun SecurityPoint(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(30.dp).background(Color(0x22FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MimIcon(MimIcon.Lock, size = 15.dp, tint = Color(0xFFDDEBE6))
        }
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Color(0xFFF4FAF7))
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB9D4CC),
            )
        }
    }
}

@Composable
private fun AuthTabs(mode: AuthMode, onChange: (AuthMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        listOf(AuthMode.Login to "登录", AuthMode.Register to "注册").forEach { (value, label) ->
            val selected = mode == value
            Column(
                modifier = Modifier.clickable { onChange(value) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MimPalette.Teal else MimPalette.InkSoft,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .width(22.dp)
                        .height(2.dp)
                        .background(if (selected) MimPalette.Teal else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun MimField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MimPalette.Teal,
            unfocusedBorderColor = MimPalette.Line,
            focusedContainerColor = Color(0xFFFCFBF8),
            unfocusedContainerColor = Color(0xFFFCFBF8),
            focusedLabelColor = MimPalette.Teal,
            cursorColor = MimPalette.Teal,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RememberRow(remember: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = remember,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = MimPalette.Teal,
                uncheckedColor = MimPalette.Line,
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("记住账号密码", style = MaterialTheme.typography.bodySmall)
            Text(
                "本机加密保存，仅限这台设备；公共电脑请勿勾选。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MimPalette.Teal),
        modifier = Modifier.fillMaxWidth().height(46.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color(0xFFFDFDFB),
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MimPalette.Teal,
        modifier = Modifier.clickable { onClick() },
    )
}

@Composable
private fun Feedback(error: String?, notice: String?) {
    val message = error ?: notice ?: return
    val isError = error != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isError) MimPalette.DangerSoft else MimPalette.TealSoft,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MimPalette.Danger else MimPalette.TealDeep,
        )
    }
}

@Composable
private fun TermsDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    val terms = remember { loadTerms() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用户协议") },
        text = {
            Text(
                terms,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("我已阅读并同意") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        containerColor = MimPalette.Card,
        textContentColor = MimPalette.InkSoft,
        titleContentColor = MimPalette.Ink,
    )
}

private fun loadTerms(): String =
    runCatching {
        val stream = object {}.javaClass.getResourceAsStream("/terms/2026-11.txt")
            ?: return@runCatching "用户协议文本缺失，请更新客户端后重试。"
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrElse { "用户协议文本缺失，请更新客户端后重试。" }
