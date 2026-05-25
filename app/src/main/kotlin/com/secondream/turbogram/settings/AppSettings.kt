package com.secondream.turbogram.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "telegram_light_prefs")

data class ApiConfig(val apiId: Int, val apiHash: String)

object AppSettings {
    private lateinit var appContext: Context

    private val API_ID = intPreferencesKey("api_id")
    private val API_HASH = stringPreferencesKey("api_hash")

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    val apiConfig: Flow<ApiConfig>
        get() = appContext.dataStore.data.map { prefs ->
            ApiConfig(
                apiId = prefs[API_ID] ?: 0,
                apiHash = prefs[API_HASH] ?: ""
            )
        }

    suspend fun setApiConfig(apiId: Int, apiHash: String) {
        appContext.dataStore.edit { prefs ->
            prefs[API_ID] = apiId
            prefs[API_HASH] = apiHash
        }
    }
}
