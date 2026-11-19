package com.junesoon.mim.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "MiM",
        state = WindowState(size = DpSize(920.dp, 660.dp)),
    ) {
        MimApp()
    }
}
