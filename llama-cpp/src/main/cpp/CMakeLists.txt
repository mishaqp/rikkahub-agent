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

        # Android NDK contains Vulkan C headers but not all host-side helper
        # headers used by ggml-vulkan. Stage only the required pure-header
        # subtrees instead of adding /usr/include to an Android target, which
        # could accidentally leak host libc/C++ headers into the NDK build.
        set(LLAMAJNI_HOST_VULKAN_HEADERS_STAGE "${CMAKE_CURRENT_BINARY_DIR}/host-vulkan-headers/include")
        file(MAKE_DIRECTORY "${LLAMAJNI_HOST_VULKAN_HEADERS_STAGE}")

        find_path(LLAMAJNI_VULKAN_HPP_INCLUDE_DIR
            NAMES vulkan/vulkan.hpp
            PATHS /usr/include /usr/local/include "$ENV{VULKAN_SDK}/include"
            NO_CMAKE_FIND_ROOT_PATH
        )
        if(LLAMAJNI_VULKAN_HPP_INCLUDE_DIR)
            file(COPY "${LLAMAJNI_VULKAN_HPP_INCLUDE_DIR}/vulkan"
                 DESTINATION "${LLAMAJNI_HOST_VULKAN_HEADERS_STAGE}")
            message(STATUS "Staged Vulkan-Hpp headers from: ${LLAMAJNI_VULKAN_HPP_INCLUDE_DIR}")
        else()
            message(FATAL_ERROR
                "Vulkan-Hpp header vulkan/vulkan.hpp was not found. Install Vulkan-Headers/libvulkan-dev or set VULKAN_SDK.")
        endif()

        find_path(LLAMAJNI_SPIRV_INCLUDE_DIR
            NAMES spirv/unified1/spirv.hpp
            PATHS /usr/include /usr/local/include "$ENV{VULKAN_SDK}/include"
            NO_CMAKE_FIND_ROOT_PATH
        )
        if(LLAMAJNI_SPIRV_INCLUDE_DIR)
            file(COPY "${LLAMAJNI_SPIRV_INCLUDE_DIR}/spirv"
                 DESTINATION "${LLAMAJNI_HOST_VULKAN_HEADERS_STAGE}")
            message(STATUS "Staged SPIR-V headers from: ${LLAMAJNI_SPIRV_INCLUDE_DIR}")
        else()
            message(FATAL_ERROR
                "SPIR-V header spirv/unified1/spirv.hpp was not found. Install spirv-headers or set VULKAN_SDK.")
        endif()
    endif()
endif()

if(LLAMAJNI_ENABLE_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

if(TARGET ggml-vulkan AND LLAMAJNI_HOST_VULKAN_HEADERS_STAGE)
    target_include_directories(ggml-vulkan PRIVATE "${LLAMAJNI_HOST_VULKAN_HEADERS_STAGE}")
endif()

add_library(llamajni SHARED llama_jni.cpp)

target_include_directories(llamajni PRIVATE
    ${LLAMA_ROOT}/include
    ${LLAMA_ROOT}/ggml/include
    ${LLAMA_ROOT}/common
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(llamajni PRIVATE llama llama-common ${ANDROID_LOG_LIB})
