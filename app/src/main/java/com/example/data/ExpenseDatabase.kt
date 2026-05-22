package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.Update
import androidx.room.Delete
import androidx.room.Query
import androidx.room.OnConflictStrategy
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// --- Room Entities ---

@Entity(tableName = "custom_categories")
data class CustomCategory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val iconName: String,             // e.g., "ShoppingBag", "Fastfood", "DirectionsCar"
    val colorHex: String              // e.g., "#3B82F6"
)

@Entity(tableName = "recurring_expenses")
data class RecurringExpense(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val amount: Double,
    val currency: String,             // e.g. "USD", "EUR", "GBP"
    val category: String,             // Food, Transport, Lodging, Entertainment, Utilities, Other, etc.
    val merchant: String = "",
    val notes: String = "",
    val interval: String,             // e.g., "Daily", "Weekly", "Monthly", "Yearly"
    val startDate: Long,              // Timestamp of the first occurrence in millis
    val endDate: Long? = null,        // Optional far future end date
    val lastLoggedDate: Long = 0L,    // Timestamp of when it was last logged
    val isIncome: Boolean = false
)

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val amount: Double,
    val currency: String,             // e.g. "USD", "EUR", "GBP"
    val baseAmount: Double,           // Converted amount in USD (absolute rate anchor)
    val baseCurrency: String,         // Anchored base currency (always "USD")
    val exchangeRateToUSD: Double,    // Rate used: 1 Foreign Unit = X USD
    val category: String,             // Food, Transport, Lodging, Entertainment, Utilities, Other
    val date: Long,                   // Timestamp in millis
    val merchant: String = "",
    val notes: String = "",
    val scannedViaOcr: Boolean = false,
    val receiptImgUri: String? = null,
    val isIncome: Boolean = false
)

@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val category: String,             // e.g., "Food", "Transport", "All" for overall budget
    val limitAmount: Double,          // in base currency (USD)
    val monthYear: String             // e.g., "2026-05"
)

@Entity(tableName = "exchange_rates")
data class ExchangeRate(
    @PrimaryKey val currency: String,  // e.g., "USD", "EUR", "GBP", "JPY", "CAD", "INR"
    val rateToUSD: Double,            // rate to USD (1 Foreign Unit = X USD. e.g. 1 EUR = 1.08 USD, 1 JPY = 0.0064 USD)
    val lastUpdated: Long
)

@Entity(tableName = "sync_logs")
data class SyncLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val action: String,               // "Cloud Backup", "Sync Rates", "Biometric Lock", "Settings Reset"
    val status: String,               // "Success", "Failed"
    val details: String
)

// --- DAOs (Data Access Objects) ---

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllExpenses(): Flow<List<Expense>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense): Long

    @Update
    suspend fun updateExpense(expense: Expense)

    @Delete
    suspend fun deleteExpense(expense: Expense)

    @Query("DELETE FROM expenses")
    suspend fun clearAllExpenses()
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE monthYear = :monthYear")
    fun getBudgetsForMonth(monthYear: String): Flow<List<Budget>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudget(budget: Budget)

    @Query("DELETE FROM budgets WHERE monthYear = :monthYear")
    suspend fun deleteBudgetsForMonth(monthYear: String)

    @Query("DELETE FROM budgets")
    suspend fun clearAllBudgets()
}

@Dao
interface ExchangeRateDao {
    @Query("SELECT * FROM exchange_rates")
    fun getAllRates(): Flow<List<ExchangeRate>>

    @Query("SELECT * FROM exchange_rates")
    suspend fun getAllRatesSnapshot(): List<ExchangeRate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRates(rates: List<ExchangeRate>)

    @Query("SELECT * FROM exchange_rates WHERE currency = :currency LIMIT 1")
    suspend fun getRateForCurrency(currency: String): ExchangeRate?
}

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC")
    fun getAllSyncLogs(): Flow<List<SyncLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncLog(log: SyncLog)

    @Query("DELETE FROM sync_logs")
    suspend fun clearAllLogs()
}

@Dao
interface RecurringExpenseDao {
    @Query("SELECT * FROM recurring_expenses ORDER BY startDate DESC")
    fun getAllRecurringExpenses(): Flow<List<RecurringExpense>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecurringExpense(recurringExpense: RecurringExpense): Long

    @Update
    suspend fun updateRecurringExpense(recurringExpense: RecurringExpense)

    @Delete
    suspend fun deleteRecurringExpense(recurringExpense: RecurringExpense)

    @Query("DELETE FROM recurring_expenses")
    suspend fun clearAllRecurringExpenses()
}

@Dao
interface CustomCategoryDao {
    @Query("SELECT * FROM custom_categories ORDER BY name ASC")
    fun getAllCustomCategories(): Flow<List<CustomCategory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomCategory(category: CustomCategory): Long

    @Update
    suspend fun updateCustomCategory(category: CustomCategory)

    @Delete
    suspend fun deleteCustomCategory(category: CustomCategory)

    @Query("DELETE FROM custom_categories")
    suspend fun clearAllCustomCategories()
}

// --- App Room Database ---

@Database(
    entities = [Expense::class, Budget::class, ExchangeRate::class, SyncLog::class, RecurringExpense::class, CustomCategory::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun budgetDao(): BudgetDao
    abstract fun exchangeRateDao(): ExchangeRateDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun recurringExpenseDao(): RecurringExpenseDao
    abstract fun customCategoryDao(): CustomCategoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "expenses_tracker_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
