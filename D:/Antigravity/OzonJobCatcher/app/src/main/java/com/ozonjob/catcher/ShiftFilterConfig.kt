package com.ozonjob.catcher

enum class ShiftDuration {
    ANY,    // Любая
    FULL,   // Полная (10-12 ч)
    SHORT   // Короткая (4-8 ч)
}

enum class ShiftTimeOfDay {
    ANY,    // Любое время
    DAY,    // Дневная
    NIGHT   // Ночная
}

enum class TargetDateChoice {
    ANY,      // Любая
    TODAY,    // Сегодня
    TOMORROW, // Завтра
    CUSTOM    // Конкретное число
}

data class ShiftFilterConfig(
    var minPayment: Int = 8000,
    var targetWarehouse: String = "Воронеж 2", // Из скриншота пользователя
    var dateChoice: TargetDateChoice = TargetDateChoice.TODAY,
    var customDayNumber: Int = 0,             // Число месяца (например, 31 или 1)
    var duration: ShiftDuration = ShiftDuration.ANY,
    var timeOfDay: ShiftTimeOfDay = ShiftTimeOfDay.ANY,
    var onlyHotTariff: Boolean = false,       // Ловить только с молнией ⚡ Тариф +%
    var operations: Set<String> = emptySet(), // Подбор, Приемка, Размещение и т.д.
    var autoRefreshIntervalMs: Long = 1500L,
    var autoSwipeEnabled: Boolean = true
)
