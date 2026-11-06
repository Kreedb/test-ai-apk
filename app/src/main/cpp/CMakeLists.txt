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
            file(REMOVE "${NCNN_ARCHIVE}")
            file(DOWNLOAD
                "https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/${NCNN_PACKAGE}.zip"
                "${NCNN_ARCHIVE}"
                EXPECTED_HASH "SHA256=${NCNN_SHA256}"
                TLS_VERIFY ON
                STATUS ncnn_download_status
            )
            list(GET ncnn_download_status 0 ncnn_download_code)
            if(NOT ncnn_download_code EQUAL 0)
                list(GET ncnn_download_status 1 ncnn_download_error)
                file(REMOVE "${NCNN_ARCHIVE}")
                message(FATAL_ERROR "Failed to download ncnn ${NCNN_VERSION}: ${ncnn_download_error}")
            endif()
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
