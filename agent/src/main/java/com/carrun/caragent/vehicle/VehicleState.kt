package com.carrun.caragent.vehicle

enum class Gear {
    PARK,
    REVERSE,
    NEUTRAL,
    DRIVE,
    UNKNOWN,
}

data class VehicleState(
    val gear: Gear,
    val speedKph: Float,
    val source: String,
) {
    val isParked: Boolean
        get() = gear == Gear.PARK || (gear != Gear.UNKNOWN && speedKph <= 0.5f && gear != Gear.DRIVE)

    val isMoving: Boolean
        get() = speedKph > 0.5f || gear == Gear.DRIVE || gear == Gear.REVERSE

    fun summary(): String = "gear=$gear speed=${"%.1f".format(speedKph)}km/h source=$source"

    companion object {
        fun mockParked(): VehicleState = mock(Gear.PARK, 0f)

        fun mockDriving(speedKph: Float = 40f): VehicleState = mock(Gear.DRIVE, speedKph)

        fun mock(gear: Gear, speedKph: Float): VehicleState =
            VehicleState(gear, speedKph, "mock")
    }
}

/** Common developer mock scenarios shown in the main UI. */
enum class MockVehiclePreset(
    val gear: Gear,
    val speedKph: Float,
) {
    PARKED(Gear.PARK, 0f),
    REVERSE(Gear.REVERSE, 5f),
    NEUTRAL(Gear.NEUTRAL, 0f),
    CITY(Gear.DRIVE, 30f),
    URBAN(Gear.DRIVE, 60f),
    HIGHWAY(Gear.DRIVE, 100f),
    ;

    companion object {
        fun from(gear: Gear, speedKph: Float): MockVehiclePreset? =
            entries.firstOrNull {
                it.gear == gear && kotlin.math.abs(it.speedKph - speedKph) < 0.5f
            }
    }
}
