package com.tunnelac.app.hvac

enum class HvacState {
    RECIRCULATION,  // 내기순환 (CAN c=1)
    FRESH_AIR,      // 외기순환 (CAN c=2)
    AUTO,           // 오토 (CAN c=3)
    UNKNOWN
}
