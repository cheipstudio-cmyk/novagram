package com.secondream.novagram.td

sealed class AuthState {
    data object Initial : AuthState()
    data object NeedApiConfig : AuthState()
    data object WaitParameters : AuthState()
    data object WaitPhoneNumber : AuthState()
    data class WaitCode(val codeHint: String? = null, val viaTelegram: Boolean = false) : AuthState()
    data class WaitPassword(val hint: String? = null) : AuthState()
    /** No Telegram account on this number yet — TDLib wants a first/last
     *  name to create one. Reached after the code step for a brand-new user. */
    data object WaitRegistration : AuthState()
    data object Ready : AuthState()
    data object LoggingOut : AuthState()
    data object Closed : AuthState()
    data class Error(val message: String) : AuthState()
}
