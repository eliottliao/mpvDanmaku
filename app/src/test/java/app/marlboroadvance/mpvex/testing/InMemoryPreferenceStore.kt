package app.marlboroadvance.mpvex.testing

import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * In-memory [PreferenceStore] so preference-backed classes such as
 * `DanmakuPreferences` can be exercised on the plain JVM without DataStore/Context.
 */
class InMemoryPreferenceStore : PreferenceStore {
  private val preferences = HashMap<String, InMemoryPreference<*>>()

  override fun getString(key: String, defaultValue: String): Preference<String> =
    preference(key, defaultValue)

  override fun getLong(key: String, defaultValue: Long): Preference<Long> =
    preference(key, defaultValue)

  override fun getInt(key: String, defaultValue: Int): Preference<Int> =
    preference(key, defaultValue)

  override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
    preference(key, defaultValue)

  override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
    preference(key, defaultValue)

  override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
    preference(key, defaultValue)

  override fun <T> getObject(
    key: String,
    defaultValue: T,
    serializer: (T) -> String,
    deserializer: (String) -> T,
  ): Preference<T> = preference(key, defaultValue)

  override fun getAll(): Map<String, *> = preferences.mapValues { (_, value) -> value.get() }

  @Suppress("UNCHECKED_CAST")
  private fun <T> preference(key: String, defaultValue: T): Preference<T> =
    preferences.getOrPut(key) { InMemoryPreference(key, defaultValue) } as InMemoryPreference<T>
}

private class InMemoryPreference<T>(
  private val keyName: String,
  private val default: T,
) : Preference<T> {
  private val flow = MutableStateFlow(default)

  override fun key(): String = keyName

  override fun get(): T = flow.value

  override fun set(value: T) {
    flow.value = value
  }

  override fun isSet(): Boolean = flow.value != default

  override fun delete() {
    flow.value = default
  }

  override fun defaultValue(): T = default

  override fun changes(): Flow<T> = flow

  override fun stateIn(scope: CoroutineScope): StateFlow<T> =
    flow.stateIn(scope, SharingStarted.Eagerly, flow.value)
}
