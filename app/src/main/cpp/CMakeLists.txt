cmake_minimum_required(VERSION 3.22.1)

# NDK reads this while project() initializes the Android compiler/linker flags. Keep it here as
# well as passing it from Gradle so every native target (including FetchContent libraries) gets
# 16 KB LOAD segment alignment on the supported ABIs.
set(ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES ON CACHE BOOL "" FORCE)

project(trazo_llama_jni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
    # Keep the build self-contained. KleidiAI is optional and makes upstream fetch a second
    # source archive; the regular ARM CPU backend remains enabled without it.
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86" CACHE STRING "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
else()
    message(FATAL_ERROR "Unsupported Android ABI for llama.cpp: ${ANDROID_ABI}")
endif()

message(STATUS "llama.cpp Android ABI=${ANDROID_ABI}; arch=${GGML_SYSTEM_ARCH}; CPU backend enabled; KleidiAI=${GGML_CPU_KLEIDIAI}; OpenMP=${GGML_OPENMP}")

include(FetchContent)

# Upstream official release v0.8.0, pinned to its immutable release commit.
set(LLAMA_COMMIT "8642541b255bfb5dc39cd351a178be09013a55d6")
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LLAMA_COMMIT}
    GIT_SHALLOW FALSE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(trazo_llama SHARED llama_jni.cpp)
target_compile_features(trazo_llama PRIVATE cxx_std_17)
target_include_directories(trazo_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/tools/mtmd
)
target_compile_options(trazo_llama PRIVATE -fexceptions -frtti)
target_link_libraries(trazo_llama PRIVATE llama mtmd android log)
