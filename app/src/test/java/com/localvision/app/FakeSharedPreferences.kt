package com.localvision.app

import android.content.SharedPreferences

/**
 * 纯 JVM 的 SharedPreferences 替身。
 *
 * `edit()` 的调用次数被记录，用于断言"读取历史不应产生无意义写入"。
 * 写入立即生效、不做事务隔离：单测只在一次 edit{} 结束后断言，这个精度足够。
 */
class FakeSharedPreferences(seed: Map<String, Any?> = emptyMap()) : SharedPreferences {
    val values = LinkedHashMap<String, Any?>().apply { putAll(seed) }

    var editCount = 0
        private set

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor {
        editCount++
        return object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                if (value == null) values.remove(key) else values[key] = value
                return this
            }

            override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor {
                if (value == null) values.remove(key) else values[key] = value
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                values.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}
