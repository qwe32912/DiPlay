package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/**
 * Nezha steering-wheel keys via Android Car API (reflection, no compile dependency).
 *
 * The wheel buttons do NOT go through Linux input or MediaSession. They are delivered
 * as car property changes via android.car.hardware.cabin.CarCabinManager, which the
 * stock EngineerMode app uses (property IDs 371198780=prev, 371198781=next).
 *
 * We poll the properties (no callback needed) and forward to CarPlay.
 */
internal object NezhaCarWheelKeys {
    private const val TAG = "DiPlay-NezhaWheel"
    private const val PROP_PREVIOUS = 371198780
    private const val PROP_NEXT = 371198781
    private const val POLL_MILLIS = 150L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var car: Any? = null
    private var cabinManager: Any? = null
    private var polling = false
    private var lastPrev: Any? = null
    private var lastNext: Any? = null

    @Synchronized
    fun start(context: Context) {
        if (car != null) return
        appContext = context.applicationContext
        try {
            val carClass = Class.forName("android.car.Car")
            val createMethod = carClass.getMethod("createCar",
                Context::class.java, ServiceConnection::class.java)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    try {
                        val getManager = carClass.getMethod("getCarManager", String::class.java)
                        cabinManager = getManager.invoke(car, "cabin")
                        Log.i(TAG, "cabin manager acquired")
                        startPolling()
                    } catch (e: Throwable) {
                        Log.w(TAG, "getCarManager failed", e)
                    }
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    cabinManager = null
                    stopPolling()
                }
            }
            car = createMethod.invoke(null, appContext, conn)
            val connectMethod = carClass.getMethod("connect")
            connectMethod.invoke(car)
            Log.i(TAG, "car connect requested")
        } catch (e: Throwable) {
            Log.w(TAG, "car API not available", e)
        }
    }

    @Synchronized
    fun stop() {
        stopPolling()
        try {
            car?.let {
                val disconnect = it.javaClass.getMethod("disconnect")
                disconnect.invoke(it)
            }
        } catch (e: Throwable) { /* ignore */ }
        car = null
        cabinManager = null
        appContext = null
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        // Initialize last values to avoid false trigger on first poll
        lastPrev = getProperty(PROP_PREVIOUS)
        lastNext = getProperty(PROP_NEXT)
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            try {
                val prev = getProperty(PROP_PREVIOUS)
                val next = getProperty(PROP_NEXT)
                if (isPressed(prev) && !isPressed(lastPrev)) {
                    Log.i(TAG, "wheel PREVIOUS")
                    CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PREVIOUS)
                }
                if (isPressed(next) && !isPressed(lastNext)) {
                    Log.i(TAG, "wheel NEXT")
                    CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
                }
                lastPrev = prev
                lastNext = next
            } catch (e: Throwable) {
                Log.w(TAG, "poll failed", e)
            }
            handler.postDelayed(this, POLL_MILLIS)
        }
    }

    /** Returns the raw property value, or null on failure. */
    private fun getProperty(propertyId: Int): Any? {
        val mgr = cabinManager ?: return null
        return try {
            // CarPropertyManager.getProperty(int) returns CarPropertyValue
            val method = mgr.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType)
            val propValue = method.invoke(mgr, propertyId) ?: return null
            // CarPropertyValue.getValue() returns the actual value (Boolean for buttons)
            val getValue = propValue.javaClass.getMethod("getValue")
            getValue.invoke(propValue)
        } catch (e: Throwable) {
            null
        }
    }

    /** A button is "pressed" if the value is Boolean true or a positive number. */
    private fun isPressed(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> false
        }
    }
}
