package com.sriram.callreminder

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

object GoogleCalendarAuth {
    val scopes = listOf("https://www.googleapis.com/auth/calendar.calendarlist.readonly", "https://www.googleapis.com/auth/calendar.events.readonly")
    fun client(c: Context) = Identity.getAuthorizationClient(c)
    fun request(account: String) = AuthorizationRequest.builder().setAccount(Account(account, "com.google"))
        .setRequestedScopes(scopes.map(::Scope)).build()
    fun token(result: AuthorizationResult): String {
        check(!result.hasResolution() && result.grantedScopes.containsAll(scopes)) { "Please grant both read-only Calendar permissions to connect." }
        return checkNotNull(result.accessToken?.takeIf(String::isNotBlank)) { "Google did not return Calendar access. Please reconnect." }
    }
    fun clear(c: Context, token: String) = client(c).clearToken(ClearTokenRequest.builder().setToken(token).build())
    fun revoke(c: Context, account: String) = client(c).revokeAccess(RevokeAccessRequest.builder()
        .setAccount(Account(account, "com.google")).setScopes(scopes.map(::Scope)).build())
    fun message(error: Exception): String {
        val cause = (error.cause as? Exception) ?: error
        return when {
            cause is ApiException && cause.statusCode == 10 -> "Google Calendar is not configured for this app yet. Complete the one-time setup and try again."
            cause is ApiException -> "Google authorization failed (${cause.statusCode}). Check Google Play services, account access, and OAuth setup."
            cause is CalendarHttpException -> cause.message.orEmpty()
            cause is java.io.IOException -> "Could not refresh Calendar. Check your connection; the previous meeting schedule is retained."
            else -> cause.message ?: "Calendar could not connect. Please try again."
        }
    }
}
