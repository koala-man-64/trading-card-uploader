package com.tradingcards.uploader.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import com.tradingcards.uploader.BuildConfig
import com.tradingcards.uploader.R
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MsalAuthRepository(private val context: Context) {
    private val uploadScopes = arrayOf(BuildConfig.UPLOAD_API_SCOPE)
    private val galleryScopes = arrayOf(BuildConfig.GALLERY_MANAGE_SCOPE)

    suspend fun signIn(activity: Activity): String {
        return acquireToken(activity, uploadScopes)
    }

    suspend fun acquireGalleryManageToken(activity: Activity): String {
        return acquireToken(activity, galleryScopes)
    }

    suspend fun acquireToken(
        activity: Activity,
        scopes: Array<String>,
    ): String {
        val app = application()
        existingAccount(app)?.let {
            return runCatching { acquireTokenSilent(app, scopes) }
                .getOrElse { interactiveSignIn(app, activity, scopes) }
        }
        return interactiveSignIn(app, activity, scopes)
    }

    private suspend fun interactiveSignIn(
        app: ISingleAccountPublicClientApplication,
        activity: Activity,
        scopes: Array<String>,
    ): String =
        suspendCancellableCoroutine { continuation ->
            app.signIn(
                activity,
                null,
                scopes,
                object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        continuation.resume(authenticationResult.accessToken)
                    }

                    override fun onError(exception: MsalException) {
                        continuation.resumeWithException(exception)
                    }

                    override fun onCancel() {
                        continuation.resumeWithException(IllegalStateException("MSAL sign-in cancelled"))
                    }
                },
            )
        }

    suspend fun acquireTokenSilent(): String {
        val app = application()
        currentAccount(app)
        return acquireTokenSilent(app, uploadScopes)
    }

    private suspend fun acquireTokenSilent(
        app: ISingleAccountPublicClientApplication,
        scopes: Array<String>,
    ): String =
        suspendCancellableCoroutine { continuation ->
            app.acquireTokenSilentAsync(
                scopes,
                defaultAuthorityUrl(),
                object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        continuation.resume(authenticationResult.accessToken)
                    }

                    override fun onError(exception: MsalException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }

    private fun defaultAuthorityUrl(): String {
        val config =
            context.resources
                .openRawResource(R.raw.msal_auth_config)
                .bufferedReader()
                .use { it.readText() }
        val tenantId =
            JSONObject(config)
                .getJSONArray("authorities")
                .getJSONObject(0)
                .getJSONObject("audience")
                .getString("tenant_id")
        return "https://login.microsoftonline.com/$tenantId"
    }

    private suspend fun currentAccount(app: ISingleAccountPublicClientApplication): IAccount =
        existingAccount(app)
            ?: error("No signed-in account is available")

    private suspend fun existingAccount(app: ISingleAccountPublicClientApplication): IAccount? =
        suspendCancellableCoroutine { continuation ->
            app.getCurrentAccountAsync(
                object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                    override fun onAccountLoaded(activeAccount: IAccount?) {
                        continuation.resume(activeAccount)
                    }

                    override fun onAccountChanged(
                        priorAccount: IAccount?,
                        currentAccount: IAccount?,
                    ) = Unit

                    override fun onError(exception: MsalException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }

    private suspend fun application(): ISingleAccountPublicClientApplication =
        suspendCancellableCoroutine { continuation ->
            PublicClientApplication.createSingleAccountPublicClientApplication(
                context.applicationContext,
                R.raw.msal_auth_config,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        continuation.resume(application)
                    }

                    override fun onError(exception: MsalException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }
}
