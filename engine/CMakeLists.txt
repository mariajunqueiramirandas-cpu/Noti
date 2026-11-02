cmake_minimum_required(VERSION 3.22)
project(noti_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(NOTI_BUILD_TESTS "Build unit tests" ${PROJECT_IS_TOP_LEVEL})
option(NOTI_BUILD_TOOLS "Build predictor harness and engine benchmark" ${PROJECT_IS_TOP_LEVEL})
option(NOTI_SANITIZE "Build with ASan+UBSan (host only)" OFF)
option(NOTI_TSAN "Build with ThreadSanitizer (host only)" OFF)
option(NOTI_TUNE_PAD7 "Tune codegen for Snapdragon 7+ Gen 3 (Cortex-X4/A720/A520)" ON)

# ---------------------------------------------------------------------------
# Hot-path codegen: no exceptions/RTTI (nothing on the ink path can throw),
# -O3, no errno-setting math (lets sqrt/fabs inline to single instructions),
# hidden visibility. No -ffast-math: the predictor relies on NaN/inf-safe
# comparisons.
# ---------------------------------------------------------------------------
add_library(noti_flags INTERFACE)
target_compile_options(noti_flags INTERFACE
  -Wall -Wextra -Wno-unused-parameter
  -fno-exceptions -fno-rtti
  -fno-math-errno -fno-trapping-math
  -fvisibility=hidden -fvisibility-inlines-hidden
  $<$<CONFIG:Release,RelWithDebInfo>:-O3>)
if(ANDROID AND NOT ANDROID_ABI STREQUAL "arm64-v8a")
  # Modern 64-bit ARM only: no armeabi-v7a, x86 or x86_64 builds.
  message(FATAL_ERROR "Noti targets arm64-v8a only (got ANDROID_ABI=${ANDROID_ABI})")
endif()
if(ANDROID AND NOTI_TUNE_PAD7)
  # armv8.2-a baseline features present on every 64-bit Snapdragon since 2018;
  # schedule for the A720 (the cores the ADPF session keeps us on).
  target_compile_options(noti_flags INTERFACE -march=armv8.2-a+fp16+dotprod -mtune=cortex-a715)
endif()
if(NOTI_SANITIZE)
  target_compile_options(noti_flags INTERFACE -fsanitize=address,undefined -fno-omit-frame-pointer)
  target_link_options(noti_flags INTERFACE -fsanitize=address,undefined)
endif()
if(NOTI_TSAN)
  target_compile_options(noti_flags INTERFACE -fsanitize=thread -fno-omit-frame-pointer)
  target_link_options(noti_flags INTERFACE -fsanitize=thread)
endif()

find_package(Threads REQUIRED)

# ---------------------------------------------------------------------------
# Portable core: ring, predictor, geometry, probe, engine. Builds anywhere.
# ---------------------------------------------------------------------------
add_library(noti_core STATIC
  src/engine.cpp
  src/predictor.cpp
  src/latency_probe.cpp
  src/stroke_store.cpp
  src/cpu_renderer.cpp
  src/stroke_recorder.cpp)
target_include_directories(noti_core PUBLIC include)
target_link_libraries(noti_core PUBLIC noti_flags Threads::Threads)

# ---------------------------------------------------------------------------
# Android: GL front-buffer renderer, SurfaceControl layers, ADPF, JNI.
# minSdk 33 (front-buffer AHardwareBuffer usage); API 34/35 calls are weak.
# ---------------------------------------------------------------------------
if(ANDROID)
  add_library(noti_ink SHARED
    src/android/gl_renderer.cpp
    src/android/perf_session.cpp
    src/android/jni_bridge.cpp)
  target_link_libraries(noti_ink PRIVATE noti_core android EGL GLESv3 log)
  target_link_options(noti_ink PRIVATE
    -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL)
  target_compile_options(noti_ink PRIVATE -ffunction-sections -fdata-sections)
endif()

if(NOTI_BUILD_TESTS AND NOT ANDROID)
  enable_testing()
  add_executable(noti_tests tests/tests.cpp)
  target_link_libraries(noti_tests PRIVATE noti_core)
  add_test(NAME noti_tests COMMAND noti_tests)
endif()

if(NOTI_BUILD_TOOLS AND NOT ANDROID)
  add_executable(predictor_bench tools/predictor_bench.cpp tools/synthetic_strokes.cpp)
  target_link_libraries(predictor_bench PRIVATE noti_core)
  add_executable(engine_bench tools/engine_bench.cpp tools/synthetic_strokes.cpp)
  target_link_libraries(engine_bench PRIVATE noti_core)
  # Host CI gates engine CPU cost only; the wall-clock gate (--gate-p99-us) is
  # for on-device runs where scheduling noise is representative.
  add_test(NAME engine_cost_gate COMMAND engine_bench --seconds 2 --gate-cpu-us 100)
  add_test(NAME predictor_quality_gate COMMAND predictor_bench --gate)
endif()
