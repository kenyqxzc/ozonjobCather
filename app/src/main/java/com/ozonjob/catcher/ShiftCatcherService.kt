package com.ozonjob.catcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Calendar
import java.util.regex.Pattern

class ShiftCatcherService : AccessibilityService() {

    companion object {
        const val TAG = "OzonShiftCatcher"
        var isCatchingActive = false
        var filterConfig = ShiftFilterConfig()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickTime = 0L
    private val clickCooldownMs = 1200L

    // Маркеры повышенного тарифа (молния и огонь как на скриншотах)
    private val hotRateMarkers = listOf("⚡", "🔥", "тариф +", "тариф+")

    // Регулярное выражение для цены: ≈10 910 ₽, ≈8 110 ₽, ≈11 317 ₽
    private val pricePattern = Pattern.compile("(?:≈|~)?\\s*(\\d[\\d\\s]{2,6})\\s*₽", Pattern.CASE_INSENSITIVE)

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isCatchingActive && filterConfig.autoSwipeEnabled) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val fullText = collectAllText(rootNode).lowercase()
                    // Свайпаем только если мы на первом экране (список операций)
                    if (fullText.contains("доступные") || (fullText.contains("склады") && !fullText.contains("выберите время"))) {
                        performPullToRefresh()
                    }
                    rootNode.recycle()
                }
                handler.postDelayed(this, filterConfig.autoRefreshIntervalMs)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Служба Ozon Job Catcher успешно подключена к системе.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isCatchingActive || event == null) return

        val rootNode = rootInActiveWindow ?: return

        try {
            val fullScreenText = collectAllText(rootNode).lowercase()

            // ШАГ 3: Шторка "Выберите время" (выбор 12 ч / 6 ч, чекбокс слота и кнопка "Записаться")
            if (fullScreenText.contains("выберите время") || fullScreenText.contains("длительность записи")) {
                handleTimeSlotBottomSheet(rootNode, fullScreenText)
                return
            }

            // ШАГ 2: Экран календаря («Можете выбрать несколько дней сразу»)
            if (fullScreenText.contains("можете выбрать несколько дней") || fullScreenText.contains("выбрать время")) {
                handleCalendarScreen(rootNode, fullScreenText)
                return
            }

            // ШАГ 1: Главный экран склада (список: Размещение, Подбор, Приемка...)
            if (fullScreenText.contains("доступные") || fullScreenText.contains("тариф")) {
                handleOperationsListScreen(rootNode)
            }

        } finally {
            rootNode.recycle()
        }
    }

    // =========================================================================
    // ШАГ 1: Список операций склада
    // =========================================================================
    private fun handleOperationsListScreen(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < clickCooldownMs) return

        // Проверяем соответствие склада (например, "Воронеж 2")
        if (filterConfig.targetWarehouse.isNotBlank()) {
            val screenText = collectAllText(rootNode).lowercase()
            if (!screenText.contains(filterConfig.targetWarehouse.lowercase().trim())) {
                return
            }
        }

        val targetCard = findMatchingOperationCard(rootNode)
        if (targetCard != null) {
            Log.i(TAG, "ШАГ 1: Найдена операция под фильтры. Кликаем по карточке...")
            val clicked = clickNodeOrParent(targetCard)
            if (clicked) {
                lastClickTime = now
            }
            targetCard.recycle()
        }
    }

    private fun findMatchingOperationCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cardText = collectAllText(node)
        val lower = cardText.lowercase()

        if (lower.contains("₽") && (lower.contains("тариф") || lower.contains("даты:"))) {
            if (matchesOperationFilters(cardText, filterConfig)) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findMatchingOperationCard(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun matchesOperationFilters(cardText: String, config: ShiftFilterConfig): Boolean {
        val lower = cardText.lowercase()

        // Проверка повышенного тарифа ⚡ / 🔥
        if (config.onlyHotTariff) {
            val isHot = hotRateMarkers.any { lower.contains(it) }
            if (!isHot) return false
        }

        // Проверка выбранных операций (Подбор, Размещение, Приемка и т.д.)
        if (config.operations.isNotEmpty()) {
            val matchesOp = config.operations.any { opName ->
                lower.contains(opName.lowercase().trim())
            }
            if (!matchesOp) return false
        }

        // Проверка дат в превью карточки (Сегодня, Завтра)
        when (config.dateChoice) {
            TargetDateChoice.TODAY -> if (!lower.contains("сегодня")) return false
            TargetDateChoice.TOMORROW -> if (!lower.contains("завтра")) return false
            TargetDateChoice.CUSTOM -> {
                if (config.customDayNumber > 0) {
                    val formatted = String.format("%02d", config.customDayNumber)
                    if (!lower.contains(formatted) && !lower.contains("${config.customDayNumber}")) return false
                }
            }
            TargetDateChoice.ANY -> {}
        }

        // Проверка минимальной ставки
        val matcher = pricePattern.matcher(cardText)
        var price = 0
        if (matcher.find()) {
            val raw = matcher.group(1)?.replace(" ", "")?.replace("\u00A0", "") ?: "0"
            price = raw.toIntOrNull() ?: 0
        }

        if (config.minPayment > 0 && price > 0 && price < config.minPayment) {
            return false
        }

        return true
    }

    // =========================================================================
    // ШАГ 2: Календарь («Можете выбрать несколько дней сразу»)
    // =========================================================================
    private fun handleCalendarScreen(rootNode: AccessibilityNodeInfo, fullText: String) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < clickCooldownMs) return

        // 1. Если синяя кнопка "Выбрать время" уже активна внизу — сразу жмём её!
        val selectTimeNodes = rootNode.findAccessibilityNodeInfosByText("Выбрать время")
        for (node in selectTimeNodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "ШАГ 2: Нажата синяя кнопка 'Выбрать время'")
                clickNodeOrParent(node)
                lastClickTime = now
                node.recycle()
                return
            }
            node.recycle()
        }

        // 2. Если дата ещё не выбрана, находим нужное число в календаре
        val calendar = Calendar.getInstance()
        val targetDayNumber: Int = when (filterConfig.dateChoice) {
            TargetDateChoice.TODAY -> calendar.get(Calendar.DAY_OF_MONTH)
            TargetDateChoice.TOMORROW -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                calendar.get(Calendar.DAY_OF_MONTH)
            }
            TargetDateChoice.CUSTOM -> {
                if (filterConfig.customDayNumber > 0) filterConfig.customDayNumber else calendar.get(Calendar.DAY_OF_MONTH)
            }
            TargetDateChoice.ANY -> calendar.get(Calendar.DAY_OF_MONTH)
        }

        val dayStr = targetDayNumber.toString()
        val dayNodes = rootNode.findAccessibilityNodeInfosByText(dayStr)
        for (node in dayNodes) {
            if (node.text?.toString()?.trim() == dayStr && node.isVisibleToUser) {
                Log.i(TAG, "ШАГ 2: Выбираем число $dayStr в календаре...")
                clickNodeOrParent(node)
                lastClickTime = now
                node.recycle()
                return
            }
            node.recycle()
        }
    }

    // =========================================================================
    // ШАГ 3: Шторка "Выберите время"
    // =========================================================================
    private fun handleTimeSlotBottomSheet(rootNode: AccessibilityNodeInfo, fullText: String) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 600L) return // Меньшая пауза на шторке для скорости

        // 1. Выбор длительности (12 часов / 6 часов)
        when (filterConfig.duration) {
            ShiftDuration.FULL -> clickByExactText(rootNode, "12 часов")
            ShiftDuration.SHORT -> clickByExactText(rootNode, "6 часов")
            ShiftDuration.ANY -> {}
        }

        // 2. Выбор времени суток (День: 8:00 – 20:00 / Ночь: 20:00 – 8:00)
        // Карточка слота содержит время и чекбокс, который надо нажать
        val targetTimeKeyword = when (filterConfig.timeOfDay) {
            ShiftTimeOfDay.DAY -> "8:00"
            ShiftTimeOfDay.NIGHT -> "20:00"
            ShiftTimeOfDay.ANY -> "8:00" // по умолчанию выбираем первый доступный слот
        }

        // Ищем карточку с нужным временем и кликаем на неё (или на чекбокс внутри)
        val timeNodes = rootNode.findAccessibilityNodeInfosByText(targetTimeKeyword)
        for (node in timeNodes) {
            if (node.isVisibleToUser) {
                clickNodeOrParent(node)
                node.recycle()
                break
            }
            node.recycle()
        }

        // 3. Финальный клик по кнопке "Записаться"
        val bookNodes = rootNode.findAccessibilityNodeInfosByText("Записаться")
        for (node in bookNodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "ФИНАЛ: Нажимаем кнопку 'Записаться'! Смена забронирована.")
                val success = clickNodeOrParent(node)
                if (success) {
                    lastClickTime = now + 1000L // увеличиваем паузу после взятия
                }
                node.recycle()
                return
            }
            node.recycle()
        }
    }

    private fun clickByExactText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isVisibleToUser && node.text?.toString()?.trim().equals(text, ignoreCase = true)) {
                val clicked = clickNodeOrParent(node)
                node.recycle()
                return clicked
            }
            node.recycle()
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(collectAllText(child)).append(" ")
            child.recycle()
        }
        return sb.toString()
    }

    private fun performPullToRefresh() {
        val displayMetrics = resources.displayMetrics
        val startX = (displayMetrics.widthPixels / 2).toFloat()
        val startY = (displayMetrics.heightPixels * 0.35).toFloat()
        val endY = (displayMetrics.heightPixels * 0.75).toFloat()

        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 250))
            .build()

        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        isCatchingActive = false
        handler.removeCallbacks(refreshRunnable)
    }
}
