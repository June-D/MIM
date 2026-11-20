package com.junesoon.mim.app.data

/**
 * 账号开启了二次验证，登录还需要 6 位动态验证码或一次性恢复码。
 *
 * 服务端用业务状态码 40102 表达这件事；它不是凭据错误，
 * 界面应该补一个输入框而不是清空账号密码。
 */
class TwoFactorRequiredException(message: String) : IllegalStateException(message)
