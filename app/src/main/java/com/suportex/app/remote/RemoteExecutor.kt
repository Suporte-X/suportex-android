package com.suportex.app.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

object RemoteExecutor {
    private const val TAG = "SXS/CTRL"
    private const val TAP_DURATION_MS = 100L
    private const val DEFAULT_LONG_PRESS_MS = 2000L
    private const val DEFAULT_DRAG_SEGMENT_MS = 120L
    private const val POINTER_HOLD_MS = 10000L
    @Volatile private var svc: RemoteControlService? = null
    @Volatile private var captureFrameWidth = 0
    @Volatile private var captureFrameHeight = 0
    @Volatile private var ongoingDrag: GestureDescription.StrokeDescription? = null
    @Volatile private var lastDragPoint: Pair<Float, Float>? = null
    @Volatile private var ongoingPointer: GestureDescription.StrokeDescription? = null
    @Volatile private var lastPointerPoint: Pair<Float, Float>? = null

    fun bind(service: RemoteControlService) {
        svc = service
    }

    fun unbind(service: RemoteControlService) {
        if (svc === service) {
            svc = null
            clearCaptureFrameSize()
            ongoingDrag = null
            lastDragPoint = null
            ongoingPointer = null
            lastPointerPoint = null
        }
    }

    fun isReady(): Boolean = svc != null

    fun disableAccessibilityService(): Boolean {
        val service = svc ?: return false
        Handler(Looper.getMainLooper()).post {
            runCatching { service.disableSelf() }
                .onFailure { error ->
                    Log.w(TAG, "Falha ao desativar o Serviço de Acessibilidade", error)
                }
        }
        return true
    }

    fun tap(xNorm: Float, yNorm: Float) {
        val (x, y) = normalizeToPx(xNorm, yNorm) ?: return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        dispatchGesture(stroke)
    }

    fun longPress(xNorm: Float, yNorm: Float, durationMs: Long = DEFAULT_LONG_PRESS_MS) {
        val (x, y) = normalizeToPx(xNorm, yNorm) ?: return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        dispatchGesture(stroke)
    }

    fun drag(
        xStartNorm: Float,
        yStartNorm: Float,
        xEndNorm: Float,
        yEndNorm: Float,
        durationMs: Long = 450L
    ) {
        swipe(xStartNorm, yStartNorm, xEndNorm, yEndNorm, durationMs)
    }

    fun dragStart(xNorm: Float, yNorm: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val (x, y) = normalizeToPx(xNorm, yNorm) ?: return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            DEFAULT_DRAG_SEGMENT_MS,
            true
        )
        dispatchGesture(stroke)
        ongoingDrag = stroke
        lastDragPoint = x to y
    }

    fun pointerDown(xNorm: Float, yNorm: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val (x, y) = normalizeToPx(xNorm, yNorm) ?: return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            POINTER_HOLD_MS,
            true
        )
        dispatchGesture(stroke)
        ongoingPointer = stroke
        lastPointerPoint = x to y
    }

    fun pointerMove(xNorm: Float, yNorm: Float, durationMs: Long = DEFAULT_DRAG_SEGMENT_MS) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val previous = ongoingPointer ?: return
        val lastPoint = lastPointerPoint ?: return
        val (x, y) = normalizeToPx(xNorm, yNorm) ?: return
        val path = Path().apply {
            moveTo(lastPoint.first, lastPoint.second)
            lineTo(x, y)
        }
        val stroke = previous.continueStroke(path, 0, durationMs.coerceAtLeast(1), true)
        dispatchGesture(stroke)
        ongoingPointer = stroke
        lastPointerPoint = x to y
    }

    fun pointerUp(xNorm: Float, yNorm: Float, durationMs: Long = DEFAULT_DRAG_SEGMENT_MS) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val previous = ongoingPointer
        val lastPoint = lastPointerPoint
        val (x, y) = normalizeToPx(xNorm, yNorm) ?: return
        if (previous == null || lastPoint == null) {
            swipe(xNorm, yNorm, xNorm, yNorm, durationMs)
            return
        }
        val path = Path().apply {
            moveTo(lastPoint.first, lastPoint.second)
            lineTo(x, y)
        }
        val stroke = previous.continueStroke(path, 0, durationMs.coerceAtLeast(1), false)
        dispatchGesture(stroke)
        ongoingPointer = null
        lastPointerPoint = null
    }

    fun dragMove(xNorm: Float, yNorm: Float, durationMs: Long = DEFAULT_DRAG_SEGMENT_MS) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val previous = ongoingDrag ?: return
        val lastPoint = lastDragPoint ?: return
        val (x, y) = normalizeToPx(xNorm, yNorm) ?: return
        val path = Path().apply {
            moveTo(lastPoint.first, lastPoint.second)
            lineTo(x, y)
        }
        val stroke = previous.continueStroke(path, 0, durationMs.coerceAtLeast(1), true)
        dispatchGesture(stroke)
        ongoingDrag = stroke
        lastDragPoint = x to y
    }

    fun dragEnd(xNorm: Float, yNorm: Float, durationMs: Long = DEFAULT_DRAG_SEGMENT_MS) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val previous = ongoingDrag
        val lastPoint = lastDragPoint
        val (x, y) = normalizeToPx(xNorm, yNorm) ?: return
        if (previous == null || lastPoint == null) {
            swipe(xNorm, yNorm, xNorm, yNorm, durationMs)
            return
        }
        val path = Path().apply {
            moveTo(lastPoint.first, lastPoint.second)
            lineTo(x, y)
        }
        val stroke = previous.continueStroke(path, 0, durationMs.coerceAtLeast(1), false)
        dispatchGesture(stroke)
        ongoingDrag = null
        lastDragPoint = null
    }

    fun swipe(
        xStartNorm: Float,
        yStartNorm: Float,
        xEndNorm: Float,
        yEndNorm: Float,
        durationMs: Long = 250L
    ) {
        val start = normalizeToPx(xStartNorm, yStartNorm) ?: return
        val end = normalizeToPx(xEndNorm, yEndNorm) ?: return
        val path = Path().apply {
            moveTo(start.first, start.second)
            lineTo(end.first, end.second)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        dispatchGesture(stroke)
    }

    fun back() {
        svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun home() {
        svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun recents() {
        svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    fun inputText(text: String, append: Boolean = true) {
        val service = svc ?: return
        if (append && text.isBlank()) return
        val root = service.rootInActiveWindow ?: return
        val focused = findFocusedEditable(root)
        if (focused == null) {
            Log.w(TAG, "Nenhum campo editável encontrado para inserir texto")
            return
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val current = focused.text?.toString() ?: ""
        val finalText = if (append) {
            current + text
        } else {
            text
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            Log.w(TAG, "Falha ao inserir texto")
        }
    }

    fun applyKey(key: String, shift: Boolean = false) {
        val service = svc ?: return
        val root = service.rootInActiveWindow ?: return
        val focused = findFocusedEditable(root)
        if (focused == null) {
            Log.w(TAG, "Nenhum campo editável encontrado para tecla $key")
            return
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val currentText = focused.text?.toString() ?: ""
        val selection = normalizeSelection(focused, currentText.length)
        val normalizedKey = key.trim().lowercase()

        when (normalizedKey) {
            "arrowleft", "left" -> {
                val newPos = if (selection.first != selection.second) {
                    selection.first
                } else {
                    (selection.first - 1).coerceAtLeast(0)
                }
                setSelection(focused, newPos, newPos)
            }
            "arrowright", "right" -> {
                val newPos = if (selection.first != selection.second) {
                    selection.second
                } else {
                    (selection.second + 1).coerceAtMost(currentText.length)
                }
                setSelection(focused, newPos, newPos)
            }
            "arrowup", "up" -> {
                setSelection(focused, 0, 0)
            }
            "arrowdown", "down" -> {
                setSelection(focused, currentText.length, currentText.length)
            }
            "backspace", "del", "deletebackward" -> {
                val (newText, cursor) = applyDeletion(currentText, selection, deleteForward = false)
                setTextAndSelection(focused, newText, cursor, cursor)
            }
            "delete", "forwarddelete" -> {
                val (newText, cursor) = applyDeletion(currentText, selection, deleteForward = true)
                setTextAndSelection(focused, newText, cursor, cursor)
            }
            "enter", "return", "numenter" -> {
                if (!shift && trySendAction()) {
                    return
                }
                val (newText, cursor) = applyInsertion(currentText, selection, "\n")
                setTextAndSelection(focused, newText, cursor, cursor)
            }
            "space", "spacebar", " " -> {
                val (newText, cursor) = applyInsertion(currentText, selection, " ")
                setTextAndSelection(focused, newText, cursor, cursor)
            }
            "tab" -> {
                val (newText, cursor) = applyInsertion(currentText, selection, "\t")
                setTextAndSelection(focused, newText, cursor, cursor)
            }
        }
    }

    fun trySendAction(): Boolean {
        val service = svc ?: return false
        val root = service.rootInActiveWindow ?: return false
        val sendButton = findSendButton(root) ?: return false
        val ok = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok) {
            Log.w(TAG, "Falha ao acionar botão de envio")
        }
        return ok
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            val found = findFirstEditable(root.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused ?: findFirstEditable(root)
    }

    private fun normalizeSelection(node: AccessibilityNodeInfo, textLength: Int): Pair<Int, Int> {
        val start = node.textSelectionStart.takeIf { it >= 0 } ?: textLength
        val end = node.textSelectionEnd.takeIf { it >= 0 } ?: textLength
        return if (start <= end) {
            start to end
        } else {
            end to start
        }
    }

    private fun applyDeletion(
        currentText: String,
        selection: Pair<Int, Int>,
        deleteForward: Boolean
    ): Pair<String, Int> {
        val (start, end) = selection
        if (start != end) {
            val newText = currentText.removeRange(start, end)
            return newText to start
        }
        if (deleteForward) {
            if (start >= currentText.length) {
                return currentText to start
            }
            val newText = currentText.removeRange(start, start + 1)
            return newText to start
        }
        if (start <= 0) {
            return currentText to start
        }
        val newText = currentText.removeRange(start - 1, start)
        return newText to (start - 1)
    }

    private fun applyInsertion(
        currentText: String,
        selection: Pair<Int, Int>,
        insertText: String
    ): Pair<String, Int> {
        val (start, end) = selection
        val newText = StringBuilder(currentText.length + insertText.length).apply {
            append(currentText.take(start))
            append(insertText)
            append(currentText.substring(end))
        }.toString()
        val cursor = start + insertText.length
        return newText to cursor
    }

    private fun setTextAndSelection(
        node: AccessibilityNodeInfo,
        text: String,
        selectionStart: Int,
        selectionEnd: Int
    ) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            Log.w(TAG, "Falha ao inserir texto")
            return
        }
        setSelection(node, selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0))
    }

    private fun setSelection(node: AccessibilityNodeInfo, selectionStart: Int, selectionEnd: Int) {
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selectionStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectionEnd)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
    }

    private fun findSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val className = root.className?.toString().orEmpty()
        val label = listOfNotNull(
            root.text?.toString(),
            root.contentDescription?.toString(),
            root.viewIdResourceName
        ).joinToString(" ").lowercase()
        val isButton = className.contains("Button", ignoreCase = true)
        val looksLikeSend = label.contains("send") || label.contains("enviar")
        if (isButton && looksLikeSend) {
            return root
        }
        for (i in 0 until root.childCount) {
            val found = findSendButton(root.getChild(i))
            if (found != null) return found
        }
        return null
    }

    fun setCaptureFrameSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            captureFrameWidth = width
            captureFrameHeight = height
        }
    }

    fun clearCaptureFrameSize() {
        captureFrameWidth = 0
        captureFrameHeight = 0
    }

    private fun normalizeToPx(xNorm: Float, yNorm: Float): Pair<Float, Float>? {
        val service = svc ?: return null
        val area = resolveTappableArea(service)
        val width = (area.right - area.left).toFloat().coerceAtLeast(1f)
        val height = (area.bottom - area.top).toFloat().coerceAtLeast(1f)
        val x = area.left + xNorm.coerceIn(0f, 1f) * width
        val y = area.top + yNorm.coerceIn(0f, 1f) * height
        return x to y
    }

    private fun resolveTappableArea(service: AccessibilityService): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = service.getSystemService(WindowManager::class.java)
            if (windowManager != null) {
                val metrics = windowManager.currentWindowMetrics
                val bounds = metrics.bounds
                return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }
        val dm = service.resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun dispatchGesture(stroke: GestureDescription.StrokeDescription) {
        val service = svc ?: return
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }
}
