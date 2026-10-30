package com.ozonjob.catcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusIndicatorDot: View
    private lateinit var tvStatus: TextView
    private lateinit var btnEnableAccessibility: Button
    private lateinit var etWarehouse: EditText
    private lateinit var cbOnlyHotTariff: SwitchCompat
    private lateinit var spDateChoice: Spinner
    private lateinit var spDuration: Spinner
    private lateinit var spTimeOfDay: Spinner
    
    // Операции со склада
    private lateinit var cbOpPicking: CheckBox
    private lateinit var cbOpPlacement: CheckBox
    private lateinit var cbOpReceiving: CheckBox
    private lateinit var cbOpPacking: CheckBox
    private lateinit var cbOpSortingSmall: CheckBox
    private lateinit var cbOpSortingBig: CheckBox
    private lateinit var cbOpReturns: CheckBox
    private lateinit var cbOpReturnsPicking: CheckBox
    private lateinit var cbOpConsolidation: CheckBox

    // Сумма оплаты
    private lateinit var btnMinusPrice: Button
    private lateinit var etMinPrice: EditText
    private lateinit var btnPlusPrice: Button

    private lateinit var cbAutoSwipe: CheckBox
    private lateinit var btnToggleCatching: Button

    private val PREFS_NAME = "OzonCatcherSettings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupSpinners()
        setupPriceButtons()
        loadSavedSettings()

        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnToggleCatching.setOnClickListener {
            toggleCatcher()
        }
    }

    private fun initViews() {
        statusIndicatorDot = findViewById(R.id.statusIndicatorDot)
        tvStatus = findViewById(R.id.tvStatus)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        etWarehouse = findViewById(R.id.etWarehouse)
        cbOnlyHotTariff = findViewById(R.id.cbOnlyHotTariff)
        spDateChoice = findViewById(R.id.spDateChoice)
        spDuration = findViewById(R.id.spDuration)
        spTimeOfDay = findViewById(R.id.spTimeOfDay)

        cbOpPicking = findViewById(R.id.cbOpPicking)
        cbOpPlacement = findViewById(R.id.cbOpPlacement)
        cbOpReceiving = findViewById(R.id.cbOpReceiving)
        cbOpPacking = findViewById(R.id.cbOpPacking)
        cbOpSortingSmall = findViewById(R.id.cbOpSortingSmall)
        cbOpSortingBig = findViewById(R.id.cbOpSortingBig)
        cbOpReturns = findViewById(R.id.cbOpReturns)
        cbOpReturnsPicking = findViewById(R.id.cbOpReturnsPicking)
        cbOpConsolidation = findViewById(R.id.cbOpConsolidation)

        btnMinusPrice = findViewById(R.id.btnMinusPrice)
        etMinPrice = findViewById(R.id.etMinPrice)
        btnPlusPrice = findViewById(R.id.btnPlusPrice)

        cbAutoSwipe = findViewById(R.id.cbAutoSwipe)
        btnToggleCatching = findViewById(R.id.btnToggleCatching)
    }

    private fun setupSpinners() {
        val dateOptions = arrayOf("Сегодня", "Завтра", "Любая дата")
        spDateChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dateOptions)

        val durationOptions = arrayOf("Любая длительность", "12 часов (Полная)", "6 часов (Короткая)")
        spDuration.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, durationOptions)

        val timeOptions = arrayOf("Любое время суток", "☀️ День (8:00 – 20:00)", "🌙 Ночь (20:00 – 8:00)")
        spTimeOfDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, timeOptions)
    }

    private fun setupPriceButtons() {
        btnMinusPrice.setOnClickListener {
            val current = etMinPrice.text.toString().toIntOrNull() ?: 8000
            val next = (current - 500).coerceAtLeast(0)
            etMinPrice.setText(next.toString())
        }

        btnPlusPrice.setOnClickListener {
            val current = etMinPrice.text.toString().toIntOrNull() ?: 8000
            val next = current + 500
            etMinPrice.setText(next.toString())
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (service in enabledServices) {
            if (service.id.contains(packageName)) {
                return true
            }
        }
        return false
    }

    private fun updateAccessibilityStatus() {
        if (isAccessibilityServiceEnabled()) {
            statusIndicatorDot.setBackgroundColor(Color.parseColor("#00D26A"))
            tvStatus.text = "Служба активна и готова к перехвату"
            tvStatus.setTextColor(Color.parseColor("#00D26A"))
            btnEnableAccessibility.visibility = View.GONE
        } else {
            statusIndicatorDot.setBackgroundColor(Color.parseColor("#FF3B30"))
            tvStatus.text = "Служба не включена в настройках"
            tvStatus.setTextColor(Color.parseColor("#FF3B30"))
            btnEnableAccessibility.visibility = View.VISIBLE
            btnEnableAccessibility.text = "Предоставить разрешение"
        }
    }

    private fun saveCurrentSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("warehouse", etWarehouse.text.toString())
            putBoolean("onlyHot", cbOnlyHotTariff.isChecked)
            putInt("dateChoice", spDateChoice.selectedItemPosition)
            putInt("duration", spDuration.selectedItemPosition)
            putInt("timeOfDay", spTimeOfDay.selectedItemPosition)

            putBoolean("opPicking", cbOpPicking.isChecked)
            putBoolean("opPlacement", cbOpPlacement.isChecked)
            putBoolean("opReceiving", cbOpReceiving.isChecked)
            putBoolean("opPacking", cbOpPacking.isChecked)
            putBoolean("opSortingSmall", cbOpSortingSmall.isChecked)
            putBoolean("opSortingBig", cbOpSortingBig.isChecked)
            putBoolean("opReturns", cbOpReturns.isChecked)
            putBoolean("opReturnsPicking", cbOpReturnsPicking.isChecked)
            putBoolean("opConsolidation", cbOpConsolidation.isChecked)

            putString("minPrice", etMinPrice.text.toString())
            putBoolean("autoSwipe", cbAutoSwipe.isChecked)
            apply()
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etWarehouse.setText(prefs.getString("warehouse", "Воронеж 2"))
        cbOnlyHotTariff.isChecked = prefs.getBoolean("onlyHot", false)
        spDateChoice.setSelection(prefs.getInt("dateChoice", 0))
        spDuration.setSelection(prefs.getInt("duration", 0))
        spTimeOfDay.setSelection(prefs.getInt("timeOfDay", 0))

        cbOpPicking.isChecked = prefs.getBoolean("opPicking", true)
        cbOpPlacement.isChecked = prefs.getBoolean("opPlacement", false)
        cbOpReceiving.isChecked = prefs.getBoolean("opReceiving", false)
        cbOpPacking.isChecked = prefs.getBoolean("opPacking", false)
        cbOpSortingSmall.isChecked = prefs.getBoolean("opSortingSmall", false)
        cbOpSortingBig.isChecked = prefs.getBoolean("opSortingBig", false)
        cbOpReturns.isChecked = prefs.getBoolean("opReturns", false)
        cbOpReturnsPicking.isChecked = prefs.getBoolean("opReturnsPicking", false)
        cbOpConsolidation.isChecked = prefs.getBoolean("opConsolidation", false)

        etMinPrice.setText(prefs.getString("minPrice", "8000"))
        cbAutoSwipe.isChecked = prefs.getBoolean("autoSwipe", true)
    }

    private fun toggleCatcher() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Сначала включите службу в Спецвозможностях!", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        saveCurrentSettings()

        val selectedOperations = mutableSetOf<String>()
        if (cbOpPicking.isChecked) selectedOperations.add("Подбор")
        if (cbOpPlacement.isChecked) selectedOperations.add("Размещение")
        if (cbOpReceiving.isChecked) selectedOperations.add("Приемка")
        if (cbOpPacking.isChecked) selectedOperations.add("Упаковка")
        if (cbOpSortingSmall.isChecked) selectedOperations.add("Сортировка мелкого товара")
        if (cbOpSortingBig.isChecked) selectedOperations.add("Сортировка крупного товара")
        if (cbOpReturns.isChecked) selectedOperations.add("Возвраты")
        if (cbOpReturnsPicking.isChecked) selectedOperations.add("Подбор возвратов")
        if (cbOpConsolidation.isChecked) selectedOperations.add("Консолидация")

        val duration = when (spDuration.selectedItemPosition) {
            1 -> ShiftDuration.FULL
            2 -> ShiftDuration.SHORT
            else -> ShiftDuration.ANY
        }

        val timeOfDay = when (spTimeOfDay.selectedItemPosition) {
            1 -> ShiftTimeOfDay.DAY
            2 -> ShiftTimeOfDay.NIGHT
            else -> ShiftTimeOfDay.ANY
        }

        val dateChoice = when (spDateChoice.selectedItemPosition) {
            0 -> TargetDateChoice.TODAY
            1 -> TargetDateChoice.TOMORROW
            else -> TargetDateChoice.ANY
        }

        val config = ShiftFilterConfig(
            minPayment = etMinPrice.text.toString().toIntOrNull() ?: 0,
            targetWarehouse = etWarehouse.text.toString().trim(),
            dateChoice = dateChoice,
            duration = duration,
            timeOfDay = timeOfDay,
            onlyHotTariff = cbOnlyHotTariff.isChecked,
            operations = selectedOperations,
            autoSwipeEnabled = cbAutoSwipe.isChecked
        )

        ShiftCatcherService.filterConfig = config

        if (ShiftCatcherService.isCatchingActive) {
            ShiftCatcherService.isCatchingActive = false
            btnToggleCatching.text = "▶  ЗАПУСТИТЬ ЛОВЛЮ"
            btnToggleCatching.background = ContextCompat.getDrawable(this, R.drawable.bg_button_start)
            Toast.makeText(this, "Ловля остановлена", Toast.LENGTH_SHORT).show()
        } else {
            ShiftCatcherService.isCatchingActive = true
            btnToggleCatching.text = "⏹  ОСТАНОВИТЬ ЛОВЛЮ"
            btnToggleCatching.background = ContextCompat.getDrawable(this, R.drawable.bg_button_stop)
            Toast.makeText(this, "Ловля запущена! Откройте Ozon Job", Toast.LENGTH_LONG).show()
        }
    }
}
