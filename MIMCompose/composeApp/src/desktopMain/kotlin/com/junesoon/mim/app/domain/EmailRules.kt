package com.junesoon.mim.app.domain

/**
 * 邮箱规则：与服务端 `ValidationUtil.validateEmail` 保持一致。
 *
 * 只做基本格式与长度检查，不验证送达性；注册、绑定找回邮箱、找回密码三处共用，
 * 避免三份正则各自漂移。
 */
object EmailRules {

    private val Pattern = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    private const val MAX_LENGTH = 254

    fun isValid(value: String): Boolean =
        value.length <= MAX_LENGTH && Pattern.matches(value)
}
