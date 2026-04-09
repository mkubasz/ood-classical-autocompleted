package com.github.mkubasz.oodclassicalautocompleted.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.APP)
class ProviderCredentialsService {

    private val modificationCounter = AtomicLong(0)

    val modificationCount: Long
        get() = modificationCounter.get()

    fun getApiKey(provider: AutocompleteProviderType): String? =
        PasswordSafe.instance.getPassword(attributes(provider))
            ?.takeIf { it.isNotBlank() }

    fun hasApiKey(provider: AutocompleteProviderType): Boolean = !getApiKey(provider).isNullOrBlank()

    fun setApiKey(provider: AutocompleteProviderType, value: String?) {
        val normalized = value?.trim().orEmpty().ifBlank { null }
        val previous = getApiKey(provider)
        if (previous == normalized) return

        PasswordSafe.instance.setPassword(attributes(provider), normalized)
        modificationCounter.incrementAndGet()
    }

    fun migrateLegacyKey(provider: AutocompleteProviderType, legacyValue: String?) {
        if (legacyValue.isNullOrBlank()) return
        setApiKey(provider, legacyValue)
    }

    private fun attributes(provider: AutocompleteProviderType): CredentialAttributes =
        CredentialAttributes(generateServiceName(SERVICE_NAME, provider.name))

    companion object {
        private const val SERVICE_NAME = "OOD Autocomplete"
    }
}
