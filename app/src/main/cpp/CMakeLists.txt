# CMake file for building Opus with JNI wrapper for Android
cmake_minimum_required(VERSION 3.22.1)

project("opuscodec")

# Set C/C++ standards
set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)

# Add compiler flags for optimization - arm64-v8a only
# Note: arm64-v8a has NEON by default, no -mfpu needed
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

# Opus configuration flags for arm64-v8a
add_definitions(
    -DOPUS_BUILD
    -DOPUS_VERSION="1.5.2"
    -DHAVE_LRINT
    -DHAVE_LRINTF
    -DOPUS_ENABLE_HARDENING
    -DVAR_ARRAYS  # Use variable length arrays for stack allocation
    # Note: No ARM ASM optimizations needed for arm64-v8a - compiler handles it
)

# Opus source directories
set(OPUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opus-1.5.2)

# Include directories
include_directories(
    ${OPUS_DIR}/include
    ${OPUS_DIR}/src
    ${OPUS_DIR}/celt
    ${OPUS_DIR}/silk
    ${OPUS_DIR}/silk/float
    ${OPUS_DIR}  # For relative includes like "celt/arm/armcpu.h"
)

# Opus source files - Core
file(GLOB OPUS_CORE_SOURCES
    ${OPUS_DIR}/src/*.c
)

# CELT codec sources
file(GLOB CELT_SOURCES
    ${OPUS_DIR}/celt/*.c
)

# SILK codec sources  
file(GLOB SILK_SOURCES
    ${OPUS_DIR}/silk/*.c
    ${OPUS_DIR}/silk/float/*.c
)

# Exclude test files
list(FILTER OPUS_CORE_SOURCES EXCLUDE REGEX ".*_demo\\.c$")
list(FILTER OPUS_CORE_SOURCES EXCLUDE REGEX ".*_test\\.c$")
list(FILTER CELT_SOURCES EXCLUDE REGEX ".*_demo\\.c$")
list(FILTER CELT_SOURCES EXCLUDE REGEX ".*_test\\.c$")
list(FILTER SILK_SOURCES EXCLUDE REGEX ".*_demo\\.c$")
list(FILTER SILK_SOURCES EXCLUDE REGEX ".*_test\\.c$")

# Create static Opus library
add_library(opus STATIC
    ${OPUS_CORE_SOURCES}
    ${CELT_SOURCES}
    ${SILK_SOURCES}
)

# Our JNI wrapper
add_library(opuscodec SHARED
    opus_jni.cpp
)

# Link libraries
target_link_libraries(opuscodec
    opus
    log
)

# Find required Android libraries
find_library(log-lib log)
target_link_libraries(opuscodec ${log-lib})