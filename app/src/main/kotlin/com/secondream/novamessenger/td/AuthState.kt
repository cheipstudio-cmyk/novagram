package com.secondream.novamessenger.td

sealed class AuthState {
    data object Initial : AuthState()
    data object NeedApiConfig : AuthState()
    data object WaitParameters : AuthState()
    data object WaitPhoneNumber : AuthState()
    data class WaitCode(val codeHint: String? = null) : AuthState()
    data class WaitPassword(val hint: String? = null) : AuthState()
    data object Ready : AuthState()
    data object LoggingOut : AuthState()
    data object Closed : AuthState()
    data class Error(val message: String) : AuthState()
}
