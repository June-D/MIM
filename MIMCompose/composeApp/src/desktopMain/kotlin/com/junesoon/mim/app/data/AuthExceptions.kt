package com.junesoon.mim.app.data

/**
 * 账号开启了二次验证，登录还需要 6 位动态验证码或一次性恢复码。
 *
 * 服务端用业务状态码 40102 表达这件事；它不是凭据错误，
 * 界面应该补一个输入框而不是清空账号密码。
 */
class TwoFactorRequiredException(message: String) : IllegalStateException(message)

/**
 * 账号或密码错误。
 *
 * 单独成类型是因为「本机登录保护」要按它计数：网络错误、限流、二次验证码错误
 * 都不能算进去，否则一次弱网或一次手滑就会吃掉用户的历史。
 */
class InvalidCredentialsException(message: String) : IllegalStateException(message)
