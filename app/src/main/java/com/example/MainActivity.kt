package com.example

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import com.example.data.*
import com.example.ui.ExpenseViewModel
import com.example.ui.ScanState
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: ExpenseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.refreshGoogleSignInStatus()

        // Handle Widget deep-links
        val launchedFromWidget = intent?.getBooleanExtra("launch_quick_entry", false) ?: false

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val isDark = when (themeMode) {
                0 -> true
                1 -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            MyApplicationTheme(darkTheme = isDark) {
                val isLocked by viewModel.isBiometricLocked.collectAsStateWithLifecycle()
                val isGoogleSignedIn by viewModel.isGoogleSignedIn.collectAsStateWithLifecycle()
                var continueOfflineOverride by remember { mutableStateOf(false) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isLocked) {
                        BiometricLockScreen(
                            onUnlockSubmit = { pin ->
                                val success = viewModel.tryUnlock(pin)
                                if (!success) {
                                    Toast.makeText(this, "Incorrect PIN. Default is 1234", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    } else if (!isGoogleSignedIn && !continueOfflineOverride) {
                        GoogleLoginScreen(
                            viewModel = viewModel,
                            onContinueOffline = { continueOfflineOverride = true }
                        )
                    } else {
                        MainNavigationWorkspace(
                            viewModel = viewModel,
                            initialOpenQuickEntry = launchedFromWidget
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshGoogleSignInStatus()
        // Lock screen on app resume (if biometric enabled) to guarantee absolute data privacy
        viewModel.lockDevice()
    }
}

// --- Enum representing Bottom Navigation Screens ---
enum class AppScreen(val label: String, val iconSelected: ImageVector, val iconUnselected: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet),
    SCANNER("Scan Receipt", Icons.Filled.QrCodeScanner, Icons.Outlined.QrCodeScanner),
    BUDGETS("Budgets", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    ADVISOR("AI Advisor", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    SETTINGS("System Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun MainNavigationWorkspace(
    viewModel: ExpenseViewModel,
    initialOpenQuickEntry: Boolean = false
) {
    var activeScreen by remember { mutableStateOf(AppScreen.DASHBOARD) }
    var showQuickAddSheet by remember { mutableStateOf(initialOpenQuickEntry) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                AppScreen.values().forEach { screen ->
                    val isSelected = activeScreen == screen
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { activeScreen = screen },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) screen.iconSelected else screen.iconUnselected,
                                contentDescription = screen.label
                            )
                        },
                        label = {
                            Text(
                                text = screen.label,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.testTag("nav_tab_${screen.name.lowercase(Locale.ROOT)}")
                    )
                }
            }
        },
        floatingActionButton = {
            if (activeScreen == AppScreen.DASHBOARD) {
                FloatingActionButton(
                    onClick = { showQuickAddSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("fab_add_expense")
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = "Add Expense")
                }
            }
        },
        contentWindowInsets = WindowInsets.navigationBars
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Screen switching logic with smooth elegant transitions
            Crossfade(
                targetState = activeScreen,
                animationSpec = tween(250),
                label = "ScreenTransition"
            ) { screen ->
                when (screen) {
                    AppScreen.DASHBOARD -> DashboardScreen(viewModel = viewModel)
                    AppScreen.SCANNER -> ScannerScreen(viewModel = viewModel)
                    AppScreen.BUDGETS -> BudgetsScreen(viewModel = viewModel)
                    AppScreen.ADVISOR -> AdvisorScreen(viewModel = viewModel)
                    AppScreen.SETTINGS -> SettingsScreen(viewModel = viewModel)
                }
            }

            // Quick manual entry modal sheet/overlay
            if (showQuickAddSheet) {
                QuickEntryOverlay(
                    viewModel = viewModel,
                    onDismiss = { showQuickAddSheet = false }
                )
            }
        }
    }
}

// ==========================================
// SCREEN 1: DASHBOARD
// ==========================================
@Composable
fun DashboardScreen(viewModel: ExpenseViewModel) {
    val expenses by viewModel.allExpenses.collectAsStateWithLifecycle()
    val homeCurrency by viewModel.homeCurrency.collectAsStateWithLifecycle()
    val exchangeRates by viewModel.allRates.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Reactive advanced filter states
    var isFilterExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var minAmountStr by remember { mutableStateOf("") }
    var maxAmountStr by remember { mutableStateOf("") }
    var startDateStr by remember { mutableStateOf("") }
    var endDateStr by remember { mutableStateOf("") }
    var transactionTypeFilter by remember { mutableStateOf("All") } // "All", "Expenses", "Incomes"

    val customCategories by viewModel.allCustomCategories.collectAsStateWithLifecycle()
    val customCategoryNames = remember(customCategories) { customCategories.map { it.name } }

    val expenseCategories = remember(customCategoryNames) {
        listOf("Food", "Transport", "Lodging", "Entertainment", "Utilities", "Other") + customCategoryNames
    }
    val incomeCategories = listOf("Salary", "Freelance", "Investment", "Gift", "Refund", "Other")
    val allCategoriesList = remember(expenseCategories, incomeCategories) {
        listOf("All") + (expenseCategories + incomeCategories).distinct()
    }

    // Dynamic Filter Engine
    val filteredExpenses = remember(
        expenses, searchQuery, selectedCategory, minAmountStr, maxAmountStr, startDateStr, endDateStr, transactionTypeFilter
    ) {
        expenses.filter { exp ->
            // 1. Keyword search (title/merchant/notes)
            val matchesSearch = if (searchQuery.isNotBlank()) {
                exp.title.contains(searchQuery, ignoreCase = true) ||
                        exp.merchant.contains(searchQuery, ignoreCase = true) ||
                        exp.notes.contains(searchQuery, ignoreCase = true)
            } else true

            // 2. Category selection match
            val matchesCategory = if (selectedCategory != "All") {
                exp.category.equals(selectedCategory, ignoreCase = true)
            } else true

            // 3. Transaction Type (Expense vs Income)
            val matchesType = when (transactionTypeFilter) {
                "Expenses" -> !exp.isIncome
                "Incomes" -> exp.isIncome
                else -> true
            }

            // 4. Amount Limits check
            val minVal = minAmountStr.toDoubleOrNull()
            val maxVal = maxAmountStr.toDoubleOrNull()
            
            val matchesMin = if (minVal != null) {
                exp.amount >= minVal || exp.baseAmount >= minVal
            } else true

            val matchesMax = if (maxVal != null) {
                exp.amount <= maxVal || exp.baseAmount <= maxVal
            } else true

            // 5. Calendar Date limits
            val matchesDate = try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val startMillis = if (startDateStr.isNotBlank()) dateFormat.parse(startDateStr)?.time else null
                
                val endMillis = if (endDateStr.isNotBlank()) {
                    val parsedDate = dateFormat.parse(endDateStr)
                    if (parsedDate != null) {
                        val cal = Calendar.getInstance().apply {
                            time = parsedDate
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                            set(Calendar.SECOND, 59)
                            set(Calendar.MILLISECOND, 999)
                        }
                        cal.timeInMillis
                    } else null
                } else null

                val matchesStart = if (startMillis != null) exp.date >= startMillis else true
                val matchesEnd = if (endMillis != null) exp.date <= endMillis else true
                matchesStart && matchesEnd
            } catch (e: Exception) {
                true
            }

            matchesSearch && matchesCategory && matchesType && matchesMin && matchesMax && matchesDate
        }
    }

    // Helper Dialog for Android Native DatePicker
    val showDatePicker = { isStart: Boolean ->
        val calendar = Calendar.getInstance()
        val currentStr = if (isStart) startDateStr else endDateStr
        if (currentStr.isNotBlank()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                sdf.parse(currentStr)?.let { calendar.time = it }
            } catch (e: Exception) {}
        }
        try {
            android.app.DatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                    val sel = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    }
                    val formatted = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(sel.time)
                    if (isStart) {
                        startDateStr = formatted
                    } else {
                        endDateStr = formatted
                    }
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        } catch (e: Exception) {
            android.util.Log.e("DatePicker", "Could not show DatePickerDialog", e)
        }
    }

    val homeTotalExpenses = remember(expenses, homeCurrency, exchangeRates) {
        val rateMap = exchangeRates.associateBy { it.currency }
        var sumInHome = 0.0
        for (e in expenses) {
            if (!e.isIncome) {
                if (e.currency == homeCurrency) {
                    sumInHome += e.amount
                } else {
                    val rateToUSD = e.exchangeRateToUSD
                    val targetRateToUSD = rateMap[homeCurrency]?.rateToUSD ?: 1.0
                    sumInHome += if (targetRateToUSD > 0.0) {
                        (e.amount * rateToUSD) / targetRateToUSD
                    } else {
                        e.amount
                    }
                }
            }
        }
        sumInHome
    }

    val homeTotalIncome = remember(expenses, homeCurrency, exchangeRates) {
        val rateMap = exchangeRates.associateBy { it.currency }
        var sumInHome = 0.0
        for (e in expenses) {
            if (e.isIncome) {
                if (e.currency == homeCurrency) {
                    sumInHome += e.amount
                } else {
                    val rateToUSD = e.exchangeRateToUSD
                    val targetRateToUSD = rateMap[homeCurrency]?.rateToUSD ?: 1.0
                    sumInHome += if (targetRateToUSD > 0.0) {
                        (e.amount * rateToUSD) / targetRateToUSD
                    } else {
                        e.amount
                    }
                }
            }
        }
        sumInHome
    }

    val homeNetBalance = homeTotalIncome - homeTotalExpenses

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                    )
                )
            ),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Global Balance",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Travel Smart, Sync Safer",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
                
                // Multi-Currency Quick Indicator
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(
                        text = homeCurrency,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Summary Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("summary_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "NET DISPOSABLE BALANCE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        letterSpacing = 1.0.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${if (homeNetBalance >= 0) "" else "- "}$homeCurrency ${String.format(Locale.US, "%,.2f", Math.abs(homeNetBalance))}",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (homeNetBalance >= 0) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("total_amount_text")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowUpward,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "$homeCurrency ${String.format(Locale.US, "%,.2f", homeTotalIncome)}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981)
                                )
                            }
                            Text(text = "Total Incomes", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDownward,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "$homeCurrency ${String.format(Locale.US, "%,.2f", homeTotalExpenses)}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEF4444)
                                )
                            }
                            Text(text = "Total Expenses", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // SEARCH AND ADVANCED FILTERS PANEL
        item {
            val hasActiveFilters = searchQuery.isNotBlank() ||
                    selectedCategory != "All" ||
                    transactionTypeFilter != "All" ||
                    minAmountStr.isNotBlank() ||
                    maxAmountStr.isNotBlank() ||
                    startDateStr.isNotBlank() ||
                    endDateStr.isNotBlank()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_filter_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = if (hasActiveFilters) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                } else null
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Search Query Input Box
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search logs description or tags...", fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear text",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                
                                IconButton(onClick = { isFilterExpanded = !isFilterExpanded }) {
                                    Box {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = "Filters Settings",
                                            tint = if (isFilterExpanded || hasActiveFilters) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.size(20.dp)
                                        )
                                        if (hasActiveFilters && !isFilterExpanded) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF10B981))
                                                    .align(Alignment.TopEnd)
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_text_input")
                    )

                    // Collapsible Advanced Filters Section
                    AnimatedVisibility(
                        visible = isFilterExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

                            // 1. Transaction Type Choices
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "TRANSACTION TYPE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    letterSpacing = 0.5.sp
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val types = listOf("All", "Expenses", "Incomes")
                                    types.forEach { type ->
                                        val isSelected = transactionTypeFilter == type
                                        val bg = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        }
                                        val textCol = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(bg)
                                                .clickable { transactionTypeFilter = type }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = type,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = textCol
                                            )
                                        }
                                    }
                                }
                            }

                            // 2. Amount Ranges
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "AMOUNT LIMITS",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    letterSpacing = 0.5.sp
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = minAmountStr,
                                        onValueChange = { minAmountStr = it },
                                        placeholder = { Text("Min Value", fontSize = 12.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("search_min_amount"),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                                        )
                                    )
                                    OutlinedTextField(
                                        value = maxAmountStr,
                                        onValueChange = { maxAmountStr = it },
                                        placeholder = { Text("Max Value", fontSize = 12.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("search_max_amount"),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                                        )
                                    )
                                }
                            }

                            // 3. Date Limits Selection
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "VALUATION DATE RANGE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    letterSpacing = 0.5.sp
                                )
                                
                                // Direct Preset buttons
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    val presets = listOf("All time", "Today", "Last 7 Days", "This Month")
                                    presets.forEach { preset ->
                                        val isActive = when (preset) {
                                            "All time" -> startDateStr.isBlank() && endDateStr.isBlank()
                                            "Today" -> {
                                                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                                startDateStr == today && endDateStr == today
                                            }
                                            "Last 7 Days" -> {
                                                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                                val end = sdf.format(Date())
                                                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
                                                val start = sdf.format(cal.time)
                                                startDateStr == start && endDateStr == end
                                            }
                                            "This Month" -> {
                                                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                                val end = sdf.format(Date())
                                                val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
                                                val start = sdf.format(cal.time)
                                                startDateStr == start && endDateStr == end
                                            }
                                            else -> false
                                        }

                                        AssistChip(
                                            onClick = {
                                                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                                when (preset) {
                                                    "All time" -> {
                                                        startDateStr = ""
                                                        endDateStr = ""
                                                    }
                                                    "Today" -> {
                                                        val t = sdf.format(Date())
                                                        startDateStr = t
                                                        endDateStr = t
                                                    }
                                                    "Last 7 Days" -> {
                                                        val end = sdf.format(Date())
                                                        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
                                                        startDateStr = sdf.format(cal.time)
                                                        endDateStr = end
                                                    }
                                                    "This Month" -> {
                                                        val end = sdf.format(Date())
                                                        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
                                                        startDateStr = sdf.format(cal.time)
                                                        endDateStr = end
                                                    }
                                                }
                                            },
                                            label = { Text(preset, fontSize = 10.sp) },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                                labelColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                    }
                                }

                                // Interactive Picker fields
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = { showDatePicker(true) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DateRange,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (startDateStr.isBlank()) "Start Date" else startDateStr,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { showDatePicker(false) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DateRange,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (endDateStr.isBlank()) "End Date" else endDateStr,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // 4. Horizontal Categories chips
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "SELECT CATEGORY TAG",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    letterSpacing = 0.5.sp
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    allCategoriesList.forEach { cat ->
                                        val isSelected = selectedCategory == cat
                                        val bg = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        }
                                        val textCol = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(bg)
                                                .clickable { selectedCategory = cat }
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = cat,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = textCol
                                            )
                                        }
                                    }
                                }
                            }

                            // 5. Clear All Buttons
                            if (hasActiveFilters) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            searchQuery = ""
                                            selectedCategory = "All"
                                            transactionTypeFilter = "All"
                                            minAmountStr = ""
                                            maxAmountStr = ""
                                            startDateStr = ""
                                            endDateStr = ""
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ClearAll,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Reset All Filters", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section Title: Dynamic Title depending on searching state
        item {
            val hasActiveFilters = searchQuery.isNotBlank() ||
                    selectedCategory != "All" ||
                    transactionTypeFilter != "All" ||
                    minAmountStr.isNotBlank() ||
                    maxAmountStr.isNotBlank() ||
                    startDateStr.isNotBlank() ||
                    endDateStr.isNotBlank()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasActiveFilters) "Filtered Results (${filteredExpenses.size})" else "Recent Transactions",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier.clickable {
                        coroutineScope.launch {
                            viewModel.triggerExchangeRatesSync()
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync Rates", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // List of Expenses
        if (expenses.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ReceiptLong,
                        contentDescription = "No receipts",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No expenses logged yet",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "Scan a receipt or log manually directly",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }
            }
        } else if (filteredExpenses.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "No results",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No matching transactions found",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "Adjust your search filters, amounts, or date ranges.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            searchQuery = ""
                            selectedCategory = "All"
                            transactionTypeFilter = "All"
                            minAmountStr = ""
                            maxAmountStr = ""
                            startDateStr = ""
                            endDateStr = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Reset Filters", fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(filteredExpenses) { exp ->
                ExpenseItemRow(
                    expense = exp,
                    homeCurrency = homeCurrency,
                    customCategories = customCategories,
                    onDismiss = {
                        viewModel.deleteExpense(exp)
                    }
                )
            }
        }
    }
}

@Composable
fun ExpenseItemRow(
    expense: Expense,
    homeCurrency: String,
    customCategories: List<CustomCategory> = emptyList(),
    onDismiss: () -> Unit
) {
    val (icon, iconTint) = getCategoryIconAndColor(expense.category, expense.isIncome, customCategories)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("expense_row_${expense.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                // Circular Category Icon Accent
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = iconTint)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = expense.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val sdf = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
                        Text(
                            text = "${if (expense.isIncome) "Income: ${expense.category}" else expense.category} • ${sdf.format(Date(expense.date))}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        if (expense.scannedViaOcr) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            ) {
                                Text(
                                    text = "AI OCR",
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Amounts
            Column(horizontalAlignment = Alignment.End) {
                // Foreign Original Transaction
                Text(
                    text = "${if (expense.isIncome) "+ " else "- "}${expense.currency} ${String.format(Locale.US, "%.2f", expense.amount)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (expense.isIncome) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                )
                
                // Converted home base representation
                if (expense.currency != homeCurrency) {
                    Text(
                        text = "≈ ${if (expense.isIncome) "+ " else "- "}$homeCurrency ${String.format(Locale.US, "%.2f", expense.baseAmount)}",
                        fontSize = 10.sp,
                        color = if (expense.isIncome) Color(0xFF10B981).copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                
                Spacer(modifier = Modifier.height(2.dp))

                // Quick Delete
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "Delete entry",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onDismiss() }
                )
            }
        }
    }
}

// ==========================================
// SCREEN 2: RECEIPT SCANNER & OCR VIEW
// ==========================================
@Composable
fun ScannerScreen(viewModel: ExpenseViewModel) {
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = "Travel Receipt OCR", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(text = "Let Gemini extract merchant values and amounts", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        }

        when (scanState) {
            is ScanState.Idle -> {
                // Viewfinder Simulation / Interactive Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Symmetrical Viewfinder bracket look
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Box(modifier = Modifier.align(Alignment.TopStart).size(24.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 8.dp)))
                        Box(modifier = Modifier.align(Alignment.TopEnd).size(24.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(topEnd = 8.dp)))
                        Box(modifier = Modifier.align(Alignment.BottomStart).size(24.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(bottomStart = 8.dp)))
                        Box(modifier = Modifier.align(Alignment.BottomEnd).size(24.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(bottomEnd = 8.dp)))
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoCamera,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Simulated camera viewfinder active", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Align travel receipt inside brackets", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }

                // Presets to instantly test scanning
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "Try Premium Receipt Samples:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val samples = listOf(
                            Triple("Starbucks Seattle", "USD 5.45", "Food"),
                            Triple("Paris Bistro Bill", "EUR 34.80", "Food"),
                            Triple("Tokyo Subway", "JPY 320", "Transport")
                        )

                        samples.forEach { sample ->
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        // Simulate pick with loaded values
                                        val dummyBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                                        viewModel.scanReceiptImage(dummyBitmap, simulate = true)
                                    },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = sample.first, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = sample.second, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text(text = sample.third, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }

            is ScanState.Scanning -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Gemini OCR Reading Receipt...", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(text = "Extracting merchant, currency rates, date and values", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }

            is ScanState.Success -> {
                val expense = (scanState as ScanState.Success).generatedExpense
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        }

                        Text(text = "SCAN SUCCESS", fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Text(text = expense.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        
                        Text(
                            text = "${expense.currency} ${String.format(Locale.US, "%.2f", expense.amount)}",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Category:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            Text(expense.category, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Processed Date:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                            Text(sdf.format(Date(expense.date)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Button(
                            onClick = { viewModel.dismissScanResult() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Done & File to Vault")
                        }
                    }
                }
            }

            is ScanState.Error -> {
                val errorMsg = (scanState as ScanState.Error).message
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "Extraction Failed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text(text = errorMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.dismissScanResult() }) {
                            Text("Try Again")
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 3: BUDGETS & CANVAS VISUALIZATION
// ==========================================
@Composable
fun BudgetsScreen(viewModel: ExpenseViewModel) {
    val expenses by viewModel.allExpenses.collectAsStateWithLifecycle()
    val budgets by viewModel.currentBudgets.collectAsStateWithLifecycle()
    val homeCurrency by viewModel.homeCurrency.collectAsStateWithLifecycle()
    val exchangeRates by viewModel.allRates.collectAsStateWithLifecycle()
    val customCategories by viewModel.allCustomCategories.collectAsStateWithLifecycle()

    var showAdjustLimitDialog by remember { mutableStateOf(false) }
    var selectedBudgetCategory by remember { mutableStateOf("All") }
    var inputLimitValue by remember { mutableStateOf("") }

    // Aggregate category expenditures converted to home currency
    val expensesByCategory = remember(expenses, homeCurrency, exchangeRates) {
        val rates = exchangeRates.associateBy { it.currency }
        val categoryCalculations = mutableMapOf<String, Double>()
        var overallTotal = 0.0

        for (e in expenses) {
            if (!e.isIncome) {
                val amountInHome = if (e.currency == homeCurrency) {
                    e.amount
                } else {
                    val rateToUSD = e.exchangeRateToUSD
                    val targetRateToUSD = rates[homeCurrency]?.rateToUSD ?: 1.0
                    (e.amount * rateToUSD) / targetRateToUSD
                }
                overallTotal += amountInHome
                categoryCalculations[e.category] = categoryCalculations.getOrDefault(e.category, 0.0) + amountInHome
            }
        }
        categoryCalculations["All"] = overallTotal
        categoryCalculations
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "Budget limits", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(text = "Review set thresholds vs live expenditure breakdown", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            }
        }

        // Beautiful Canvas-Powered spending Donut Chart visualization
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Expenditure Distribution Chart", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier.size(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val categories = listOf("Food", "Transport", "Lodging", "Entertainment", "Utilities", "Other") + customCategories.map { it.name }
                            val colors = listOf(Color(0xFFEF4444), Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF64748B)) + customCategories.map { parseColorHex(it.colorHex) }
                            
                            val grandTotal = categories.sumOf { expensesByCategory[it] ?: 0.0 }
                            
                            if (grandTotal == 0.0) {
                                drawArc(
                                    color = Color.LightGray.copy(alpha = 0.3f),
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                                )
                            } else {
                                var currentAngle = -90f
                                for (i in categories.indices) {
                                    val catName = categories[i]
                                    val catAmt = expensesByCategory[catName] ?: 0.0
                                    if (catAmt > 0) {
                                        val sweep = (catAmt / grandTotal * 360).toFloat()
                                        drawArc(
                                            color = colors[i],
                                            startAngle = currentAngle,
                                            sweepAngle = sweep,
                                            useCenter = false,
                                            style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round)
                                        )
                                        currentAngle += sweep
                                    }
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Overall spent", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Text(
                                text = "${String.format(Locale.US, "%.0f", expensesByCategory["All"] ?: 0.0)}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(homeCurrency, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Simple legends layout using row with horizontal scrolling to make it stable on tablets & phones
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val legendItems = listOf(
                            Pair("Food", Color(0xFFEF4444)),
                            Pair("Transport", Color(0xFF3B82F6)),
                            Pair("Lodging", Color(0xFF8B5CF6)),
                            Pair("Entertainment", Color(0xFFF59E0B)),
                            Pair("Utilities", Color(0xFF10B981)),
                            Pair("Other", Color(0xFF64748B))
                        ) + customCategories.map { Pair(it.name, parseColorHex(it.colorHex)) }
                        legendItems.forEach { legend ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(legend.second))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = legend.first, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }

        // Grid/List of live Budgets limits comparison
        item {
            Text(text = "Category Limits Management", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        val categoriesToLog = listOf("All", "Food", "Transport", "Lodging", "Entertainment", "Utilities", "Other") + customCategories.map { it.name }
        items(categoriesToLog) { cat ->
            // Match with set limit
            val matchingBudget = budgets.find { it.category == cat }
            val budgetLimitAmountInUSD = matchingBudget?.limitAmount ?: 1000.0
            
            val budgetLimitAmountInHome = remember(budgetLimitAmountInUSD, homeCurrency, exchangeRates) {
                val rates = exchangeRates.associateBy { it.currency }
                val targetRateToUSD = rates[homeCurrency]?.rateToUSD ?: 1.0
                if (targetRateToUSD > 0.0) {
                    budgetLimitAmountInUSD / targetRateToUSD
                } else {
                    budgetLimitAmountInUSD
                }
            }

            val spentValue = expensesByCategory[cat] ?: 0.0
            val progress = if (budgetLimitAmountInHome > 0) (spentValue / budgetLimitAmountInHome).toFloat() else 0f
            val progressColor = if (progress > 1f) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedBudgetCategory = cat
                        inputLimitValue = String.format(Locale.US, "%.0f", budgetLimitAmountInHome)
                        showAdjustLimitDialog = true
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (cat == "All") "Total limit" else cat, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(imageVector = Icons.Filled.Edit, contentDescription = "Edit limit", tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), modifier = Modifier.size(12.dp))
                        }
                        Text(
                            text = "$homeCurrency ${String.format(Locale.US, "%.0f", spentValue)} of ${String.format(Locale.US, "%.0f", budgetLimitAmountInHome)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Progress Gauge
                    LinearProgressIndicator(
                        progress = progress.coerceIn(0f..1f),
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = progressColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    if (progress > 1f) {
                        Text(
                            text = "⚠️ Overspent by $homeCurrency ${String.format(Locale.US, "%.2f", spentValue - budgetLimitAmountInHome)} !",
                            fontSize = 10.sp,
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // Modal dialogue to easily change limit threshold
    BudgetAdjustDialog(
        visible = showAdjustLimitDialog,
        category = selectedBudgetCategory,
        homeCurrency = homeCurrency,
        onConfirm = { doubleVal ->
            if (doubleVal >= 0) {
                viewModel.setBudgetLimit(selectedBudgetCategory, doubleVal)
            }
            showAdjustLimitDialog = false
        },
        onDismiss = { showAdjustLimitDialog = false }
    )
}

// ==========================================
// SCREEN 4: AI ADVISOR & REPORT GENERATOR
// ==========================================
@Composable
fun AdvisorScreen(viewModel: ExpenseViewModel) {
    val reportText by viewModel.aiReport.collectAsStateWithLifecycle()
    val isReportLoading by viewModel.isReportLoading.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "Automated AI Advisor", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(text = "Consult Gemini dynamic logic on traveler paces & savings tips", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(text = "Automated Monthly Report Engine", fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(
                        text = "Parses recent transactions, active categories threshold status, and provides clear summaries based on currency conversions.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                    
                    Button(
                        onClick = { viewModel.generateAIReport() },
                        enabled = !isReportLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isReportLoading) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Consulting Gemini Cloud...")
                        } else {
                            Text("Generate AI Spending Report")
                        }
                    }
                }
            }
        }

        // Active Report display screen area
        if (reportText.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Assignment,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "AI spending report summary", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                        Text(
                            text = reportText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontFamily = FontFamily.Default,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 5: SETTINGS & BACKUP ENGINE
// ==========================================
@Composable
fun SettingsScreen(viewModel: ExpenseViewModel) {
    val homeCurrency by viewModel.homeCurrency.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val isBackingUp by viewModel.isBackingUp.collectAsStateWithLifecycle()
    val syncLogList by viewModel.allSyncLogs.collectAsStateWithLifecycle()

    var showCurrencySelector by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "Application Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(text = "Configure multi-currency anchors, biometric locks and cloud backups", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
            }
        }

        // Option 1: Home Currency Selector
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCurrencySelector = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Home Currency Anchor", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("All transaction amounts convert to this", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Text(text = homeCurrency, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Option 2: Biometric Guard Lock switch
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Biometric Shield Guard", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Prompt fingerprint lock overlay on launch", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Switch(
                        checked = biometricEnabled,
                        onCheckedChange = { viewModel.updateBiometricEnabled(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        // Option 3: Dark Mode toggle segments
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Late-Night Theme Option", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Optimize lighting for low-emission expense logging", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf("Dark Mode", "Light Mode", "System Config")
                        modes.forEachIndexed { idx, label ->
                            val isSelected = themeMode == idx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { viewModel.updateThemeMode(idx) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Option 4: Google Cloud Drive Sync Integration
        item {
            val isGoogleSignedIn by viewModel.isGoogleSignedIn.collectAsStateWithLifecycle()
            val googleAccountEmail by viewModel.googleAccountEmail.collectAsStateWithLifecycle()
            val googleAccountName by viewModel.googleAccountName.collectAsStateWithLifecycle()
            val context = androidx.compose.ui.platform.LocalContext.current
            val coroutineScope = rememberCoroutineScope()

            val signInLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    try {
                        val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                        viewModel.handleGoogleSignInSuccess(account)
                    } catch (e: Exception) {
                        android.util.Log.e("GoogleSignIn", "Sign-in failed inside settings", e)
                        android.widget.Toast.makeText(context, "Google Sign-In failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().testTag("google_drive_sync_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = "Google Drive Sync",
                                tint = if (isGoogleSignedIn) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Google Cloud Drive Sync", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (isGoogleSignedIn) "Connected to Drive AppData" else "Enable automated secure sync to private Drive",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    if (isGoogleSignedIn) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = "Active account: $googleAccountName",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Email: $googleAccountEmail",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val success = viewModel.googleDriveSyncLatest()
                                        if (success) {
                                            android.widget.Toast.makeText(context, "Cloud sync completed successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Cloud sync failed. Check connection.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).testTag("google_drive_sync_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Force Sync", fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    viewModel.triggerGoogleDriveRestore()
                                    android.widget.Toast.makeText(context, "Download cloud backup triggered...", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f).testTag("google_drive_restore_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Restore", fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                                    .build()
                                val client = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso)
                                client.signOut().addOnCompleteListener {
                                    viewModel.handleGoogleSignOut()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("google_driver_signout_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Sign out of Google Account")
                        }
                    } else {
                        Button(
                            onClick = {
                                val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                                    .requestEmail()
                                    .requestScopes(
                                        com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.appdata"),
                                        com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.file")
                                    )
                                    .build()
                                val client = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso)
                                signInLauncher.launch(client.signInIntent)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("settings_google_signin_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                        ) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign in with Google Account")
                        }
                    }
                }
            }
        }

        // Action block: Cloud Backup and hard reset
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "Secure Cloud Backup", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    
                    Button(
                        onClick = { viewModel.triggerCloudBackup() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Securing database files...")
                        } else {
                            Text("Backup Database to Secured Cloud")
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.performHardReset() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Hard reset sandbox")
                    }
                }
            }
        }

        // Option 5: Recurring Expenses Scheduler
        item {
            var showRecurringManager by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRecurringManager = true }
                    .testTag("settings_recurring_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Autorenew,
                            contentDescription = "Recurring Expenses Scheduler",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Automatic Recurring Expenses", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Automate subscriptions, utility bills or repeating incomes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open Manager",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            RecurringExpensesManagerDialog(
                visible = showRecurringManager,
                onDismiss = { showRecurringManager = false },
                viewModel = viewModel
            )
        }

        // Option 6: Custom Budget Categories Manager
        item {
            var showCustomCategoriesManager by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCustomCategoriesManager = true }
                    .testTag("settings_custom_categories_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Category,
                            contentDescription = "Manage Custom Categories",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Manage Custom Categories", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Add, select colors/icons and set budgets for categories", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open Manager",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            CustomCategoriesManagerDialog(
                visible = showCustomCategoriesManager,
                onDismiss = { showCustomCategoriesManager = false },
                viewModel = viewModel
            )
        }

        // Sync logs visual tracker list
        item {
            Text(text = "Secured Vault Sync Logs", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        if (syncLogList.isEmpty()) {
            item {
                Text(text = "No log trails stored yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        } else {
            items(syncLogList) { log ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = log.action, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            val isSuccessValue = log.status == "Success"
                            Text(
                                text = log.status,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSuccessValue) MaterialTheme.colorScheme.primary else Color.Red
                            )
                        }
                        Text(text = log.details, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }

    // Currency switching dialog options
    HomeCurrencySelectorDialog(
        visible = showCurrencySelector,
        currentHomeCurrency = homeCurrency,
        onCurrencySelected = { selectedCurrency ->
            viewModel.updateHomeCurrency(selectedCurrency)
            showCurrencySelector = false
        },
        onDismiss = { showCurrencySelector = false }
    )
}

// ==========================================
// MODAL: QUICK MANUAL ENTRY OVERLAY drawer
// ==========================================
@Composable
fun QuickEntryOverlay(
    viewModel: ExpenseViewModel,
    onDismiss: () -> Unit
) {
    val homeCurrency by viewModel.homeCurrency.collectAsStateWithLifecycle()
    val customCategories by viewModel.allCustomCategories.collectAsStateWithLifecycle()
    val customCategoryNames = remember(customCategories) { customCategories.map { it.name } }
    
    var isIncomeEntry by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(homeCurrency) }
    var category by remember { mutableStateOf("Food") }
    var merchant by remember { mutableStateOf("") }

    var showCurrencyChooserInQuick by remember { mutableStateOf(false) }

    val expenseCategories = remember(customCategoryNames) {
        listOf("Food", "Transport", "Lodging", "Entertainment", "Utilities", "Other") + customCategoryNames
    }
    val incomeCategories = listOf("Salary", "Freelance", "Investment", "Gift", "Refund", "Other")
    val categories = if (isIncomeEntry) incomeCategories else expenseCategories

    // Auto update default category when switching toggle
    LaunchedEffect(isIncomeEntry) {
        category = if (isIncomeEntry) "Salary" else "Food"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                onClick = onDismiss,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(enabled = true, onClick = {}, interactionSource = remember { MutableInteractionSource() }, indication = null) // prevent clicking through card dismissing it
                .navigationBarsPadding()
                .imePadding(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isIncomeEntry) "Log Travel Income" else "Log Travel Expense",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isIncomeEntry) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = "Close",
                        tint = Color.Gray,
                        modifier = Modifier.size(24.dp).clickable { onDismiss() }
                    )
                }

                // Type selector: Expense / Income segment
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isIncomeEntry) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { isIncomeEntry = false }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "EXPENSE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isIncomeEntry) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isIncomeEntry) Color(0xFF10B981) else Color.Transparent)
                            .clickable { isIncomeEntry = true }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "INCOME",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isIncomeEntry) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Amount Large Input field
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text(if (isIncomeEntry) "Amount received" else "Amount spent") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("quick_amount_input"),
                    singleLine = true,
                    leadingIcon = {
                        Text(
                            text = currency,
                            fontWeight = FontWeight.Bold,
                            color = if (isIncomeEntry) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .clickable { showCurrencyChooserInQuick = true }
                        )
                    }
                )

                // Title Expense/Income
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isIncomeEntry) "Description (e.g. Salary, Daily allowance)" else "What did you buy? (e.g. Coffee, Taxi)") },
                    modifier = Modifier.fillMaxWidth().testTag("quick_title_input"),
                    singleLine = true
                )

                // Merchant
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text(if (isIncomeEntry) "Payer / Source (Optional)" else "Merchant / Store (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Row for categories using standard scrolling Row which is 100% stable
                Column {
                    Text("Select Category:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { cat ->
                            val isSelected = category == cat
                            val color = if (isSelected) {
                                if (isIncomeEntry) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                            val textColor = if (isSelected) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(color)
                                    .clickable { category = cat }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = cat, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textColor)
                            }
                        }
                    }
                }

                // Add Button
                Button(
                    onClick = {
                        val amount = amountStr.toDoubleOrNull() ?: 0.0
                        if (amount > 0) {
                            viewModel.addExpense(
                                title = title,
                                amount = amount,
                                currency = currency,
                                category = category,
                                merchant = merchant,
                                notes = "",
                                isIncome = isIncomeEntry
                            )
                        }
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isIncomeEntry) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("quick_save_button")
                ) {
                    Text(if (isIncomeEntry) "Secure Log Income" else "Secure Log Expense")
                }
            }
        }
    }

    // Currency chooser inside Log Expense
    TransactionCurrencySelectorDialog(
        visible = showCurrencyChooserInQuick,
        currentCurrency = currency,
        onCurrencySelected = { selectedCurrency ->
            currency = selectedCurrency
            showCurrencyChooserInQuick = false
        },
        onDismiss = { showCurrencyChooserInQuick = false }
    )
}

// ==========================================
// LOCKSCREEN: BIOMETRIC AUTH PROTECTION
// ==========================================
@Composable
fun BiometricLockScreen(
    onUnlockSubmit: (String) -> Unit
) {
    var digits by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B19)) // Deepest Obsidian
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Shield Logo Area
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981).copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = "Shield Locked",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "DATA VAULT PROTECTED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981), letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "App Locked for Privacy", fontSize = 14.sp, color = Color(0xFF94A3B8))
        }

        // Indicator Bubbles for digits
        Row(
            modifier = Modifier.wrapContentSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            for (i in 0..3) {
                val filled = digits.length > i
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (filled) Color(0xFF10B981) else Color.DarkGray)
                )
            }
        }

        // Keypad Pin lock Simulation with nice biometric layout
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("Fingerprint", "0", "Del")
            )

            keys.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { k ->
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(if (k == "Fingerprint" || k == "Del") Color.Transparent else Color(0xFF151B33))
                                .clickable {
                                    when (k) {
                                        "Del" -> if (digits.isNotEmpty()) digits = digits.dropLast(1)
                                        "Fingerprint" -> {
                                            // Simulated Fingerprint Scan success directly!
                                            onUnlockSubmit("1234")
                                        }
                                        else -> {
                                            if (digits.length < 4) {
                                                digits += k
                                                if (digits.length == 4) {
                                                    onUnlockSubmit(digits)
                                                    digits = "" // reset
                                                }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (k == "Fingerprint") {
                                Icon(
                                    imageVector = Icons.Filled.Fingerprint,
                                    contentDescription = "",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(36.dp)
                                )
                            } else if (k == "Del") {
                                Icon(
                                    imageVector = Icons.Filled.Backspace,
                                    contentDescription = "",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Text(text = k, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SECURED GOOGLE LOGIN SCREEN
// ==========================================
@Composable
fun GoogleLoginScreen(
    viewModel: ExpenseViewModel,
    onContinueOffline: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isBackingUp by viewModel.isBackingUp.collectAsStateWithLifecycle()

    val signInLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                viewModel.handleGoogleSignInSuccess(account)
            } catch (e: Exception) {
                android.util.Log.e("GoogleSignIn", "Login failed on launch", e)
                android.widget.Toast.makeText(context, "Google Authorization failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    )
                )
            )
            .padding(24.dp)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Identity Brand Icon Accent
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "SpendWise Travel",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "Secure Cloud Sync & Travel Ledger",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Premium benefits layout card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp).padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Automatic Drive Backup", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Your transaction logs are automatically backed up live to your personal private Google Drive.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }

                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp).padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Instant Sync Restore", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Restore your full travel portfolio instantly upon logging in on any other android hardware.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }

                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(20.dp).padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Record Income & Expense", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Keep track of multi-currency travel allowances, freelance pay-ins, and expenditures simultaneously.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isBackingUp) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text(
                    text = "Checking and downloading existing cloud database copies...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            } else {
                Button(
                    onClick = {
                        val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .requestScopes(
                                com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.appdata"),
                                com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.file")
                            )
                            .build()
                        val client = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(context, gso)
                        signInLauncher.launch(client.signInIntent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("google_login_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign in with Google Account", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onContinueOffline,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("offline_skip_button"),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f))
                ) {
                    Text("Continue to Personal Sandbox (Offline)", fontSize = 13.sp)
                }
            }
        }
    }
}

// ==========================================
// SEPARATED DIALOG COMPOSABLES (REDUCES COMPOSITION SCOPE AND IME ISSUES)
// ==========================================

@Composable
fun HomeCurrencySelectorDialog(
    visible: Boolean,
    currentHomeCurrency: String,
    onCurrencySelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var currencySearchQuery by remember { mutableStateOf("") }
    val currencyOpts = remember(currencySearchQuery) {
        val list = CountryCurrencyData.countries
        if (currencySearchQuery.isBlank()) {
            list
        } else {
            list.filter {
                it.first.contains(currencySearchQuery, ignoreCase = true) ||
                it.second.contains(currencySearchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Primary Home Currency Anchor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = currencySearchQuery,
                    onValueChange = { currencySearchQuery = it },
                    placeholder = { Text("Search country or currency...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (currencySearchQuery.isNotEmpty()) {
                            IconButton(onClick = { currencySearchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                    items(currencyOpts) { cur ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCurrencySelected(cur.second)
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "${cur.first} (${cur.second})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (cur.second == currentHomeCurrency) {
                                Icon(imageVector = Icons.Filled.Check, contentDescription = "", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (currencyOpts.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Text("No matches found", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun TransactionCurrencySelectorDialog(
    visible: Boolean,
    currentCurrency: String,
    onCurrencySelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var currencySearchQuery by remember { mutableStateOf("") }
    val curList = remember(currencySearchQuery) {
        val list = CountryCurrencyData.countries
        if (currencySearchQuery.isBlank()) {
            list
        } else {
            list.filter {
                it.first.contains(currencySearchQuery, ignoreCase = true) ||
                it.second.contains(currencySearchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Transaction Currency") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = currencySearchQuery,
                    onValueChange = { currencySearchQuery = it },
                    placeholder = { Text("Search country or currency...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (currencySearchQuery.isNotEmpty()) {
                            IconButton(onClick = { currencySearchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                    items(curList) { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCurrencySelected(opt.second)
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "${opt.first} (${opt.second})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (opt.second == currentCurrency) {
                                Icon(imageVector = Icons.Filled.Check, contentDescription = "", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (curList.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Text("No matches found", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}

@Composable
fun BudgetAdjustDialog(
    visible: Boolean,
    category: String,
    homeCurrency: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var inputLimitValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Adjust limit for $category") },
        text = {
            OutlinedTextField(
                value = inputLimitValue,
                onValueChange = { inputLimitValue = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { Text("Enter threshold limit") },
                singleLine = true,
                prefix = { Text("$homeCurrency ") }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val doubleVal = inputLimitValue.toDoubleOrNull() ?: 0.0
                onConfirm(doubleVal)
            }) {
                Text("Save Threshold")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// RECURRING EXPENSES SCHEDULER & MANAGER DIALOGS
// ==========================================

@Composable
fun RecurringExpensesManagerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    viewModel: ExpenseViewModel
) {
    if (!visible) return

    val recurringExpenses by viewModel.allRecurringExpenses.collectAsStateWithLifecycle()
    val homeCurrency by viewModel.homeCurrency.collectAsStateWithLifecycle()

    var showAddRecurringSheet by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Recurring Expenses",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Manage automated interval transactions",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close Dialog")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { showAddRecurringSheet = true },
                        modifier = Modifier.weight(1f).testTag("button_add_recurring"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Schedule New", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Schedule New", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { viewModel.triggerRecurringCheck() },
                        modifier = Modifier.weight(1f).testTag("button_run_auto_log"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Run Check", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Force Run Check", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Recurring items list
                if (recurringExpenses.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Autorenew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "No recurring expenses set up yet",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                            Text(
                                text = "Set up weekly subscriptions, monthly rent or daily utilities.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(recurringExpenses, key = { it.id }) { item ->
                            val sdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
                            
                            // Let's compute estimated next logging date
                            val nextCal = remember(item.lastLoggedDate, item.startDate, item.interval) {
                                Calendar.getInstance().apply {
                                    if (item.lastLoggedDate == 0L) {
                                        timeInMillis = item.startDate
                                    } else {
                                        timeInMillis = item.lastLoggedDate
                                        when (item.interval.lowercase(Locale.US)) {
                                            "daily" -> add(Calendar.DAY_OF_YEAR, 1)
                                            "weekly" -> add(Calendar.WEEK_OF_YEAR, 1)
                                            "monthly" -> add(Calendar.MONTH, 1)
                                            "yearly" -> add(Calendar.YEAR, 1)
                                        }
                                    }
                                }
                            }

                            val nextRunFormatted = sdf.format(nextCal.time)
                            val isEndingPassed = item.endDate != null && System.currentTimeMillis() > item.endDate

                            Card(
                                modifier = Modifier.fillMaxWidth().testTag("recurring_item_${item.id}"),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = item.title,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = item.interval,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${item.category} • Start: ${sdf.format(Date(item.startDate))}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (item.endDate != null) {
                                                Text(
                                                    text = "Ends: ${sdf.format(Date(item.endDate))}" + if (isEndingPassed) " (Expired)" else "",
                                                    fontSize = 11.sp,
                                                    color = if (isEndingPassed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${item.currency} ${String.format(Locale.getDefault(), "%,.2f", item.amount)}",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (item.isIncome) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            IconButton(
                                                onClick = { viewModel.deleteRecurringExpense(item) },
                                                modifier = Modifier.size(28.dp).testTag("delete_recurring_btn_${item.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete automated item",
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (item.lastLoggedDate == 0L) "Pending first log run" else "Last triggered: ${sdf.format(Date(item.lastLoggedDate))}",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                        )

                                        if (!isEndingPassed) {
                                            Text(
                                                text = "Next execution: $nextRunFormatted",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddRecurringSheet) {
        AddRecurringExpenseDialog(
            visible = showAddRecurringSheet,
            viewModel = viewModel,
            onDismiss = { showAddRecurringSheet = false },
            onSave = { title, amt, curr, cat, interval, sDate, eDate, isInc ->
                viewModel.saveRecurringExpense(
                    title = title,
                    amount = amt,
                    currency = curr,
                    category = cat,
                    interval = interval,
                    startDate = sDate,
                    endDate = eDate,
                    isIncome = isInc
                )
                showAddRecurringSheet = false
            }
        )
    }
}

@Composable
fun AddRecurringExpenseDialog(
    visible: Boolean,
    viewModel: ExpenseViewModel,
    onDismiss: () -> Unit,
    onSave: (String, Double, String, String, String, Long, Long?, Boolean) -> Unit
) {
    if (!visible) return

    val customCategories by viewModel.allCustomCategories.collectAsStateWithLifecycle()
    val customCategoryNames = remember(customCategories) { customCategories.map { it.name } }

    var title by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    var isIncome by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("Food") }
    var interval by remember { mutableStateOf("Monthly") }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    
    val todayCal = remember { Calendar.getInstance() }
    var startDate by remember { mutableStateOf(todayCal.timeInMillis) }
    var endDate by remember { mutableStateOf<Long?>(null) }
    var hasEndDate by remember { mutableStateOf(false) }

    var showCurrencyChooser by remember { mutableStateOf(false) }

    val expenseCategories = remember(customCategoryNames) {
        listOf("Food", "Transport", "Lodging", "Entertainment", "Utilities", "Other") + customCategoryNames
    }
    val incomeCategories = listOf("Salary", "Freelance", "Investment", "Gift", "Refund", "Other")
    val activeCategories = if (isIncome) incomeCategories else expenseCategories

    // Auto update category if current selection is not valid for new isIncome state
    LaunchedEffect(isIncome) {
        if (!activeCategories.contains(category)) {
            category = activeCategories.first()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Schedule Automated Transaction",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // Transaction Type Segments (Expense vs Income)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(false to "Expense", true to "Income").forEach { (typeVal, label) ->
                        val isSelected = isIncome == typeVal
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) {
                                        if (typeVal) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer
                                    } else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) {
                                        if (typeVal) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                                    } else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { isIncome = typeVal }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) {
                                    if (typeVal) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                                } else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Title TextInput
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title / Purpose") },
                    placeholder = { Text("Rent, Netflix Subscription, Salary...") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("add_rec_title_input")
                )

                // Amount TextInput & Currency Picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it },
                        label = { Text("Amount") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1.5f).testTag("add_rec_amount_input")
                    )

                    // Currency Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .clickable { showCurrencyChooser = true }
                            .padding(vertical = 16.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = currency, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Repeat Interval Trigger selection
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("REPEATING INTERVAL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Daily", "Weekly", "Monthly", "Yearly").forEach { itemInterval ->
                            val isSelected = interval == itemInterval
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { interval = itemInterval }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = itemInterval,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Category selection dropdown UI
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CATEGORY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        activeCategories.forEach { catOption ->
                            val isSelected = category == catOption
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .border(1.dp, if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                    .clickable { category = catOption }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = catOption,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Start Date & Optional End date picker buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Start Date Button
                    OutlinedButton(
                        onClick = {
                            val calendar = Calendar.getInstance().apply { timeInMillis = startDate }
                            android.app.DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    val newDate = Calendar.getInstance().apply {
                                        set(Calendar.YEAR, year)
                                        set(Calendar.MONTH, month)
                                        set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                    }
                                    startDate = newDate.timeInMillis
                                },
                                calendar.get(Calendar.YEAR),
                                calendar.get(Calendar.MONTH),
                                calendar.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("START DATE", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            Text(text = sdf.format(Date(startDate)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Optional End Date Button
                    OutlinedButton(
                        onClick = {
                            if (!hasEndDate) {
                                hasEndDate = true
                                val calendar = Calendar.getInstance()
                                android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        val newDate = Calendar.getInstance().apply {
                                            set(Calendar.YEAR, year)
                                            set(Calendar.MONTH, month)
                                            set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                        }
                                        endDate = newDate.timeInMillis
                                    },
                                    calendar.get(Calendar.YEAR),
                                    calendar.get(Calendar.MONTH),
                                    calendar.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            } else {
                                val calendar = Calendar.getInstance().apply { timeInMillis = endDate ?: System.currentTimeMillis() }
                                android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        val newDate = Calendar.getInstance().apply {
                                            set(Calendar.YEAR, year)
                                            set(Calendar.MONTH, month)
                                            set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                        }
                                        endDate = newDate.timeInMillis
                                    },
                                    calendar.get(Calendar.YEAR),
                                    calendar.get(Calendar.MONTH),
                                    calendar.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (hasEndDate) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("END DATE", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            Text(
                                text = if (hasEndDate && endDate != null) sdf.format(Date(endDate!!)) else "No End Date",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // If end date is enabled, show option to clear/remove it
                if (hasEndDate) {
                    TextButton(
                        onClick = {
                            hasEndDate = false
                            endDate = null
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Remove end date restriction", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Confirm / Save Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val doubleAmt = amountStr.toDoubleOrNull() ?: 0.0
                            if (title.isBlank()) {
                                android.widget.Toast.makeText(context, "Please write a title", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (doubleAmt <= 0) {
                                android.widget.Toast.makeText(context, "Amount must be greater than zero", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                onSave(
                                    title,
                                    doubleAmt,
                                    currency,
                                    category,
                                    interval,
                                    startDate,
                                    if (hasEndDate) endDate else null,
                                    isIncome
                                )
                            }
                        },
                        modifier = Modifier.weight(1.5f).testTag("add_rec_save_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Enable Automation", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showCurrencyChooser) {
        TransactionCurrencySelectorDialog(
            visible = showCurrencyChooser,
            currentCurrency = currency,
            onCurrencySelected = { selectedCurrency ->
                currency = selectedCurrency
                showCurrencyChooser = false
            },
            onDismiss = { showCurrencyChooser = false }
        )
    }
}

// ==========================================
// CUSTOM CATEGORIES HELPER FUNCTIONS
// ==========================================

fun getCustomIcon(iconName: String): ImageVector {
    return when (iconName) {
        "Fastfood" -> Icons.Filled.Fastfood
        "DirectionsCar" -> Icons.Filled.DirectionsCar
        "Hotel" -> Icons.Filled.Hotel
        "LocalActivity" -> Icons.Filled.LocalActivity
        "Power" -> Icons.Filled.Power
        "ShoppingBag" -> Icons.Filled.ShoppingBag
        "School" -> Icons.Filled.School
        "MedicalServices" -> Icons.Filled.MedicalServices
        "FitnessCenter" -> Icons.Filled.FitnessCenter
        "Pets" -> Icons.Filled.Pets
        "Flight" -> Icons.Filled.Flight
        "Home" -> Icons.Filled.Home
        "LocalCafe" -> Icons.Filled.LocalCafe
        "Build" -> Icons.Filled.Build
        "ReceiptLong" -> Icons.Filled.ReceiptLong
        else -> Icons.Filled.ReceiptLong
    }
}

fun parseColorHex(colorHex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(colorHex))
    } catch (_: Exception) {
        Color(0xFF3B82F6) // default blue fallback
    }
}

@Composable
fun getCategoryIconAndColor(
    category: String,
    isIncome: Boolean,
    customCategories: List<CustomCategory>
): Pair<ImageVector, Color> {
    if (isIncome) {
        return Pair(Icons.Filled.TrendingUp, Color(0xFF10B981))
    }
    
    val matchingCustom = customCategories.find { it.name.equals(category, ignoreCase = true) }
    if (matchingCustom != null) {
        val color = parseColorHex(matchingCustom.colorHex)
        val icon = getCustomIcon(matchingCustom.iconName)
        return Pair(icon, color)
    }

    return when (category) {
        "Food" -> Pair(Icons.Filled.Restaurant, Color(0xFFEF4444))
        "Transport" -> Pair(Icons.Filled.DirectionsCar, Color(0xFF3B82F6))
        "Lodging" -> Pair(Icons.Filled.Hotel, Color(0xFF8B5CF6))
        "Entertainment" -> Pair(Icons.Filled.LocalActivity, Color(0xFFF59E0B))
        "Utilities" -> Pair(Icons.Filled.Power, Color(0xFF10B981))
        "Other" -> Pair(Icons.Filled.ReceiptLong, Color(0xFF64748B))
        else -> Pair(Icons.Filled.ReceiptLong, MaterialTheme.colorScheme.primary)
    }
}

// ==========================================
// CUSTOM CATEGORIES MANAGER DIALOG
// ==========================================

@Composable
fun CustomCategoriesManagerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    viewModel: ExpenseViewModel
) {
    if (!visible) return

    val customCategories by viewModel.allCustomCategories.collectAsStateWithLifecycle()
    val currentBudgets by viewModel.currentBudgets.collectAsStateWithLifecycle()
    val homeCurrency by viewModel.homeCurrency.collectAsStateWithLifecycle()
    val exchangeRates by viewModel.allRates.collectAsStateWithLifecycle()

    var editingCategory by remember { mutableStateOf<CustomCategory?>(null) }
    var showAddEditDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Custom Categories",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Personalize colors, icons and custom limits",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("custom_cat_close_dialog")) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close Dialog")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions
                Button(
                    onClick = {
                        editingCategory = null
                        showAddEditDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().testTag("button_create_custom_category"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Category", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Create Custom Category", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "CATEGORIES LIST",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // List
                val standardList = listOf("Food", "Transport", "Lodging", "Entertainment", "Utilities", "Other")

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Standard Non-Deletable Categories (helpful reference)
                    item {
                        Text(
                            text = "Default / Built-In Categories",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(standardList) { name ->
                        val (icon, color) = getCategoryIconAndColor(name, false, emptyList())
                        val matchingBudget = currentBudgets.find { it.category.equals(name, ignoreCase = true) }
                        val budgetLimitAmountInUSD = matchingBudget?.limitAmount ?: 0.0
                        val budgetInHome = remember(budgetLimitAmountInUSD, homeCurrency, exchangeRates) {
                            val rates = exchangeRates.associateBy { it.currency }
                            val targetRateToUSD = rates[homeCurrency]?.rateToUSD ?: 1.0
                            if (targetRateToUSD > 0.0) budgetLimitAmountInUSD / targetRateToUSD else budgetLimitAmountInUSD
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(
                                        modifier = Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(text = name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                        if (budgetInHome > 0) {
                                            Text(
                                                text = "Budget: $homeCurrency ${String.format(Locale.US, "%.0f", budgetInHome)}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Text(text = "No custom budget limit", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                        }
                                    }
                                }

                                Text(
                                    text = "System",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                    }

                    // Custom User Categories
                    item {
                        Text(
                            text = "Custom User-Created Categories",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }

                    if (customCategories.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No custom categories. Click button above to create!",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }
                    } else {
                        items(customCategories, key = { it.id }) { cat ->
                            val (icon, color) = getCategoryIconAndColor(cat.name, false, customCategories)
                            val matchingBudget = currentBudgets.find { it.category.equals(cat.name, ignoreCase = true) }
                            val budgetLimitAmountInUSD = matchingBudget?.limitAmount ?: 0.0
                            val budgetInHome = remember(budgetLimitAmountInUSD, homeCurrency, exchangeRates) {
                                val rates = exchangeRates.associateBy { it.currency }
                                val targetRateToUSD = rates[homeCurrency]?.rateToUSD ?: 1.0
                                if (targetRateToUSD > 0.0) budgetLimitAmountInUSD / targetRateToUSD else budgetLimitAmountInUSD
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth().testTag("custom_cat_item_${cat.id}"),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Box(
                                            modifier = Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(text = cat.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                            if (budgetInHome > 0) {
                                                Text(
                                                    text = "Budget: $homeCurrency ${String.format(Locale.US, "%.0f", budgetInHome)}",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            } else {
                                                Text(text = "No custom budget limit", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                            }
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                editingCategory = cat
                                                showAddEditDialog = true
                                            },
                                            modifier = Modifier.size(32.dp).testTag("custom_cat_edit_btn_${cat.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit Category",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { viewModel.deleteCustomCategory(cat) },
                                            modifier = Modifier.size(32.dp).testTag("custom_cat_delete_btn_${cat.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete Category",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddEditDialog) {
        val initialLimit = remember(editingCategory, currentBudgets) {
            val matching = editingCategory?.let { ec -> currentBudgets.find { it.category == ec.name } }
            matching?.limitAmount ?: 0.0
        }
        val initialLimitInHome = remember(initialLimit, homeCurrency, exchangeRates) {
            val rates = exchangeRates.associateBy { it.currency }
            val targetRateToUSD = rates[homeCurrency]?.rateToUSD ?: 1.0
            if (targetRateToUSD > 0.0) initialLimit / targetRateToUSD else initialLimit
        }

        AddEditCustomCategoryDialog(
            visible = showAddEditDialog,
            categoryToEdit = editingCategory,
            initialBudgetLimit = initialLimitInHome,
            onDismiss = { showAddEditDialog = false },
            viewModel = viewModel,
            onSave = { name, icon, color, optBudget ->
                if (editingCategory == null) {
                    viewModel.saveCustomCategory(name, icon, color)
                    if (optBudget != null) {
                        viewModel.setBudgetLimit(name, optBudget)
                    }
                } else {
                    val prevName = editingCategory!!.name
                    val updated = editingCategory!!.copy(name = name, iconName = icon, colorHex = color)
                    viewModel.updateCustomCategory(updated)
                    if (optBudget != null) {
                        viewModel.setBudgetLimit(name, optBudget)
                    }
                }
                showAddEditDialog = false
            }
        )
    }
}

// ==========================================
// ADD OR EDIT CUSTOM CATEGORY DIALOG
// ==========================================

@Composable
fun AddEditCustomCategoryDialog(
    visible: Boolean,
    categoryToEdit: CustomCategory?,
    initialBudgetLimit: Double,
    onDismiss: () -> Unit,
    viewModel: ExpenseViewModel,
    onSave: (String, String, String, Double?) -> Unit
) {
    if (!visible) return

    val homeCurrency by viewModel.homeCurrency.collectAsStateWithLifecycle()
    val customCategories by viewModel.allCustomCategories.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf(categoryToEdit?.name ?: "") }
    var selectedIcon by remember { mutableStateOf(categoryToEdit?.iconName ?: "ShoppingBag") }
    var selectedColor by remember { mutableStateOf(categoryToEdit?.colorHex ?: "#3B82F6") }
    var budgetLimitStr by remember { mutableStateOf(if (initialBudgetLimit > 0) String.format(Locale.US, "%.0f", initialBudgetLimit) else "") }

    val context = androidx.compose.ui.platform.LocalContext.current

    val iconsList = listOf(
        "ShoppingBag", "Fastfood", "DirectionsCar", "Hotel", "LocalActivity",
        "Power", "School", "MedicalServices", "FitnessCenter", "Pets",
        "Flight", "Home", "LocalCafe", "Build", "ReceiptLong"
    )

    val colorsList = listOf(
        "#3B82F6", "#EF4444", "#10B981", "#F59E0B", "#8B5CF6",
        "#14B8A6", "#EC4899", "#6366F1", "#06B6D4", "#84CC16",
        "#64748B", "#EC4899", "#4B5563"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (categoryToEdit == null) "Add Custom Category" else "Edit Category",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // Category Name input
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category Name") },
                    placeholder = { Text("Rent, Taxes, Health, Gym...") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("add_custom_cat_name_input")
                )

                // Option 1: Pick Icon
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CHOOSE ICON", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        iconsList.forEach { iconName ->
                            val isSelected = selectedIcon == iconName
                            val iconVector = getCustomIcon(iconName)
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .border(2.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable { selectedIcon = iconName }
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = iconVector,
                                    contentDescription = iconName,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // Option 2: Pick Color
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CHOOSE COLOR ACCENT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        colorsList.forEach { hex ->
                            val isSelected = selectedColor == hex
                            val brushColor = parseColorHex(hex)
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(brushColor)
                                    .border(3.dp, if (isSelected) MaterialTheme.colorScheme.outline else Color.Transparent, CircleShape)
                                    .clickable { selectedColor = hex }
                            )
                        }
                    }
                }

                // Option 3: Optional Budget Threshold Link
                OutlinedTextField(
                    value = budgetLimitStr,
                    onValueChange = { budgetLimitStr = it },
                    label = { Text("Monthly Budget Limit (Optional)") },
                    placeholder = { Text("Set spending limit threshold") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    prefix = { Text("$homeCurrency ") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("add_custom_cat_budget_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val cleanName = name.trim()
                            val budgetLimit = budgetLimitStr.toDoubleOrNull()
                            val isDuplicate = customCategories.any {
                                it.name.equals(cleanName, ignoreCase = true) && it.id != (categoryToEdit?.id ?: -1)
                            } || listOf("Food", "Transport", "Lodging", "Entertainment", "Utilities", "Other", "All").any {
                                it.equals(cleanName, ignoreCase = true)
                            }

                            if (cleanName.isBlank()) {
                                android.widget.Toast.makeText(context, "Please enter a category name", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (isDuplicate) {
                                android.widget.Toast.makeText(context, "Category name already exists", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                onSave(cleanName, selectedIcon, selectedColor, budgetLimit)
                            }
                        },
                        modifier = Modifier.weight(1.5f).testTag("custom_cat_save_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (categoryToEdit == null) "Create" else "Save Changes", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

