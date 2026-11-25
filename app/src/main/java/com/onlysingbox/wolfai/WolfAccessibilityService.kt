package com.onlysingbox.wolfai

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WolfAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var active: WolfAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    fun readVisibleText(limit: Int = 80): String {
        val root = rootInActiveWindow ?: return "صفحه فعالی در دسترس نیست."
        val lines = mutableListOf<String>()
        collectText(root, lines, limit)
        return lines.distinct().joinToString("\n").ifBlank { "متن قابل‌خواندن در صفحه پیدا نشد." }
    }

    fun clickVisibleText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findNode(root, query)?.let { node ->
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) return@let current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current = current.parent
            }
            false
        } ?: false
    }

    fun enterText(fieldHint: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, fieldHint) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findNode(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
        if (label.contains(query, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNode(child, query)
            if (found != null) return found
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, output: MutableList<String>, limit: Int) {
        if (output.size >= limit) return
        val value = node.text?.toString()?.trim().orEmpty()
        if (value.isNotBlank()) output.add(value.take(300))
        for (i in 0 until node.childCount) {
            if (output.size >= limit) return
            node.getChild(i)?.let { collectText(it, output, limit) }
        }
    }
}
