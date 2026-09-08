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

        # Android NDK ships the Vulkan C API, but not Vulkan-Hpp (vulkan.hpp),
        # which ggml-vulkan also needs. Locate the host Vulkan-Headers package
        # outside the Android find-root and stage only its vulkan/ subtree into
        # the build directory. Do not add /usr/include directly to an Android
        # compile, because that can leak host libc/C++ headers into the NDK build.
        find_path(LLAMAJNI_VULKAN_HPP_INCLUDE_DIR
            NAMES vulkan/vulkan.hpp
            PATHS /usr/include /usr/local/include "$ENV{VULKAN_SDK}/include"
            NO_CMAKE_FIND_ROOT_PATH
        )
        if(LLAMAJNI_VULKAN_HPP_INCLUDE_DIR)
            set(LLAMAJNI_VULKAN_HPP_STAGE "${CMAKE_CURRENT_BINARY_DIR}/host-vulkan-headers/include")
            file(MAKE_DIRECTORY "${LLAMAJNI_VULKAN_HPP_STAGE}")
            file(COPY "${LLAMAJNI_VULKAN_HPP_INCLUDE_DIR}/vulkan"
                 DESTINATION "${LLAMAJNI_VULKAN_HPP_STAGE}")
            message(STATUS "Staged Vulkan-Hpp headers from: ${LLAMAJNI_VULKAN_HPP_INCLUDE_DIR}")
        else()
            message(FATAL_ERROR
                "Vulkan-Hpp header vulkan/vulkan.hpp was not found. Install Vulkan-Headers/libvulkan-dev or set VULKAN_SDK.")
        endif()
    endif()
endif()

if(LLAMAJNI_ENABLE_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

if(TARGET ggml-vulkan AND LLAMAJNI_VULKAN_HPP_STAGE)
    target_include_directories(ggml-vulkan PRIVATE "${LLAMAJNI_VULKAN_HPP_STAGE}")
endif()

add_library(llamajni SHARED llama_jni.cpp)

target_include_directories(llamajni PRIVATE
    ${LLAMA_ROOT}/include
    ${LLAMA_ROOT}/ggml/include
    ${LLAMA_ROOT}/common
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(llamajni PRIVATE llama llama-common ${ANDROID_LOG_LIB})
