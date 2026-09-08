cmake_minimum_required(VERSION 3.22.1)
project(llamajni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(LLAMAJNI_ENABLE_VULKAN "Build the Vulkan backend for GPU offload" OFF)
option(LLAMAJNI_ENABLE_OPENCL "Build the OpenCL backend for Adreno GPUs" OFF)

set(LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../native/llama.cpp)

# Everything we do not need. Each of these adds build time or binary size.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# common carries chat templates, grammar-constrained tool calling and parsing.
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)

set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

if(LLAMAJNI_ENABLE_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)

    # llama.cpp builds vulkan-shaders-gen with the host compiler while the
    # Android libraries are cross-compiled with the NDK. CMake's Android
    # find-root rules hide host packages, so explicitly discover the host
    # SPIRV-Headers config without applying the NDK root path. The actual
    # Vulkan library is still resolved from the Android NDK by llama.cpp.
    if(CMAKE_CROSSCOMPILING AND CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        find_path(LLAMAJNI_SPIRV_HEADERS_CONFIG_DIR
            NAMES SPIRV-HeadersConfig.cmake
            PATHS /usr/share /usr/local/share
            PATH_SUFFIXES cmake/SPIRV-Headers
            NO_CMAKE_FIND_ROOT_PATH
        )
        if(LLAMAJNI_SPIRV_HEADERS_CONFIG_DIR)
            set(SPIRV-Headers_DIR "${LLAMAJNI_SPIRV_HEADERS_CONFIG_DIR}" CACHE PATH
                "Host SPIRV-Headers package config used by llama.cpp Vulkan shader generation" FORCE)
            message(STATUS "Host SPIRV-Headers config: ${SPIRV-Headers_DIR}")
        endif()
    endif()
endif()

if(LLAMAJNI_ENABLE_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(llamajni SHARED llama_jni.cpp)

target_include_directories(llamajni PRIVATE
    ${LLAMA_ROOT}/include
    ${LLAMA_ROOT}/ggml/include
    ${LLAMA_ROOT}/common
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(llamajni PRIVATE llama llama-common ${ANDROID_LOG_LIB})
