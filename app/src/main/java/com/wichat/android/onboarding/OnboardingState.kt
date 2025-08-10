package com.wichat.android.onboarding

enum class OnboardingState {
    CHECKING,
    WIFI_CHECK,
    LOCATION_CHECK,
    BATTERY_OPTIMIZATION_CHECK,
    PERMISSION_EXPLANATION,
    PERMISSION_REQUESTING,
    INITIALIZING,
    COMPLETE,
    ERROR
}