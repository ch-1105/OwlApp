package com.phoneclaw.app.learner

import com.phoneclaw.app.explorer.AccessibilityNodeSnapshot

object ExplorationSafetyFilter {
    private val FORBIDDEN_PATTERNS = listOf(
        "发送", "send",
        "删除", "delete", "移除", "remove",
        "付款", "pay", "支付", "purchase", "购买",
        "确认支付", "确认付款",
        "转账", "transfer",
        "清空", "clear all",
        "注销", "退出登录", "logout", "sign out",
        "卸载", "uninstall",
        "格式化", "format",
        "重置", "reset",
    )

    fun isSafeToClick(node: AccessibilityNodeSnapshot): Boolean {
        if (!node.isClickable) return false
        if (node.isEditable) return false

        val label = buildString {
            node.text?.let { append(it.lowercase()); append(' ') }
            node.contentDescription?.let { append(it.lowercase()) }
        }.trim()

        if (label.isBlank()) return true

        return FORBIDDEN_PATTERNS.none { pattern -> label.contains(pattern) }
    }
}
