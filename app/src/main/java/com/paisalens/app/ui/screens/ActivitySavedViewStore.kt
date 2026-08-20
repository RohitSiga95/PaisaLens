package com.paisalens.app.ui.screens

import android.content.Context
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID

internal class ActivitySavedViewStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(): List<ActivitySavedView> = ActivitySavedViewCodec.decode(
        preferences.getString(KEY_SAVED_VIEWS, null),
    )

    fun save(name: String, filters: ActivityFilterState, now: Long = System.currentTimeMillis()): List<ActivitySavedView> {
        val cleanName = normalizedActivitySavedViewName(name) ?: return read()
        val current = read()
        val existing = current.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
        val updated = if (existing == null) {
            current + ActivitySavedView(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                filters = filters.normalized(),
                createdAt = now,
            )
        } else {
            current.map { view ->
                if (view.id == existing.id) view.copy(filters = filters.normalized()) else view
            }
        }
        return write(updated)
    }

    fun rename(id: String, name: String): List<ActivitySavedView> {
        val cleanName = normalizedActivitySavedViewName(name) ?: return read()
        val current = read()
        if (current.any { it.id != id && it.name.equals(cleanName, ignoreCase = true) }) return current
        return write(current.map { if (it.id == id) it.copy(name = cleanName) else it })
    }

    fun delete(id: String): List<ActivitySavedView> = write(read().filterNot { it.id == id })

    fun clear(): List<ActivitySavedView> {
        clearPreferences(preferences)
        return emptyList()
    }

    fun observe(onChanged: (views: List<ActivitySavedView>, storageWasCleared: Boolean) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SAVED_VIEWS || key == KEY_CLEAR_GENERATION) {
                onChanged(
                    read(),
                    key == KEY_CLEAR_GENERATION || !preferences.contains(KEY_SAVED_VIEWS),
                )
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun write(views: List<ActivitySavedView>): List<ActivitySavedView> {
        val normalized = views
            .distinctBy(ActivitySavedView::id)
            .takeLast(MAX_SAVED_VIEWS)
        preferences.edit()
            .putString(KEY_SAVED_VIEWS, ActivitySavedViewCodec.encode(normalized))
            .apply()
        return normalized
    }

    companion object {
        fun clear(context: Context) {
            val preferences = context.applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            clearPreferences(preferences)
        }

        private fun clearPreferences(preferences: android.content.SharedPreferences) {
            // The generation key guarantees an observer callback even when no views were saved,
            // allowing an open Activity screen to clear transient filters and search metadata.
            preferences.edit()
                .remove(KEY_SAVED_VIEWS)
                .putLong(KEY_CLEAR_GENERATION, System.nanoTime())
                .apply()
        }

        private const val PREFERENCES_NAME = "paisalens.activity.saved.views"
        private const val KEY_SAVED_VIEWS = "views_v1"
        private const val KEY_CLEAR_GENERATION = "clear_generation"
        private const val MAX_SAVED_VIEWS = 20
    }
}

internal object ActivitySavedViewCodec {
    private const val VERSION = 1

    fun encode(views: List<ActivitySavedView>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeInt(views.size)
            views.forEach { view ->
                output.writeUTF(view.id)
                output.writeUTF(view.name)
                output.writeLong(view.createdAt)
                writeFilters(output, view.filters.normalized())
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(encoded: String?): List<ActivitySavedView> {
        if (encoded.isNullOrBlank()) return emptyList()
        return runCatching {
            DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
                if (input.readInt() != VERSION) return emptyList()
                val count = input.readInt().coerceIn(0, 20)
                List(count) {
                    ActivitySavedView(
                        id = input.readUTF(),
                        name = input.readUTF(),
                        createdAt = input.readLong(),
                        filters = readFilters(input),
                    )
                }.filter { it.id.isNotBlank() && normalizedActivitySavedViewName(it.name) != null }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeFilters(output: DataOutputStream, filters: ActivityFilterState) {
        output.writeUTF(filters.query)
        output.writeUTF(filters.typeFilter.name)
        output.writeInt(filters.selectedAccountKeys.size)
        filters.selectedAccountKeys.sorted().forEach(output::writeUTF)
        output.writeUTF(filters.dateRange.name)
        output.writeNullableLong(filters.customStartEpochDay)
        output.writeNullableLong(filters.customEndEpochDay)
        output.writeNullableString(filters.categoryKey)
        output.writeNullableLong(filters.minimumAmountMinor)
        output.writeNullableLong(filters.maximumAmountMinor)
        output.writeNullableString(filters.source?.name)
        output.writeNullableString(filters.institution)
        output.writeNullableString(filters.tag)
        output.writeBoolean(filters.duplicateOnly)
        output.writeNullableString(filters.reviewStatus?.name)
    }

    private fun readFilters(input: DataInputStream): ActivityFilterState = ActivityFilterState(
        query = input.readUTF(),
        typeFilter = input.readUTF().let { name ->
            TransactionFilter.entries.firstOrNull { it.name == name } ?: TransactionFilter.ALL
        },
        selectedAccountKeys = buildSet {
            repeat(input.readInt().coerceIn(0, 100)) { add(input.readUTF()) }
        },
        dateRange = input.readUTF().let { name ->
            ActivityDateRange.entries.firstOrNull { it.name == name } ?: ActivityDateRange.ANY_TIME
        },
        customStartEpochDay = input.readNullableLong(),
        customEndEpochDay = input.readNullableLong(),
        categoryKey = input.readNullableString(),
        minimumAmountMinor = input.readNullableLong(),
        maximumAmountMinor = input.readNullableLong(),
        source = input.readNullableString()?.let { name ->
            TransactionSource.entries.firstOrNull { it.name == name }
        },
        institution = input.readNullableString(),
        tag = input.readNullableString(),
        duplicateOnly = input.readBoolean(),
        reviewStatus = input.readNullableString()?.let { name ->
            ReviewStatus.entries.firstOrNull { it.name == name }
        },
    ).normalized()
}

internal fun normalizedActivitySavedViewName(name: String): String? = name
    .trim()
    .replace(Regex("\\s+"), " ")
    .take(32)
    .takeIf(String::isNotBlank)

private fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    if (value != null) writeLong(value)
}

private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUTF(value)
}

private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readUTF() else null
