package com.prism.launcher

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.lang.reflect.Constructor

object DynamicPageLoader {
    private const val TAG = "DynamicPageLoader"

    fun inflateView(host: Context, packageName: String, className: String): View? {
        return try {
            val foreign = host.createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            val loader = foreign.classLoader
            val clazz = Class.forName(className, false, loader)
            instantiateView(clazz, foreign) ?: instantiateView(clazz, host)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load $className from $packageName", t)
            null
        }
    }

    private fun instantiateView(clazz: Class<*>, context: Context): View? {
        val viewClass = View::class.java
        if (!viewClass.isAssignableFrom(clazz)) return null
        @Suppress("UNCHECKED_CAST")
        val viewType = clazz as Class<out View>
        return tryConstructor(viewType, arrayOf(Context::class.java), arrayOf(context))
            ?: tryConstructor(
                viewType,
                arrayOf(Context::class.java, AttributeSet::class.java),
                arrayOf(context, null)
            )
            ?: tryConstructor(
                viewType,
                arrayOf(Context::class.java, AttributeSet::class.java, Int::class.javaPrimitiveType!!),
                arrayOf(context, null, 0)
            )
    }

    private fun tryConstructor(
        clazz: Class<out View>,
        types: Array<Class<*>>,
        args: Array<Any?>,
    ): View? {
        return try {
            val ctor: Constructor<out View> = clazz.getConstructor(*types)
            ctor.newInstance(*args)
        } catch (_: Throwable) {
            null
        }
    }
}
