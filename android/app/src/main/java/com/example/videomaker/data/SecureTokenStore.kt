package com.example.videomaker.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureTokenStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "video_maker_secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun readApiToken(): String = preferences.getString(API_TOKEN_KEY, "").orEmpty()

    fun writeApiToken(value: String) {
        preferences.edit().putString(API_TOKEN_KEY, value.trim()).apply()
    }

    private companion object {
        const val API_TOKEN_KEY = "api_token"
    }
}
