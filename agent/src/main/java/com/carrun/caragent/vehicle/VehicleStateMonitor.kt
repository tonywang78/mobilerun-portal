package com.carrun.caragent.vehicle

import android.content.Context
import android.util.Log

/**
 * Reads gear/speed from Android Automotive Car APIs when available.
 * Falls back to mock values for phones / emulators without car services.
 */
class VehicleStateMonitor(
    private val context: Context,
    private val useMock: () -> Boolean,
    private val mockGear: () -> Gear,
    private val mockSpeedKph: () -> Float,
) {
    fun current(): VehicleState {
        if (useMock()) {
            return VehicleState.mock(mockGear(), mockSpeedKph())
        }
        return readCarProperties()
            ?: VehicleState.mock(mockGear(), mockSpeedKph())
    }

    private fun readCarProperties(): VehicleState? {
        return try {
            val carClass = Class.forName("android.car.Car")
            val createMethod = carClass.getMethod("createCar", Context::class.java)
            val car = createMethod.invoke(null, context) ?: return null
            val getCarManager = carClass.getMethod("getCarManager", String::class.java)
            val propertyServiceName = carClass.getField("PROPERTY_SERVICE").get(null) as String
            val propertyManager = getCarManager.invoke(car, propertyServiceName) ?: return null

            val propClass = Class.forName("android.car.VehiclePropertyIds")
            val gearProp = propClass.getField("GEAR_SELECTION").getInt(null)
            val speedProp = propClass.getField("PERF_VEHICLE_SPEED").getInt(null)

            val pmClass = propertyManager.javaClass
            val getProperty = pmClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val gearValue = (getProperty.invoke(propertyManager, gearProp, 0)?.javaClass
                ?.getMethod("getValue")
                ?.invoke(getProperty.invoke(propertyManager, gearProp, 0)) as? Number)?.toInt()
            val speedMs = (getProperty.invoke(propertyManager, speedProp, 0)?.javaClass
                ?.getMethod("getValue")
                ?.invoke(getProperty.invoke(propertyManager, speedProp, 0)) as? Number)?.toFloat()

            val gear = mapGear(gearValue)
            val speedKph = (speedMs ?: 0f) * 3.6f
            VehicleState(gear, speedKph, "android.car")
        } catch (t: Throwable) {
            Log.d(TAG, "Car properties unavailable: ${t.message}")
            null
        }
    }

    private fun mapGear(raw: Int?): Gear = when (raw) {
        4 -> Gear.PARK
        2 -> Gear.REVERSE
        1 -> Gear.NEUTRAL
        8 -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }

    companion object {
        private const val TAG = "VehicleStateMonitor"
    }
}
