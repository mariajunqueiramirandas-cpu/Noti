package app.noti.openrouter

import android.content.Context
import app.noti.data.EncryptedSecretStore

internal fun openRouterKeys(context: Context) =
    EncryptedSecretStore(context.applicationContext, "openrouter_keys", "noti-openrouter")
