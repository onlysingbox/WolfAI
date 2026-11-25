package com.onlysingbox.wolfai

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WolfAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var active: WolfAccessibilityService? = null
    }

    override fun onServiceConnected() { super.onServiceConnected(); active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

    fun readVisibleText(limit: Int = 100): String {
        val root = rootInActiveWindow ?: return "صفحه فعالی در دسترس نیست."
        val lines = mutableListOf<String>()
        collectText(root, lines, limit)
        return lines.distinct().joinToString("\n").ifBlank { "متن قابل‌خواندن در صفحه پیدا نشد." }
    }

    fun clickVisibleText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, query) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return false
    }

    fun clickVisibleTextExact(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeMatch(root, query.trim(), true) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return false
    }

    fun longClickVisibleText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, query) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun enterText(fieldHint: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, fieldHint) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val forward = direction.equals("down", true) || direction.equals("forward", true)
        return scrollNode(root, forward)
    }

    private fun scrollNode(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        if (node.isScrollable) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(action)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollNode(child, forward)) return true
        }
        return false
    }

    fun global(command: String): Boolean {
        val action = when (command.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            else -> return false
        }
        return performGlobalAction(action)
    }

    private fun findNode(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val target = query.trim()
        if (target.isBlank()) return null
        val exact = findNodeMatch(node, target, true)
        return exact ?: findNodeMatch(node, target, false)
    }

    private fun findNodeMatch(node: AccessibilityNodeInfo, query: String, exact: Boolean): AccessibilityNodeInfo? {
        val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .map { it.trim() }.filter { it.isNotBlank() }
        val matches = if (exact) labels.any { it.equals(query, ignoreCase = true) }
            else labels.any { it.contains(query, ignoreCase = true) }
        if (matches) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeMatch(child, query, exact)
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
