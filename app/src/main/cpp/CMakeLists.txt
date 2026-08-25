cmake_minimum_required(VERSION 3.22.1)
project(lastwave_audio LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(oboe REQUIRED CONFIG)

# libsoxr 0.1.3 is pinned for reproducible HQ sinc resampling. It is built as
# a separate LGPL shared library rather than statically folded into the app.
include(FetchContent)
FetchContent_Declare(
    soxr_source
    URL https://github.com/chirlu/soxr/archive/refs/tags/0.1.3.tar.gz
    URL_HASH SHA256=db6ca1b1e8405c6ef92f8294fc123d910abf0a114003b3f0f13fa57a95fd62d0
)
FetchContent_GetProperties(soxr_source)
if(NOT soxr_source_POPULATED)
    FetchContent_Populate(soxr_source)
    list(APPEND CMAKE_MODULE_PATH "${soxr_source_SOURCE_DIR}/cmake/Modules")
    set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(BUILD_LSR_TESTS OFF CACHE BOOL "" FORCE)
    set(WITH_LSR_BINDINGS OFF CACHE BOOL "" FORCE)
    set(WITH_OPENMP OFF CACHE BOOL "" FORCE)
    set(WITH_CR32S OFF CACHE BOOL "" FORCE)
    set(WITH_CR64S OFF CACHE BOOL "" FORCE)
    set(WITH_PFFFT OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    add_subdirectory("${soxr_source_SOURCE_DIR}" "${soxr_source_BINARY_DIR}" EXCLUDE_FROM_ALL)
    # Android APKs need an unversioned DT_SONAME/file name for reliable
    # packaging and namespace loading of transitive native dependencies.
    set_target_properties(soxr PROPERTIES VERSION "" SOVERSION "")
endif()

add_library(
    lastwave_audio
    SHARED
    AudioEngine.cpp
    DspProcessor.cpp
    NativeBridge.cpp
)

target_include_directories(
    lastwave_audio
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${soxr_source_SOURCE_DIR}/src"
)

target_compile_options(
    lastwave_audio
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -Wconversion
    -Wshadow
)

target_link_libraries(
    lastwave_audio
    PRIVATE
    oboe::oboe
    soxr
    android
    log
)
