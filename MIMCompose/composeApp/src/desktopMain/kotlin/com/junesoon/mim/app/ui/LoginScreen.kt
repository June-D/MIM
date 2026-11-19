package com.junesoon.mim.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    busy: Boolean,
    error: String?,
    onLogin: (username: String, password: String, deviceName: String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("desktop") }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MiM", style = MaterialTheme.typography.displaySmall)
        Text(
            "端到端加密的私密通讯",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(28.dp))

        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("设备名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = { onLogin(username, password, deviceName) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("登录")
                }
            }
            Text(
                "协议与密钥由 mim-core 处理；界面不接触明文密钥。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
