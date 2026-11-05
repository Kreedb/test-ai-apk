cmake_minimum_required(VERSION 3.22)
project(ncnnpose LANGUAGES C CXX)

# ncnn is pinned as a submodule at the release tag; build it as a static library so only
# libncnnpose.so ships in the APK.
set(NCNN_VULKAN OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_PYTHON OFF CACHE BOOL "" FORCE)
set(NCNN_PIXEL_DRAWING OFF CACHE BOOL "" FORCE)
set(NCNN_PIXEL_ROTATE OFF CACHE BOOL "" FORCE)
set(NCNN_PIXEL_AFFINE OFF CACHE BOOL "" FORCE)
# The bundled model is float32, so int8/bf16/weight-quant kernels are dead weight.
set(NCNN_INT8 OFF CACHE BOOL "" FORCE)
set(NCNN_BF16 OFF CACHE BOOL "" FORCE)
set(NCNN_WEIGHT_QUANT OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "x86_64")
    # x86_64 is only used by emulators. Keep the SSE2 baseline and drop the large AVX
    # variants instead of shipping every dispatch target.
    foreach(option
        NCNN_AVX NCNN_FMA NCNN_FMA4 NCNN_XOP NCNN_F16C NCNN_AVX2 NCNN_AVX512
        NCNN_AVXVNNI NCNN_AVXVNNIINT8 NCNN_AVXVNNIINT16 NCNN_AVXNECONVERT
        NCNN_AVX512VNNI NCNN_AVX512BF16 NCNN_AVX512FP16
    )
        set(${option} OFF CACHE BOOL "" FORCE)
    endforeach()
endif()
add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../ncnn"
    "${CMAKE_CURRENT_BINARY_DIR}/ncnn"
    EXCLUDE_FROM_ALL
)

add_library(ncnnpose SHARED ncnn_pose.cpp)
target_link_libraries(ncnnpose PRIVATE ncnn)
target_compile_options(ncnnpose PRIVATE -O3 -fvisibility=hidden)
target_link_options(ncnnpose PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384
)
