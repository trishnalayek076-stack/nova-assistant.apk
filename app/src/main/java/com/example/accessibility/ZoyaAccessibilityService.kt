package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ZoyaAccessibilityService : AccessibilityService() {

    companion object {
        var shouldAutoClick = false
            set(value) {
                field = value
                if (value) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        field = false
                    }, 10000) // Reset after 10 seconds
                }
            }
        var pendingMessageToSend: String? = null
        var instance: ZoyaAccessibilityService? = null

        fun dispatchGestureClick(x: Float, y: Float): Boolean {
            val inst = instance ?: return false
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            return inst.dispatchGesture(builder.build(), null, null)
        }

        fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
            val inst = instance ?: return false
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            return inst.dispatchGesture(builder.build(), null, null)
        }

        fun clickTextOnScreen(text: String): Boolean {
            val inst = instance ?: return false
            val root = inst.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    val bounds = Rect()
                    current.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && (current.isClickable || current == node)) {
                        val x = bounds.centerX().toFloat()
                        val y = bounds.centerY().toFloat()
                        if (dispatchGestureClick(x, y)) return true
                    }
                    current = current.parent
                }
            }
            return false
        }

        fun typeTextIntoFocusedOrFirstInput(text: String): Boolean {
            val inst = instance ?: return false
            val root = inst.rootInActiveWindow ?: return false
            
            // Try focused input first
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }

            // Find any editable node
            val editableNode = findFirstEditableNode(root)
            if (editableNode != null) {
                editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                return editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            return false
        }

        private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                val found = findFirstEditableNode(child)
                if (found != null) return found
            }
            return null
        }

        fun readAllScreenContent(): String {
            val inst = instance ?: return "Accessibility Service is not enabled. Please enable NOVA Automation in Accessibility Settings."
            val root = inst.rootInActiveWindow ?: return "Screen content is currently blank or protected."
            
            val builder = StringBuilder()
            val textList = mutableListOf<String>()
            extractNodeTexts(root, textList)
            
            if (textList.isEmpty()) {
                return "No readable text detected on the current screen."
            }
            
            builder.append("Screen Elements Detected:\n")
            textList.distinct().take(30).forEachIndexed { index, text ->
                builder.append("${index + 1}. $text\n")
            }
            return builder.toString().trim()
        }

        private fun extractNodeTexts(node: AccessibilityNodeInfo?, output: MutableList<String>) {
            if (node == null) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            
            if (!text.isNullOrEmpty() && text.length > 1) {
                output.add(text)
            }
            if (!desc.isNullOrEmpty() && desc != text && desc.length > 1) {
                output.add("[$desc]")
            }
            for (i in 0 until node.childCount) {
                extractNodeTexts(node.getChild(i), output)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("NOVA_Accessibility", "NOVA Automation Service Connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        if (shouldAutoClick) {
            val packageName = event.packageName?.toString() ?: ""
            if (packageName.contains("whatsapp")) {
                val rootNode = rootInActiveWindow ?: return
                
                // If there's text to paste/type into WhatsApp chat box first
                pendingMessageToSend?.let { msg ->
                    typeTextIntoFocusedOrFirstInput(msg)
                    pendingMessageToSend = null
                }

                val clicked = searchAndClickSendButton(rootNode)
                if (clicked) {
                    Log.d("NOVA_Accessibility", "WhatsApp send button clicked successfully!")
                    shouldAutoClick = false
                }
            }
        }
    }

    private fun searchAndClickSendButton(node: AccessibilityNodeInfo): Boolean {
        val idsToTry = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send"
        )
        for (id in idsToTry) {
            val sendButtons = node.findAccessibilityNodeInfosByViewId(id)
            if (sendButtons.isNotEmpty()) {
                for (button in sendButtons) {
                    if (performClick(button)) {
                        Log.d("NOVA_Accessibility", "Clicked send button by ID: $id")
                        return true
                    }
                }
            }
        }

        return recursiveSearchAndClick(node)
    }

    private fun recursiveSearchAndClick(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        
        if (desc == "send" || desc == "bheje" || desc == "bhejen" || desc == "envio" || desc == "enviar") {
            if (performClick(node)) {
                Log.d("NOVA_Accessibility", "Clicked send button by content description: $desc")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (recursiveSearchAndClick(child)) {
                    return true
                }
            }
        }
        return false
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            if (dispatchGestureClick(x, y)) {
                return true
            }
        }

        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    override fun onInterrupt() {
        Log.d("NOVA_Accessibility", "Accessibility Service Interrupted")
    }
}
