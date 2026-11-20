package com.junesoon.mim.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "MiM · 私密通讯",
        state = WindowState(size = DpSize(1000.dp, 700.dp)),
    ) {
        MimApp()
    }
}
