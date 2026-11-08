cmake_minimum_required(VERSION 3.22.1)
project(granite_llama_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Optional path to a local llama.cpp checkout (e.g. -DLLAMA_CPP_DIR=/path/to/llama.cpp)
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp" CACHE PATH "Path to llama.cpp source root")

add_library(granite_llama_jni SHARED granite_llama_jni.cpp)

if (EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    add_subdirectory("${LLAMA_CPP_DIR}" llama_build EXCLUDE_FROM_ALL)
    target_compile_definitions(granite_llama_jni PRIVATE GRANITE_HAS_LLAMA_CPP=1)
    target_include_directories(granite_llama_jni PRIVATE "${LLAMA_CPP_DIR}/include" "${LLAMA_CPP_DIR}/ggml/include")
    target_link_libraries(granite_llama_jni PRIVATE llama ggml log)
else ()
    target_link_libraries(granite_llama_jni PRIVATE log)
endif ()
