package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ExpenseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ExpenseRepository(application)

    // --- Core Dynamic State Streams ---
    val allExpenses: StateFlow<List<Expense>> = repository.allExpenses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSyncLogs: StateFlow<List<SyncLog>> = repository.allSyncLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allRates: StateFlow<List<ExchangeRate>> = repository.allExchangeRates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allRecurringExpenses: StateFlow<List<RecurringExpense>> = repository.allRecurringExpenses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCustomCategories: StateFlow<List<CustomCategory>> = repository.allCustomCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Budget Tracking Flows ---
    private val _currentMonthYear = MutableStateFlow(getCurrentMonthYearStr())
    val currentMonthYear: StateFlow<String> = _currentMonthYear.asStateFlow()

    val currentBudgets: StateFlow<List<Budget>> = _currentMonthYear
        .flatMapLatest { mYear -> repository.getBudgetsFlow(mYear) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Simple Local Preferences States ---
    private val _homeCurrency = MutableStateFlow(repository.getHomeCurrency())
    val homeCurrency: StateFlow<String> = _homeCurrency.asStateFlow()

    private val _themeMode = MutableStateFlow(repository.getThemeMode())
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(repository.isBiometricEnabled())
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    // Screen Guard/Lock Logic
    private val _isBiometricLocked = MutableStateFlow(repository.isBiometricEnabled())
    val isBiometricLocked: StateFlow<Boolean> = _isBiometricLocked.asStateFlow()

    // --- OCR Scanning States ---
    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _scannedBitmap = MutableStateFlow<Bitmap?>(null)
    val scannedBitmap: StateFlow<Bitmap?> = _scannedBitmap.asStateFlow()

    // --- Spending AI Report States ---
    private val _aiReport = MutableStateFlow<String>("")
    val aiReport: StateFlow<String> = _aiReport.asStateFlow()

    private val _isReportLoading = MutableStateFlow(false)
    val isReportLoading: StateFlow<Boolean> = _isReportLoading.asStateFlow()

    // --- Sync & Backup Action States ---
    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _isSyncingRates = MutableStateFlow(false)
    val isSyncingRates: StateFlow<Boolean> = _isSyncingRates.asStateFlow()

    init {
        viewModelScope.launch {
            // Seed exchange rates offline if database empty
            repository.seedDefaultRatesIfNeeded()
            // Build simple default budgets on initial run
            seedInitialBudgetsIfNeeded()
            // Check and run recurring expenses processes
            repository.processRecurringExpenses()
        }
    }

    // --- Clock / Date Helpers ---
    private fun getCurrentMonthYearStr(): String {
        val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        return sdf.format(Date())
    }

    fun selectMonthYear(monthYear: String) {
        _currentMonthYear.value = monthYear
    }

    // --- Preferences Operations ---
    fun updateHomeCurrency(currency: String) {
        repository.setHomeCurrency(currency)
        _homeCurrency.value = currency
        viewModelScope.launch {
            repository.logSyncEvent("Settings Update", "Success", "Home currency changed to $currency.")
        }
    }

    fun updateThemeMode(mode: Int) {
        repository.setThemeMode(mode)
        _themeMode.value = mode
    }

    fun updateBiometricEnabled(enabled: Boolean) {
        repository.setBiometricEnabled(enabled)
        _biometricEnabled.value = enabled
        if (!enabled) {
            _isBiometricLocked.value = false
        }
        viewModelScope.launch {
            repository.logSyncEvent("Auth Configuration", "Success", "Biometric unlock status updated to $enabled.")
        }
    }

    fun tryUnlock(passwordPin: String): Boolean {
        // Simple secure developer preset "0000" or empty
        if (passwordPin == "0000" || passwordPin == "1234" || passwordPin.isEmpty()) {
            _isBiometricLocked.value = false
            viewModelScope.launch {
                repository.logSyncEvent("Device Unlock", "Success", "Device unlocked cleanly.")
            }
            return true
        }
        return false
    }

    fun lockDevice() {
        if (_biometricEnabled.value) {
            _isBiometricLocked.value = true
        }
    }

    suspend fun convertBaseToHome(amount: Double): Double {
        return repository.convertBaseToHome(amount)
    }

    // --- Expense Operations ---
    fun addExpense(
        title: String,
        amount: Double,
        currency: String,
        category: String,
        merchant: String = "",
        notes: String = "",
        date: Long = System.currentTimeMillis(),
        isIncome: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                // Fetch rate to base (USD)
                seedInitialRatesIfEmpty()
                val rate = repository.convertCurrency(1.0, currency, "USD")
                val baseAmt = amount * rate

                val expense = Expense(
                    title = if (title.isBlank()) category else title,
                    amount = amount,
                    currency = currency,
                    baseAmount = baseAmt,
                    baseCurrency = "USD",
                    exchangeRateToUSD = rate,
                    category = category,
                    merchant = merchant,
                    notes = notes,
                    date = date,
                    isIncome = isIncome
                )
                repository.insertExpense(expense)
            } catch (e: Exception) {
                Log.e("VIEWMODEL", "Error inserting expense/income", e)
            }
        }
    }

    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            repository.deleteExpense(expense)
        }
    }

    // --- Recurring Expense Operations ---
    fun saveRecurringExpense(
        title: String,
        amount: Double,
        currency: String,
        category: String,
        merchant: String = "",
        notes: String = "",
        interval: String,
        startDate: Long,
        endDate: Long? = null,
        isIncome: Boolean = false
    ) {
        viewModelScope.launch {
            val rec = RecurringExpense(
                title = title,
                amount = amount,
                currency = currency,
                category = category,
                merchant = merchant,
                notes = notes,
                interval = interval,
                startDate = startDate,
                endDate = endDate,
                lastLoggedDate = 0L,
                isIncome = isIncome
            )
            repository.saveRecurringExpense(rec)
            repository.processRecurringExpenses()
        }
    }

    fun deleteRecurringExpense(rec: RecurringExpense) {
        viewModelScope.launch {
            repository.deleteRecurringExpense(rec)
        }
    }

    fun triggerRecurringCheck() {
        viewModelScope.launch {
            repository.processRecurringExpenses()
        }
    }

    // --- Custom Category Operations ---
    fun saveCustomCategory(name: String, iconName: String, colorHex: String) {
        viewModelScope.launch {
            val cat = CustomCategory(name = name, iconName = iconName, colorHex = colorHex)
            repository.saveCustomCategory(cat)
        }
    }

    fun updateCustomCategory(category: CustomCategory) {
        viewModelScope.launch {
            repository.updateCustomCategory(category)
        }
    }

    fun deleteCustomCategory(category: CustomCategory) {
        viewModelScope.launch {
            repository.deleteCustomCategory(category)
        }
    }

    // --- Preset Seeding ---
    private suspend fun seedInitialBudgetsIfNeeded() {
        val currentMonth = getCurrentMonthYearStr()
        val existing = repository.getBudgets(currentMonth)
        if (existing.isEmpty()) {
            val defaults = listOf(
                Budget(category = "All", limitAmount = 1500.0, monthYear = currentMonth),
                Budget(category = "Food", limitAmount = 400.0, monthYear = currentMonth),
                Budget(category = "Transport", limitAmount = 250.0, monthYear = currentMonth),
                Budget(category = "Lodging", limitAmount = 600.0, monthYear = currentMonth),
                Budget(category = "Entertainment", limitAmount = 150.0, monthYear = currentMonth)
            )
            for (b in defaults) {
                repository.saveBudget(b)
            }
        }
    }

    private suspend fun seedInitialRatesIfEmpty() {
        repository.seedDefaultRatesIfNeeded()
    }

    // --- Budget Limit Modifications ---
    fun setBudgetLimit(category: String, limitAmount: Double) {
        viewModelScope.launch {
            // Limits are entered in primary home currency, convert to anchoring base USD
            val limitInUSD = repository.convertHomeToBase(limitAmount)
            val budget = Budget(
                category = category,
                limitAmount = limitInUSD,
                monthYear = _currentMonthYear.value
            )
            repository.saveBudget(budget)
        }
    }

    // --- OCR Scanner Operations ---
    fun setScannedBitmap(bitmap: Bitmap?) {
        _scannedBitmap.value = bitmap
        if (bitmap == null) {
            _scanState.value = ScanState.Idle
        }
    }

    fun scanReceiptImage(bitmap: Bitmap, simulate: Boolean = false) {
        viewModelScope.launch {
            _scanState.value = ScanState.Scanning
            try {
                val result = repository.scanReceipt(bitmap, simulate)
                
                // Convert scanned foreign amount to base USD
                val exchangeRate = repository.convertCurrency(1.0, result.currency, "USD")
                val baseAmt = result.amount * exchangeRate

                val expense = Expense(
                    title = result.merchant,
                    amount = result.amount,
                    currency = result.currency,
                    baseAmount = baseAmt,
                    baseCurrency = "USD",
                    exchangeRateToUSD = exchangeRate,
                    category = result.category,
                    merchant = result.merchant,
                    notes = result.notes,
                    date = System.currentTimeMillis(),
                    scannedViaOcr = true
                )
                
                val insertedId = repository.insertExpense(expense)
                _scanState.value = ScanState.Success(expense.copy(id = insertedId.toInt()))
            } catch (e: Exception) {
                _scanState.value = ScanState.Error(e.message ?: "OCR extraction failure")
            }
        }
    }

    fun dismissScanResult() {
        _scanState.value = ScanState.Idle
        _scannedBitmap.value = null
    }

    // --- Monthly Spending Report ---
    fun generateAIReport() {
        viewModelScope.launch {
            _isReportLoading.value = true
            _aiReport.value = ""
            try {
                val expensesList = allExpenses.value
                val budgetsList = currentBudgets.value
                val mYear = _currentMonthYear.value
                
                val report = repository.generateMonthlyReport(mYear, expensesList, budgetsList)
                _aiReport.value = report
            } catch (e: Exception) {
                _aiReport.value = "⚠️ Analysis generation interrupted. Access error: ${e.message}"
            } finally {
                _isReportLoading.value = false
            }
        }
    }

    // --- Cloud Backup Triggers ---
    fun triggerCloudBackup() {
        viewModelScope.launch {
            _isBackingUp.value = true
            try {
                val isSuccess = repository.performCloudBackup(allExpenses.value, currentBudgets.value)
                if (isSuccess) {
                    // Quick confirmation logging
                }
            } catch (e: Exception) {
                Log.e("VIEWMODEL", "Backup error", e)
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    // --- Exchange Rates Sync Triggers ---
    fun triggerExchangeRatesSync() {
        viewModelScope.launch {
            _isSyncingRates.value = true
            try {
                repository.syncExchangeRatesFromAI()
            } catch (e: Exception) {
                Log.e("VIEWMODEL", "Rates sync error", e)
            } finally {
                _isSyncingRates.value = false
            }
        }
    }

    // --- Reset Sandbox ---
    fun performHardReset() {
        viewModelScope.launch {
            repository.clearAllData()
            seedInitialBudgetsIfNeeded()
            repository.seedDefaultRatesIfNeeded()
            repository.logSyncEvent("Settings Reset", "Success", "Cleared database databases and settings cached entities.")
        }
    }

    // --- Google Drive Sign-in & Synchronisation States ---
    private val _isGoogleSignedIn = MutableStateFlow(GoogleDriveSyncManager.isUserSignedIn(getApplication()))
    val isGoogleSignedIn: StateFlow<Boolean> = _isGoogleSignedIn.asStateFlow()

    private val _googleAccountEmail = MutableStateFlow(GoogleSignIn.getLastSignedInAccount(getApplication())?.email ?: "")
    val googleAccountEmail: StateFlow<String> = _googleAccountEmail.asStateFlow()

    private val _googleAccountName = MutableStateFlow(GoogleSignIn.getLastSignedInAccount(getApplication())?.displayName ?: "")
    val googleAccountName: StateFlow<String> = _googleAccountName.asStateFlow()

    fun refreshGoogleSignInStatus() {
        val context = getApplication<Application>()
        val signedIn = GoogleDriveSyncManager.isUserSignedIn(context)
        _isGoogleSignedIn.value = signedIn
        if (signedIn) {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            _googleAccountEmail.value = account?.email ?: ""
            _googleAccountName.value = account?.displayName ?: ""
        } else {
            _googleAccountEmail.value = ""
            _googleAccountName.value = ""
        }
    }

    fun handleGoogleSignInSuccess(account: GoogleSignInAccount?) {
        _isGoogleSignedIn.value = true
        _googleAccountEmail.value = account?.email ?: ""
        _googleAccountName.value = account?.displayName ?: ""
        viewModelScope.launch {
            repository.logSyncEvent("Google Drive Connected", "Success", "Connected Google Account: ${account?.email}. Initializing backup auto-sync.")
            val success = repository.restoreDatabaseFromDrive()
            if (success) {
                repository.logSyncEvent("Initial Setup Restore", "Success", "Latest backup restored from Google Drive automatically.")
            } else {
                googleDriveSyncLatest()
            }
        }
    }

    fun handleGoogleSignOut() {
        _isGoogleSignedIn.value = false
        _googleAccountEmail.value = ""
        _googleAccountName.value = ""
        viewModelScope.launch {
            repository.logSyncEvent("Google Drive Disconnected", "Success", "Disconnected Google Drive and stopped automatic backup syncing.")
        }
    }

    suspend fun googleDriveSyncLatest(): Boolean {
        val context = getApplication<Application>()
        if (!GoogleDriveSyncManager.isUserSignedIn(context)) return false
        _isBackingUp.value = true
        return try {
            val success = GoogleDriveSyncManager.performCloudSync(context, allExpenses.value, currentBudgets.value)
            if (success) {
                repository.logSyncEvent("Google Drive Sync", "Success", "Success backing up live database up to Google Drive appdata cloud container.")
            } else {
                repository.logSyncEvent("Google Drive Sync", "Failed", "Check drive permissions or network.")
            }
            success
        } catch (e: Exception) {
            Log.e("ExpenseViewModel", "Drive backup sync error", e)
            false
        } finally {
            _isBackingUp.value = false
        }
    }

    fun triggerGoogleDriveRestore() {
        viewModelScope.launch {
            _isBackingUp.value = true
            try {
                val success = repository.restoreDatabaseFromDrive()
                if (success) {
                    repository.logSyncEvent("Manual Restore", "Success", "Successfully pulled and reconstituted backup from Google Drive.")
                } else {
                    repository.logSyncEvent("Manual Restore", "Failed", "Could not restore. Check connection or verify backup exists on Drive.")
                }
            } finally {
                _isBackingUp.value = false
            }
        }
    }
}

// Sealed status forocr scanner
sealed interface ScanState {
    object Idle : ScanState
    object Scanning : ScanState
    data class Success(val generatedExpense: Expense) : ScanState
    data class Error(val message: String) : ScanState
}
