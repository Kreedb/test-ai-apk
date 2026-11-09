cmake_minimum_required(VERSION 3.22)
project(ncnnpose LANGUAGES C CXX)

# Prebuilt ncnn release with the Vulkan backend, downloaded once into app/build and shared
# by both ABIs. The static library is linked into libncnnpose.so, so the APK ships no libncnn.so.
set(NCNN_VERSION "20261020")
set(NCNN_PACKAGE "ncnn-${NCNN_VERSION}-android-vulkan")
set(NCNN_SHA256 "6e0c5f9b397faf32b923de2ef6210af9bf375851869dceff06a8005331981086")
set(NCNN_CACHE "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/ncnn")
set(NCNN_ABI_DIR "${NCNN_CACHE}/${NCNN_PACKAGE}/${ANDROID_ABI}")
set(NCNN_ARCHIVE "${NCNN_CACHE}/${NCNN_PACKAGE}.zip")
set(NCNN_STAMP "${NCNN_CACHE}/${NCNN_PACKAGE}.ok")

# GitHub's release CDN occasionally returns a transient HTTP error. Retry instead of failing the
# whole build, and verify the hash here so a truncated payload also gets another attempt.
function(download_archive url archive sha256)
    foreach(attempt RANGE 1 5)
        file(DOWNLOAD "${url}" "${archive}" TLS_VERIFY ON STATUS status)
        list(GET status 0 code)
        list(GET status 1 error)
        if(code EQUAL 0 AND EXISTS "${archive}")
            file(SHA256 "${archive}" actual)
            if(actual STREQUAL sha256)
                return()
            endif()
            set(error "SHA-256 mismatch: expected ${sha256}, got ${actual}")
        endif()
        file(REMOVE "${archive}")
        if(attempt LESS 5)
            message(WARNING "Download failed (attempt ${attempt}/5): ${error}; retrying")
            execute_process(COMMAND "${CMAKE_COMMAND}" -E sleep 10)
        endif()
    endforeach()
    message(FATAL_ERROR "Failed to download ${url}: ${error}")
endfunction()

if(NOT EXISTS "${NCNN_STAMP}" OR NOT EXISTS "${NCNN_ABI_DIR}/lib/libncnn.a")
    file(MAKE_DIRECTORY "${NCNN_CACHE}")
    # The two ABI CMake configures can run in parallel and share this cache.
    file(LOCK "${NCNN_CACHE}/download.lock" GUARD PROCESS TIMEOUT 600)
    if(NOT EXISTS "${NCNN_STAMP}" OR NOT EXISTS "${NCNN_ABI_DIR}/lib/libncnn.a")
        # Re-check the cached archive so an interrupted download or extraction retries instead
        # of failing forever on a truncated file.
        if(EXISTS "${NCNN_ARCHIVE}")
            file(SHA256 "${NCNN_ARCHIVE}" ncnn_archive_sha256)
        endif()
        if(NOT "${ncnn_archive_sha256}" STREQUAL "${NCNN_SHA256}")
            download_archive(
                "https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/${NCNN_PACKAGE}.zip"
                "${NCNN_ARCHIVE}"
                "${NCNN_SHA256}"
            )
        endif()
        file(ARCHIVE_EXTRACT INPUT "${NCNN_ARCHIVE}" DESTINATION "${NCNN_CACHE}")
        file(WRITE "${NCNN_STAMP}" "${NCNN_SHA256}\n")
    endif()
    file(LOCK "${NCNN_CACHE}/download.lock" RELEASE)
endif()

set(ncnn_DIR "${NCNN_ABI_DIR}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

add_library(ncnnpose SHARED ncnn_pose.cpp)
target_link_libraries(ncnnpose PRIVATE ncnn)
target_compile_options(ncnnpose PRIVATE -O3 -fvisibility=hidden)
target_link_options(ncnnpose PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384
)

# MNN's official Android prebuilt covers ARM only; x86_64 devices keep using the other backends.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(MNN_VERSION "3.6.2")
    set(MNN_PACKAGE "mnn_${MNN_VERSION}_android_armv7_armv8_cpu_opencl_vulkan")
    set(MNN_SHA256 "0b4cc255964de1e3ac8dce75dce316699a0a28b914ecacb7bea8465666858ece")
    set(MNN_CACHE "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/mnn")
    set(MNN_ABI_DIR "${MNN_CACHE}/${MNN_PACKAGE}/${ANDROID_ABI}")
    set(MNN_ARCHIVE "${MNN_CACHE}/${MNN_PACKAGE}.zip")
    set(MNN_STAMP "${MNN_CACHE}/${MNN_PACKAGE}.ok")

    if(NOT EXISTS "${MNN_STAMP}" OR NOT EXISTS "${MNN_ABI_DIR}/libMNN.so")
        file(MAKE_DIRECTORY "${MNN_CACHE}")
        file(LOCK "${MNN_CACHE}/download.lock" GUARD PROCESS TIMEOUT 600)
        if(NOT EXISTS "${MNN_STAMP}" OR NOT EXISTS "${MNN_ABI_DIR}/libMNN.so")
            if(EXISTS "${MNN_ARCHIVE}")
                file(SHA256 "${MNN_ARCHIVE}" mnn_archive_sha256)
            endif()
            if(NOT "${mnn_archive_sha256}" STREQUAL "${MNN_SHA256}")
                download_archive(
                    "https://github.com/alibaba/MNN/releases/download/${MNN_VERSION}/${MNN_PACKAGE}.zip"
                    "${MNN_ARCHIVE}"
                    "${MNN_SHA256}"
                )
            endif()
            file(ARCHIVE_EXTRACT INPUT "${MNN_ARCHIVE}" DESTINATION "${MNN_CACHE}")
            file(WRITE "${MNN_STAMP}" "${MNN_SHA256}\n")
        endif()
        file(LOCK "${MNN_CACHE}/download.lock" RELEASE)
    endif()

    # Imported targets make AGP package the runtime libraries. MNN loads the OpenCL/Vulkan
    # backends itself, but linking them keeps packaging deterministic.
    add_library(MNN SHARED IMPORTED)
    set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${MNN_ABI_DIR}/libMNN.so")
    add_library(MNN_CL SHARED IMPORTED)
    set_target_properties(MNN_CL PROPERTIES IMPORTED_LOCATION "${MNN_ABI_DIR}/libMNN_CL.so")
    add_library(MNN_Vulkan SHARED IMPORTED)
    set_target_properties(MNN_Vulkan PROPERTIES IMPORTED_LOCATION "${MNN_ABI_DIR}/libMNN_Vulkan.so")

    add_library(mnnpose SHARED mnn_pose.cpp)
    target_include_directories(mnnpose PRIVATE mnn/include)
    target_link_libraries(mnnpose PRIVATE MNN MNN_CL MNN_Vulkan android log)
    target_compile_options(mnnpose PRIVATE -O3 -fvisibility=hidden)
    target_link_options(mnnpose PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,-z,max-page-size=16384
    )
endif()
