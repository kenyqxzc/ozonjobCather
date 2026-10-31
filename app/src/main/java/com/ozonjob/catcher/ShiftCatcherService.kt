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
    private val defaultCooldownMs = 450L

    // Таймер нахождения в календаре (если смены нет, выходим назад к списку процессов)
    private var calendarEnterTime = 0L
    private val calendarMaxWaitMs = 1500L

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
                    // Автосвайп только внутри экрана операций склада («ДОСТУПНЫЕ»)
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
                handleTimeSlotBottomSheet(rootNode)
                return
            }

            // 2. КАЛЕНДАРЬ («Можете выбрать несколько дней сразу»)
            if (isCalendarScreen(fullScreenText)) {
                handleCalendarScreen(rootNode)
                return
            }

            // 3. ЭКРАН ОПЕРАЦИЙ СКЛАДА (заголовок «ДОСТУПНЫЕ», карточки «Подбор», «Размещение» и т.д.)
            if (isOperationsListScreen(fullScreenText)) {
                calendarEnterTime = 0L // сбрасываем таймер календаря
                handleOperationsListScreen(rootNode)
                return
            }

            // 4. СПИСОК СКЛАДОВ (вкладка «Склады», карточка склада «Воронеж 2» с кнопкой «Записаться»)
            if (isWarehouseListScreen(fullScreenText)) {
                handleWarehouseListScreen(rootNode)
                return
            }

            // 5. ГЛАВНАЯ / ЛЮБАЯ ДРУГАЯ СТРАНИЦА: нажимаем вкладку «Склады» в нижнем меню
            navigateToWarehousesTab(rootNode)

        } finally {
            rootNode.recycle()
        }
    }

    // =========================================================================
    // ТОЧНОЕ РАСПОЗНАВАНИЕ ЭКРАНОВ НА ОСНОВЕ СКРИНШОТОВ OZON JOB
    // =========================================================================

    /**
     * Шторка снизу с выбором длительности и времени: «Выберите время», «Длительность записи»
     */
    private fun isBottomSheet(text: String): Boolean {
        return text.contains("выберите время") ||
                text.contains("длительность записи") ||
                text.contains("подтвердим запись")
    }

    /**
     * Экран календаря: «Можете выбрать несколько дней сразу» или подзаголовок «Гибкий» с днями недели
     */
    private fun isCalendarScreen(text: String): Boolean {
        if (isBottomSheet(text)) return false
        return text.contains("можете выбрать несколько дней") ||
                (text.contains("гибкий") && text.contains("ноябрь") || text.contains("октябрь") || text.contains("пн"))
    }

    /**
     * Экран операций конкретного склада (Скриншот 3):
     * Всегда содержит заголовок «ДОСТУПНЫЕ» и названия операций («Подбор», «Размещение» и т.д.)
     */
    private fun isOperationsListScreen(text: String): Boolean {
        if (isBottomSheet(text) || isCalendarScreen(text)) return false
        val hasOps = defaultOperationsList.any { text.contains(it.lowercase()) }
        return text.contains("доступные") && hasOps
    }

    /**
     * Экран списка складов (Скриншот 2):
     * Вкладка «Склады», содержит «объектов», «вид услуг», фильтры и карточки складов с «о складе» / «построить маршрут»
     */
    private fun isWarehouseListScreen(text: String): Boolean {
        if (isBottomSheet(text) || isCalendarScreen(text) || isOperationsListScreen(text)) return false
        return text.contains("объектов") ||
                text.contains("вид услуг") ||
                text.contains("построить маршрут") ||
                text.contains("о складе")
    }

    // =========================================================================
    // ШАГ 1: Переход во вкладку «Склады» в нижнем меню (Скриншот 1 -> Скриншот 2)
    // =========================================================================
    private fun navigateToWarehousesTab(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < defaultCooldownMs) return

        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels

        val warehouseTabNodes = rootNode.findAccessibilityNodeInfosByText("Склады")
        for (node in warehouseTabNodes) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // Ищем вкладку именно в нижней панели навигации (y > 80% высоты экрана)
            if (node.isVisibleToUser && rect.centerY() > screenHeight * 0.80f) {
                Log.i(TAG, "ШАГ 1: Нажимаем вкладку 'Склады' в нижнем меню навигации...")
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
    // ШАГ 2: Выбор склада в списке («Воронеж 2» -> «Записаться») (Скриншот 2 -> 3)
    // =========================================================================
    private fun handleWarehouseListScreen(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < defaultCooldownMs) return

        val targetWarehouse = filterConfig.targetWarehouse.ifBlank { "Воронеж 2" }
        val whNodes = rootNode.findAccessibilityNodeInfosByText(targetWarehouse)

        for (whNode in whNodes) {
            if (!whNode.isVisibleToUser) {
                whNode.recycle()
                continue
            }

            // Находим карточку этого склада
            val card = findWarehouseCard(whNode)
            // На карточке склада есть синяя кнопка «Записаться» (Скриншот 2)
            val bookBtn = findNodeWithTextFuzzy(card, "Записаться")

            if (bookBtn != null && bookBtn.isVisibleToUser) {
                Log.i(TAG, "ШАГ 2: На карточке склада '$targetWarehouse' нажимаем синюю кнопку 'Записаться'...")
                if (clickOrTap(bookBtn)) {
                    lastClickTime = now
                }
                bookBtn.recycle()
                if (card != whNode) card.recycle()
                whNode.recycle()
                return
            }

            // Если кнопку не нашли, нажимаем прямо на карточку склада / название
            Log.i(TAG, "ШАГ 2: Нажимаем на склад '$targetWarehouse'...")
            if (clickOrTap(whNode)) {
                lastClickTime = now
            }
            if (card != whNode) card.recycle()
            whNode.recycle()
            return
        }
    }

    private fun findWarehouseCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        var count = 0
        while (count < 5) {
            val parent = current.parent ?: break
            val parentText = collectAllText(parent).lowercase()
            if (parentText.contains("записаться") || parentText.contains("о складе") || parentText.contains("построить маршрут")) {
                return parent
            }
            current = parent
            count++
        }
        return node
    }

    // =========================================================================
    // ШАГ 3: Экран операций склада (Выбор процесса «Подбор», «Размещение» и др.) (Скриншот 3 -> 4)
    // =========================================================================
    private fun handleOperationsListScreen(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < defaultCooldownMs) return

        val targetCard = findMatchingOperationCard(rootNode)
        if (targetCard != null) {
            Log.i(TAG, "ШАГ 3: Найдена подходящая операция. Открываем календарь...")
            val clicked = clickOrTap(targetCard)
            if (clicked) {
                lastClickTime = now
                calendarEnterTime = now // фиксируем время входа в календарь
            }
            targetCard.recycle()
        }
    }

    /**
     * Поиск конкретной карточки операции («Подбор», «Размещение» и т.д.), удовлетворяющей фильтрам
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

                // Ищем родительскую карточку конкретной операции
                val card = findOperationCard(node)
                val cardText = collectAllText(card).lowercase()

                if (matchesOperationFilters(cardText, filterConfig)) {
                    Log.i(TAG, "ШАГ 3: Операция '$opName' подходит под фильтры: $cardText")
                    // Нажимаем саму карточку или заголовок процесса
                    return node
                }
                if (card != node) {
                    card.recycle()
                }
                node.recycle()
            }
        }
        return null
    }

    private fun findOperationCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
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

        // 1. Фильтр повышенного тарифа ⚡ / 🔥
        if (config.onlyHotTariff) {
            val isHot = hotRateMarkers.any { lower.contains(it) }
            if (!isHot) return false
        }

        // 2. Фильтр даты в карточке (Сегодня, Завтра)
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

        // 3. Фильтр минимальной ставки (цена карточки)
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
    // ШАГ 4: Экран календаря (Выбор даты -> Синяя кнопка «Выбрать время» ИЛИ Выход назад) (Скриншот 4 -> 5)
    // =========================================================================
    private fun handleCalendarScreen(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (calendarEnterTime == 0L) {
            calendarEnterTime = now
        }

        // 1. Скриншот 5: Синяя кнопка «Выбрать время» внизу уже появилась — МОМЕНТАЛЬНО НАЖИМАЕМ ЕЁ!
        val selectTimeBtn = findNodeWithTextFuzzy(rootNode, "Выбрать время")
        if (selectTimeBtn != null && selectTimeBtn.isVisibleToUser) {
            Log.i(TAG, "ШАГ 4: Кнопка 'Выбрать время' активна! Нажимаем её...")
            if (clickOrTap(selectTimeBtn)) {
                lastClickTime = now
            }
            selectTimeBtn.recycle()
            return
        }

        // 2. Скриншот 4: День еще не выбран — ищем и нажимаем нужный день в календаре
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
            TargetDateChoice.ANY -> 0 // Любая доступная смена
        }

        var dayTapped = false

        if (targetDayNumber > 0) {
            dayTapped = findAndTapCalendarDay(rootNode, targetDayNumber)
        } else {
            // Ищем горячий день с молнией/огнем (как день 1 на скриншоте 4) или любой доступный
            dayTapped = findAndTapAnyAvailableCalendarDay(rootNode)
        }

        if (dayTapped) {
            lastClickTime = now
            return
        }

        // 3. ЕСЛИ СМЕНЫ НЕТ: прошло больше calendarMaxWaitMs и нажать день/кнопку не удалось — ВЫХОДИМ НАЗАД
        if (now - calendarEnterTime > calendarMaxWaitMs) {
            Log.i(TAG, "В этом процессе нет подходящей смены. Нажимаем стрелку НАЗАД и заходим заново...")
            goBack(rootNode)
            calendarEnterTime = 0L
            lastClickTime = now
        }
    }

    private fun findAndTapCalendarDay(rootNode: AccessibilityNodeInfo, dayNumber: Int): Boolean {
        val dayStr = dayNumber.toString()
        val nodes = rootNode.findAccessibilityNodeInfosByText(dayStr)
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels

        for (node in nodes) {
            if (!node.isVisibleToUser) {
                node.recycle()
                continue
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // Исключаем шапку и подвал (только календарная сетка)
            if (rect.centerY() < screenHeight * 0.20f || rect.centerY() > screenHeight * 0.85f) {
                node.recycle()
                continue
            }

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            val isMatch = text == dayStr || desc.contains(dayStr) ||
                    text.startsWith("$dayStr ") || text.startsWith("$dayStr\n")

            if (isMatch) {
                Log.i(TAG, "ШАГ 4: Нажимаем число $dayStr в календаре...")
                val success = clickOrTap(node)
                node.recycle()
                if (success) return true
            } else {
                node.recycle()
            }
        }
        return false
    }

    private fun findAndTapAnyAvailableCalendarDay(rootNode: AccessibilityNodeInfo): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels

        // 1. Ищем горячие маркеры (⚡, 🔥) в календаре
        for (marker in hotRateMarkers) {
            val markerNodes = rootNode.findAccessibilityNodeInfosByText(marker)
            for (node in markerNodes) {
                if (!node.isVisibleToUser) {
                    node.recycle()
                    continue
                }
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.centerY() in (screenHeight * 0.20f).toInt()..(screenHeight * 0.85f).toInt()) {
                    Log.i(TAG, "ШАГ 4: Нажимаем горячий день с маркером $marker...")
                    val success = clickOrTap(node)
                    node.recycle()
                    if (success) return true
                }
                node.recycle()
            }
        }

        // 2. Если маркеров нет, пробуем сегодняшний день или день 1
        val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        if (findAndTapCalendarDay(rootNode, today)) return true
        return findAndTapCalendarDay(rootNode, 1)
    }

    /**
     * Выход назад: нажимаем стрелку «<» в левом верхнем углу шапки
     */
    private fun goBack(rootNode: AccessibilityNodeInfo) {
        // Нажимаем координаты стрелки назад в левом верхнем углу (x=70, y=140)
        tapAt(70f, 140f)
        // Системный жест Back на случай задержки
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // =========================================================================
    // ШАГ 5: Шторка "Выберите время" (Длительность -> ГАЛОЧКА -> Записаться)
    // =========================================================================
    private fun handleTimeSlotBottomSheet(rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 250L) return // максимальная скорость реакции

        // 1. Выбор длительности записи (12 часов / 6 часов)
        when (filterConfig.duration) {
            ShiftDuration.FULL -> clickByExactText(rootNode, "12 часов")
            ShiftDuration.SHORT -> clickByExactText(rootNode, "6 часов")
            ShiftDuration.ANY -> {}
        }

        // 2. НАЖАТИЕ НА ГАЛОЧКУ СЛОТА ВРЕМЕНИ (правый край карточки 8:00 - 20:00)
        checkSlotConfirmationCheckbox(rootNode)

        // 3. ФИНАЛЬНАЯ СИНЯЯ КНОПКА «Записаться» (становится «Записаться, 1 запись, ≈...»)
        val bookBtn = findNodeWithTextFuzzy(rootNode, "Записаться")
        if (bookBtn != null && bookBtn.isVisibleToUser) {
            Log.i(TAG, "ФИНАЛ: НАЖАТА КНОПКА 'ЗАПИСАТЬСЯ'! СМЕНА УСПЕШНО ЗАБРОНИРОВАНА!")
            val success = clickOrTap(bookBtn)
            if (success) {
                lastClickTime = now + 1500L
            }
            bookBtn.recycle()
            return
        }
    }

    /**
     * Поиск и нажатие на чекбокс (квадратная галочка) слота времени
     */
    private fun checkSlotConfirmationCheckbox(rootNode: AccessibilityNodeInfo) {
        // А) Стандартный чекбокс, если размечен в системе
        val checkableNodes = findCheckableNodes(rootNode)
        for (cb in checkableNodes) {
            if (cb.isVisibleToUser && !cb.isChecked) {
                Log.i(TAG, "Нажимаем на чекбокс подтверждения записи!")
                clickOrTap(cb)
                cb.recycle()
                return
            }
            cb.recycle()
        }

        // Б) Надежный физический тап по правому краю карточки времени (где находится квадрат галочки)
        val slotMarkers = listOf("8:00", "20:00", "подтвердим запись", "подтвердим")
        for (marker in slotMarkers) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(marker)
            for (node in nodes) {
                if (node.isVisibleToUser) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val screenWidth = resources.displayMetrics.widthPixels
                    val tapX = screenWidth * 0.90f // 90% ширины экрана — правый край строки
                    val tapY = rect.centerY().toFloat()

                    Log.i(TAG, "Тапаем по чекбоксу в строке времени ($tapX, $tapY)...")
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

    private fun findNodeWithTextFuzzy(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isVisibleToUser) {
                return node
            }
            node.recycle()
        }
        return null
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
