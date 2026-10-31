package com.ozonjob.catcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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
    private val clickCooldownMs = 600L

    // Таймер нахождения в календаре (если смены нет, выходим назад)
    private var calendarEnterTime = 0L
    private val calendarMaxWaitMs = 1200L

    // Маркеры повышенного тарифа
    private val hotRateMarkers = listOf("⚡", "🔥", "тариф +", "тариф+")

    // Регулярное выражение цены (≈10 910 ₽, ≈8 110 ₽)
    private val pricePattern = Pattern.compile("(?:≈|~)?\\s*(\\d[\\d\\s]{2,6})\\s*₽", Pattern.CASE_INSENSITIVE)

    // Список стандартных операций Ozon Job
    private val defaultOperationsList = listOf(
        "Подбор",
        "Размещение",
        "Приемка",
        "Упаковка",
        "Сортировка крупного товара",
        "Возвраты",
        "Сортировка мелкого товара",
        "Консолидация",
        "Подбор возвратов"
    )

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isCatchingActive && filterConfig.autoSwipeEnabled) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val fullText = collectAllText(rootNode).lowercase()
                    // Свайпаем только на экране операций склада
                    if (isOperationsListScreen(fullText)) {
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
        Log.i(TAG, "Служба Ozon Job Catcher успешно запущена и подключена.")
        handler.postDelayed(refreshRunnable, 2000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isCatchingActive || event == null) return

        val rootNode = rootInActiveWindow ?: return

        try {
            val fullScreenText = collectAllText(rootNode).lowercase()

            // 1. ШТОРКА "Выберите время" (выбор 12ч/6ч -> ГАЛОЧКА слота -> Записаться)
            if (isBottomSheet(fullScreenText)) {
                handleTimeSlotBottomSheet(rootNode, fullScreenText)
                return
            }

            // 2. КАЛЕНДАРЬ («Можете выбрать несколько дней сразу»)
            if (isCalendarScreen(fullScreenText)) {
                handleCalendarScreen(rootNode, fullScreenText)
                return
            }

            // 3. ЭКРАН ОПЕРАЦИЙ СКЛАДА (список карточек "Подбор", "Размещение" и т.д.)
            if (isOperationsListScreen(fullScreenText)) {
                calendarEnterTime = 0L // сбрасываем таймер календаря
                handleOperationsListScreen(rootNode)
                return
            }

            // 4. СПИСОК СКЛАДОВ: если на экране выбор склада ("Воронеж 2" в списке)
            if (isWarehouseSelectionScreen(fullScreenText)) {
                handleWarehouseSelectionScreen(rootNode)
                return
            }

            // 5. НАВИГАЦИЯ: Если мы не на складе (Главная, Записи и т.д.), перейти во вкладку "Склады"
            if (fullScreenText.contains("склады")) {
                navigateToWarehousesTab(rootNode)
            }

        } finally {
            rootNode.recycle()
        }
    }

    // =========================================================================
    // ОПРЕДЕЛЕНИЕ ТЕКУЩЕГО ЭКРАНА
    // =========================================================================

    private fun isBottomSheet(text: String): Boolean {
        return text.contains("выберите время") ||
                text.contains("длительность записи") ||
                text.contains("подтвердим запись моментально")
    }

    private fun isCalendarScreen(text: String): Boolean {
        return (text.contains("можете выбрать несколько дней") ||
                (text.contains("выбрать время") && !text.contains("длительность записи"))) &&
                !isBottomSheet(text)
    }

    private fun isOperationsListScreen(text: String): Boolean {
        if (isBottomSheet(text) || isCalendarScreen(text)) return false
        val hasOps = defaultOperationsList.any { text.contains(it.lowercase()) }
        val targetWh = filterConfig.targetWarehouse.lowercase().trim()
        val hasWarehouse = targetWh.isBlank() || text.contains(targetWh)
        return (text.contains("доступные") || text.contains("о записи и очереди") || hasOps) && hasWarehouse
    }

    private fun isWarehouseSelectionScreen(text: String): Boolean {
        if (isBottomSheet(text) || isCalendarScreen(text) || isOperationsListScreen(text)) return false
        val targetWh = filterConfig.targetWarehouse.lowercase().trim()
        return text.contains(targetWh) || text.contains("выберите склад") || text.contains("список складов")
    }

    // =========================================================================
    // ШАГ 0: Переход во вкладку "Склады" в нижнем меню
    // =========================================================================
    private fun navigateToWarehousesTab(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 1000L) return

        val warehouseNodes = rootNode.findAccessibilityNodeInfosByText("Склады")
        for (node in warehouseNodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "ШАГ 0: Переходим во вкладку 'Склады' в нижнем меню...")
                if (clickOrTap(node)) {
                    lastClickTime = now
                }
                node.recycle()
                return
            }
            node.recycle()
        }
    }

    // =========================================================================
    // ШАГ 0.5: Выбор нужного склада (например, "Воронеж 2") из списка складов
    // =========================================================================
    private fun handleWarehouseSelectionScreen(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 1000L) return

        val target = filterConfig.targetWarehouse.ifBlank { "Воронеж 2" }
        val nodes = rootNode.findAccessibilityNodeInfosByText(target)
        for (node in nodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "ШАГ 0.5: Нажимаем на склад '$target' в списке...")
                if (clickOrTap(node)) {
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

        val targetCard = findMatchingOperationCard(rootNode)
        if (targetCard != null) {
            Log.i(TAG, "ШАГ 1: Подходящий процесс найден. Заходим внутрь...")
            val clicked = clickOrTap(targetCard)
            if (clicked) {
                lastClickTime = now
                calendarEnterTime = now // фиксируем время входа в календарь процесса
            }
            targetCard.recycle()
        }
    }

    /**
     * Поиск конкретной карточки операции (Подбор, Размещение и т.д.), подходящей под фильтры
     */
    private fun findMatchingOperationCard(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val allowedOps = if (filterConfig.operations.isNotEmpty()) {
            filterConfig.operations.toList()
        } else {
            defaultOperationsList
        }

        for (opName in allowedOps) {
            val matchingNodes = rootNode.findAccessibilityNodeInfosByText(opName)
            for (node in matchingNodes) {
                if (!node.isVisibleToUser) {
                    node.recycle()
                    continue
                }

                // Ищем родительский контейнер карточки (поднимаемся до уровня карточки с ценой)
                val cardContainer = findEnclosingCard(node)
                val cardText = collectAllText(cardContainer).lowercase()

                if (matchesOperationFilters(cardText, filterConfig)) {
                    Log.i(TAG, "Найдена карточка операции '$opName': $cardText")
                    return cardContainer
                }
                if (cardContainer != node) {
                    cardContainer.recycle()
                }
                node.recycle()
            }
        }
        return null
    }

    /**
     * Поиск контейнера карточки вверх по иерархии
     */
    private fun findEnclosingCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo = node
        var count = 0
        while (count < 4) {
            val parent = current.parent ?: break
            val parentText = collectAllText(parent).lowercase()
            if (parentText.contains("₽") || parentText.contains("тариф") || parentText.contains("даты:")) {
                return parent
            }
            current = parent
            count++
        }
        return node
    }

    private fun matchesOperationFilters(cardText: String, config: ShiftFilterConfig): Boolean {
        val lower = cardText.lowercase()

        // Проверка повышенного тарифа ⚡ / 🔥
        if (config.onlyHotTariff) {
            val isHot = hotRateMarkers.any { lower.contains(it) }
            if (!isHot) return false
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

        // 1. Если синяя кнопка "Выбрать время" уже видна — моментально нажимаем её!
        val selectTimeNodes = rootNode.findAccessibilityNodeInfosByText("Выбрать время")
        for (node in selectTimeNodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "ШАГ 2: Дата выбрана, нажимаем синюю кнопку 'Выбрать время'!")
                if (clickOrTap(node)) {
                    lastClickTime = now
                }
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
            TargetDateChoice.ANY -> 0 // Любая доступная
        }

        var dayClicked = false

        if (targetDayNumber > 0) {
            // Ищем конкретный день (сегодня / завтра / число)
            dayClicked = findAndClickCalendarDay(rootNode, targetDayNumber)
        } else {
            // Любой день с доступной сменой (ищем горячие дни ⚡ / 🔥 или дни с точками)
            dayClicked = findAndClickAnyAvailableDay(rootNode)
        }

        if (dayClicked) {
            lastClickTime = now
            return
        }

        // 3. ЕСЛИ СМЕНЫ НЕТ: если прошло больше calendarMaxWaitMs и смену выбрать не удалось — ВЫХОДИМ НАЗАД
        if (now - calendarEnterTime > calendarMaxWaitMs) {
            Log.i(TAG, "В этом процессе нет подходящей смены. Нажимаем НАЗАД и заходим заново...")
            goBack(rootNode)
            calendarEnterTime = 0L
            lastClickTime = now
        }
    }

    private fun findAndClickCalendarDay(rootNode: AccessibilityNodeInfo, dayNumber: Int): Boolean {
        val dayStr = dayNumber.toString()
        val nodes = rootNode.findAccessibilityNodeInfosByText(dayStr)
        for (node in nodes) {
            if (!node.isVisibleToUser) {
                node.recycle()
                continue
            }
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            // Проверяем, что это именно число дня, а не часть другого текста
            val isMatch = text == dayStr || desc.contains(dayStr) ||
                    text.startsWith("$dayStr ") || text.startsWith("$dayStr\n")
            if (isMatch) {
                Log.i(TAG, "ШАГ 2: Нажимаем число $dayStr в календаре...")
                val success = clickOrTap(node)
                node.recycle()
                if (success) return true
            } else {
                node.recycle()
            }
        }
        return false
    }

    private fun findAndClickAnyAvailableDay(rootNode: AccessibilityNodeInfo): Boolean {
        // Ищем узлы с горячими маркерами (⚡, 🔥)
        for (marker in hotRateMarkers) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(marker)
            for (node in nodes) {
                if (node.isVisibleToUser) {
                    Log.i(TAG, "ШАГ 2: Нажимаем горячий день $marker в календаре...")
                    val success = clickOrTap(node)
                    node.recycle()
                    if (success) return true
                }
                node.recycle()
            }
        }
        // Если маркеров нет, пробуем сегодняшний день
        val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        return findAndClickCalendarDay(rootNode, today)
    }

    /**
     * Возврат на предыдущую страницу (кнопка стрелки назад или системный жест Back)
     */
    private fun goBack(rootNode: AccessibilityNodeInfo) {
        // 1. Ищем кнопку Назад по описанию
        val backDescriptions = listOf("назад", "back", "navigate up", "перейти вверх")
        for (desc in backDescriptions) {
            val backNodes = rootNode.findAccessibilityNodeInfosByText(desc)
            for (node in backNodes) {
                if (node.isVisibleToUser) {
                    clickOrTap(node)
                    node.recycle()
                    return
                }
                node.recycle()
            }
        }
        // 2. Нажимаем верхний левый угол (где всегда стрелка назад в заголовке)
        tapAt(80f, 130f)
        // 3. Вызываем системную команду Android Назад
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // =========================================================================
    // ШАГ 3: Шторка "Выберите время" (Длительность -> ГАЛОЧКА -> Записаться)
    // =========================================================================
    private fun handleTimeSlotBottomSheet(rootNode: AccessibilityNodeInfo, fullText: String) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 350L) return // быстрая реакция на шторке

        // 1. Выбор длительности (12 часов / 6 часов)
        when (filterConfig.duration) {
            ShiftDuration.FULL -> clickByExactText(rootNode, "12 часов")
            ShiftDuration.SHORT -> clickByExactText(rootNode, "6 часов")
            ShiftDuration.ANY -> {}
        }

        // 2. ГАЛОЧКА ПОДТВЕРЖДЕНИЯ ЗАПИСИ (Чекбокс в карточке слота)
        checkSlotConfirmationCheckbox(rootNode)

        // 3. ФИНАЛЬНАЯ КНОПКА: "Записаться"
        val bookNodes = rootNode.findAccessibilityNodeInfosByText("Записаться")
        for (node in bookNodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "ФИНАЛ: Нажимаем кнопку 'Записаться'! Смена успешно забронирована!")
                val success = clickOrTap(node)
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
        // А) Проверяем наличие стандартных checkable узлов
        val checkableNodes = findCheckableNodes(rootNode)
        for (cb in checkableNodes) {
            if (cb.isVisibleToUser && !cb.isChecked) {
                Log.i(TAG, "Нажимаем на найденный чекбокс подтверждения записи!")
                clickOrTap(cb)
                cb.recycle()
                return
            }
            cb.recycle()
        }

        // Б) Нажимаем на правый край карточки слота времени (где расположен чекбокс)
        // Ищем узлы со временем слота ("8:00", "20:00", "подтвердим")
        val slotTimeMarkers = listOf("8:00", "20:00", "подтвердим запись", "подтвердим")
        for (marker in slotTimeMarkers) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(marker)
            for (node in nodes) {
                if (node.isVisibleToUser) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    // Чекбокс находится справа в той же строке
                    val displayMetrics = resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val tapX = (screenWidth * 0.90f) // правая часть экрана (90% ширины)
                    val tapY = rect.centerY().toFloat()

                    Log.i(TAG, "Тапаем по чекбоксу в слоте времени: ($tapX, $tapY)...")
                    tapAt(tapX, tapY)
                    node.recycle()
                    return
                }
                node.recycle()
            }
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
                val clicked = clickOrTap(node)
                node.recycle()
                return clicked
            }
            node.recycle()
        }
        return false
    }

    // =========================================================================
    // НАДЕЖНЫЙ КЛИК / ФИЗИЧЕСКИЙ ТАП (ДЛЯ JETPACK COMPOSE / REACT NATIVE)
    // =========================================================================

    private fun tapAt(x: Float, y: Float, onComplete: (() -> Unit)? = null): Boolean {
        if (x <= 0f || y <= 0f) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {}
        }, null)
    }

    private fun clickOrTap(node: AccessibilityNodeInfo): Boolean {
        // 1. Пробуем стандартное действие клика Accessibility
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) return true
            }
            current = current.parent
        }

        // 2. Надежный физический тап по координатам центра элемента
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0 && rect.centerX() > 0 && rect.centerY() > 0) {
            return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
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
