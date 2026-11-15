package extensionsfr.utils

import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// From https://al-e-shevelev.medium.com/mutable-lazy-in-kotlin-14233bed116d
class LazyMutable<T>(
    val initializer: () -> T,
) : ReadWriteProperty<Any?, T> {
    private object UninitializedValue

    @Volatile private var propValue: Any? = UninitializedValue

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val localValue = propValue

        if (localValue != UninitializedValue) {
            return localValue as T
        }

        return synchronized(this) {
            val localValue2 = propValue

            if (localValue2 != UninitializedValue) {
                localValue2 as T
            } else {
                val initializedValue = initializer()
                propValue = initializedValue
                initializedValue
            }
        }
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        synchronized(this) {
            propValue = value
        }
    }
}

// txh seco https://github.com/Secozzi/aniyomi-extensions/blob/master/core/src/main/kotlin/extensions/utils/Preferences.kt
class PreferenceDelegate<T>(
    val preferences: SharedPreferences,
    val key: String,
    val default: T,
) : ReadWriteProperty<Any?, T> {
    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return synchronized(this) {
            when (default) {
                is String -> preferences.getString(key, default) as T
                is Int -> preferences.getInt(key, default) as T
                is Long -> preferences.getLong(key, default) as T
                is Float -> preferences.getFloat(key, default) as T
                is Boolean -> preferences.getBoolean(key, default) as T
                is Set<*> -> preferences.getStringSet(key, default as Set<String>) as T
                else -> throw IllegalArgumentException("Unsupported Type ${default?.javaClass}")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        return synchronized(this) {
            val editor = preferences.edit()
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
                else -> IllegalArgumentException("Unsupported Type ${default?.javaClass}")
            }
            editor.apply()
        }
    }
}

fun <T> SharedPreferences.delegate(key: String, default: T)
    = PreferenceDelegate(this, key, default)