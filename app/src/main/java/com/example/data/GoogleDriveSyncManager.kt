package com.example.data

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GoogleDriveSyncManager {
    private const val TAG = "GoogleDriveSync"
    private const val BACKUP_FILE_NAME = "spendwise_google_drive_backup_v2.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Obtains a valid OAuth2 Access Token for Drive AppData scope.
     * Note: This must be called from a background thread.
     */
    fun getAccessToken(context: Context): String? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return try {
            // Retrieve token with the correct oauth2 scope pattern for Drive AppData
            val scope = "oauth2:https://www.googleapis.com/auth/drive.appdata https://www.googleapis.com/auth/drive.file email profile openid"
            GoogleAuthUtil.getToken(context, account.account!!, scope)
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining access token", e)
            null
        }
    }

    /**
     * Checks if the user is signed in to Google.
     */
    fun isUserSignedIn(context: Context): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    /**
     * Backs up current records to Google Drive.
     */
    suspend fun performCloudSync(context: Context, expenses: List<Expense>, budgets: List<Budget>): Boolean {
        if (!isUserSignedIn(context)) return false

        return with(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val token = getAccessToken(context) ?: return@with false

                // 1. Serialize DB into JSON
                val backupRoot = JSONObject()
                val expensesArr = JSONArray()
                for (e in expenses) {
                    expensesArr.put(JSONObject()
                        .put("id", e.id)
                        .put("title", e.title)
                        .put("amount", e.amount)
                        .put("currency", e.currency)
                        .put("baseAmount", e.baseAmount)
                        .put("category", e.category)
                        .put("date", e.date)
                        .put("merchant", e.merchant)
                        .put("notes", e.notes)
                        .put("isIncome", e.isIncome)
                    )
                }
                backupRoot.put("expenses", expensesArr)

                val budgetsArr = JSONArray()
                for (b in budgets) {
                    budgetsArr.put(JSONObject()
                        .put("category", b.category)
                        .put("limitAmount", b.limitAmount)
                        .put("monthYear", b.monthYear)
                    )
                }
                backupRoot.put("budgets", budgetsArr)
                backupRoot.put("syncTime", System.currentTimeMillis())

                val jsonContent = backupRoot.toString()

                // 2. Query if file exists on Google Drive
                val fileId = findBackupFileId(token)

                val success = if (fileId != null) {
                    // Update existing backup
                    updateBackupFile(token, fileId, jsonContent)
                } else {
                    // Create new backup file
                    val newId = createBackupFileMetadata(token)
                    if (newId != null) {
                        updateBackupFile(token, newId, jsonContent)
                    } else {
                        false
                    }
                }

                success
            } catch (e: Exception) {
                Log.e(TAG, "Sync error in background task", e)
                false
            }
        }
    }

    /**
     * Restores records from Google Drive. Returns the JSON String if successful, or null.
     */
    suspend fun downloadBackupFromDrive(context: Context): String? {
        if (!isUserSignedIn(context)) return null

        return with(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val token = getAccessToken(context) ?: return@with null
                val fileId = findBackupFileId(token) ?: return@with null

                val request = Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()
                    } else {
                        Log.e(TAG, "Failed downloading file: ${response.code}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading backup", e)
                null
            }
        }
    }

    private fun findBackupFileId(token: String): String? {
        try {
            // Find our file in appDataFolder spaces
            val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name%3D'$BACKUP_FILE_NAME'%20and%20trashed%3Dfalse"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseStr = response.body?.string() ?: return null
                    val json = JSONObject(responseStr)
                    val files = json.getJSONArray("files")
                    if (files.length() > 0) {
                        return files.getJSONObject(0).getString("id")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findBackupFileId error", e)
        }
        return null
    }

    private fun createBackupFileMetadata(token: String): String? {
        try {
            val url = "https://www.googleapis.com/drive/v3/files"
            val metadata = JSONObject()
                .put("name", BACKUP_FILE_NAME)
                .put("parents", JSONArray().put("appDataFolder"))

            val body = metadata.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseStr = response.body?.string() ?: return null
                    val json = JSONObject(responseStr)
                    return json.getString("id")
                } else {
                    Log.e(TAG, "Failed to create metadata: ${response.code} ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "createBackupFileMetadata error", e)
        }
        return null
    }

    private fun updateBackupFile(token: String, fileId: String, content: String): Boolean {
        try {
            val url = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
            val body = content.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .patch(body)
                .build()

            client.newCall(request).execute().use { response ->
                return response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateBackupFile content upload error", e)
        }
        return false
    }
}
