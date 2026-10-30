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
    private val clickCooldownMs = 800L

    // Время захода на экран календаря (для возврата назад, если смены нет)
    private var calendarEnterTime = 0L
    private val calendarMaxWaitMs = 1500L // 1.5 секунды на поиск смены в календаре перед выходом назад

    // Маркеры повышенного тарифа (молния и огонь)
    private val hotRateMarkers = listOf("⚡", "🔥", "тариф +", "тариф+")

    // Регулярное выражение цены (≈10 910 ₽, ≈8 110 ₽)
    private val pricePattern = Pattern.compile("(?:≈|~)?\\s*(\\d[\\d\\s]{2,6})\\s*₽", Pattern.CASE_INSENSITIVE)

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isCatchingActive && filterConfig.autoSwipeEnabled) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val fullText = collectAllText(rootNode).lowercase()
                    // Свайпаем только на главном экране операций склада
                    if (fullText.contains("доступные") || (fullText.contains("склады") && !fullText.contains("выберите время") && !fullText.contains("можете выбрать"))) {
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

            // 1. ШТОРКА "Выберите время" (выбор 12ч/6ч -> ГАЛОЧКА подтверждения -> Записаться)
            if (fullScreenText.contains("выберите время") || fullScreenText.contains("длительность записи")) {
                handleTimeSlotBottomSheet(rootNode, fullScreenText)
                return
            }

            // 2. КАЛЕНДАРЬ («Можете выбрать несколько дней сразу»)
            if (fullScreenText.contains("можете выбрать несколько дней") || fullScreenText.contains("выбрать время")) {
                handleCalendarScreen(rootNode, fullScreenText)
                return
            }

            // 3. СПИСОК ОПЕРАЦИЙ СКЛАДА (Размещение, Подбор, Приемка и т.д.)
            if (fullScreenText.contains("доступные") || fullScreenText.contains("тариф")) {
                calendarEnterTime = 0L // сбрасываем таймер календаря
                handleOperationsListScreen(rootNode)
                return
            }

            // 4. НАВИГАЦИЯ: Если мы не на складе, перейти во вкладку «Склады» внизу
            if (fullScreenText.contains("главная") && fullScreenText.contains("склады")) {
                navigateToWarehousesTab(rootNode)
            }

        } finally {
            rootNode.recycle()
        }
    }

    // =========================================================================
    // НАВИГАЦИЯ: Переход во вкладку «Склады» в нижнем меню
    // =========================================================================
    private fun navigateToWarehousesTab(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 1500L) return

        val warehouseTabNodes = rootNode.findAccessibilityNodeInfosByText("Склады")
        for (node in warehouseTabNodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "Переходим в раздел 'Склады' в нижнем меню...")
                if (clickNodeOrParent(node)) {
                    lastClickTime = now
                }
                node.recycle()
                return
            }
            node.recycle()
        }
    }

    // =========================================================================
    // ШАГ 1: Список процессов на складе (Подбор, Размещение и т.д.)
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
            Log.i(TAG, "ШАГ 1: Нашли подходящий процесс. Заходим внутрь...")
            val clicked = clickNodeOrParent(targetCard)
            if (clicked) {
                lastClickTime = now
                calendarEnterTime = now // фиксируем время входа в процесс
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

        // Проверка дат в карточке (Сегодня, Завтра)
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
    // ШАГ 2: Календарь (Поиск даты -> «Выбрать время» ИЛИ выход назад, если смены нет)
    // =========================================================================
    private fun handleCalendarScreen(rootNode: AccessibilityNodeInfo, fullText: String) {
        val now = System.currentTimeMillis()
        if (calendarEnterTime == 0L) {
            calendarEnterTime = now
        }

        // 1. Если синяя кнопка "Выбрать время" уже активна внизу — моментально жмём её!
        val selectTimeNodes = rootNode.findAccessibilityNodeInfosByText("Выбрать время")
        for (node in selectTimeNodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "ШАГ 2: Дата уже выбрана, нажимаем 'Выбрать время'")
                clickNodeOrParent(node)
                lastClickTime = now
                node.recycle()
                return
            }
            node.recycle()
        }

        // 2. Ищем целевое число в календаре
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
        var dayClicked = false
        for (node in dayNodes) {
            if (node.text?.toString()?.trim() == dayStr && node.isVisibleToUser) {
                Log.i(TAG, "ШАГ 2: Нажимаем число $dayStr в календаре...")
                if (clickNodeOrParent(node)) {
                    lastClickTime = now
                    dayClicked = true
                }
                node.recycle()
                break
            }
            node.recycle()
        }

        // 3. ЕСЛИ СМЕНЫ НЕТ: если прошло больше 1.5 сек и смену не удалось выбрать, ВЫХОДИМ НАЗАД
        if (!dayClicked && (now - calendarEnterTime > calendarMaxWaitMs)) {
            Log.i(TAG, "Подходящей смены в этом процессе нет. Нажимаем НАЗАД и заходим заново...")
            goBack(rootNode)
            calendarEnterTime = 0L
            lastClickTime = now
        }
    }

    /**
     * Возврат на предыдущую страницу (кнопка стрелки назад или системный жест Back)
     */
    private fun goBack(rootNode: AccessibilityNodeInfo) {
        // Пробуем нажать на иконку стрелки назад в шапке
        val backNodes = rootNode.findAccessibilityNodeInfosByText("<")
        for (node in backNodes) {
            if (node.isVisibleToUser && clickNodeOrParent(node)) {
                node.recycle()
                return
            }
            node.recycle()
        }
        // Если стрелки в дереве нет, вызываем системную команду Android Назад
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // =========================================================================
    // ШАГ 3: Шторка "Выберите время" (Длительность -> ГАЛОЧКА -> Записаться)
    // =========================================================================
    private fun handleTimeSlotBottomSheet(rootNode: AccessibilityNodeInfo, fullText: String) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 400L) return // быстрая реакция на шторке

        // 1. Выбор длительности (12 часов / 6 часов)
        when (filterConfig.duration) {
            ShiftDuration.FULL -> clickByExactText(rootNode, "12 часов")
            ShiftDuration.SHORT -> clickByExactText(rootNode, "6 часов")
            ShiftDuration.ANY -> {}
        }

        // 2. ГАЛОЧКА ПОДТВЕРЖДЕНИЯ ЗАПИСИ (Чекбокс в карточке времени 8:00 - 20:00)
        checkSlotConfirmationCheckbox(rootNode)

        // 3. ФИНАЛЬНАЯ КНОПКА: "Записаться"
        val bookNodes = rootNode.findAccessibilityNodeInfosByText("Записаться")
        for (node in bookNodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "ФИНАЛ: Нажимаем кнопку 'Записаться'! Смена забронирована.")
                val success = clickNodeOrParent(node)
                if (success) {
                    lastClickTime = now + 1500L
                }
                node.recycle()
                return
            }
            node.recycle()
        }
    }

    /**
     * Поиск и нажатие на чекбокс (галочку) подтверждения выбранного слота времени
     */
    private fun checkSlotConfirmationCheckbox(rootNode: AccessibilityNodeInfo) {
        // 1. Сначала ищем чекбокс среди узлов
        val checkableNodes = findCheckableNodes(rootNode)
        for (cb in checkableNodes) {
            if (cb.isVisibleToUser && !cb.isChecked) {
                Log.i(TAG, "Нажимаем на чекбокс (галочку) подтверждения записи!")
                clickNodeOrParent(cb)
                cb.recycle()
                return
            }
            cb.recycle()
        }

        // 2. Если чекбокс напрямую не размечен как CheckBox, кликаем по самой строке со временем (8:00 - 20:00)
        val targetTime = when (filterConfig.timeOfDay) {
            ShiftTimeOfDay.DAY -> "8:00"
            ShiftTimeOfDay.NIGHT -> "20:00"
            ShiftTimeOfDay.ANY -> "8:00"
        }
        val timeNodes = rootNode.findAccessibilityNodeInfosByText(targetTime)
        for (node in timeNodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "Кликаем по слоту времени '$targetTime' для активации галочки...")
                clickNodeOrParent(node)
                node.recycle()
                return
            }
            node.recycle()
        }
    }

    private fun findCheckableNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        if (node.isCheckable || node.className?.contains("CheckBox") == true || node.className?.contains("CompoundButton") == true) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            list.addAll(findCheckableNodes(child))
        }
        return list
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
