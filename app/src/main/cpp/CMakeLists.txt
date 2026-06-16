cmake_minimum_required(VERSION 3.22.1)

project(zyna_vulkan_chat)

find_program(
    GLSLC_EXECUTABLE
    glslc
    PATHS
        "${ANDROID_NDK}/shader-tools/darwin-x86_64"
        "${ANDROID_NDK}/shader-tools/darwin-arm64"
        "${ANDROID_NDK}/shader-tools/linux-x86_64"
        "${ANDROID_NDK}/shader-tools/windows-x86_64"
)

if(NOT GLSLC_EXECUTABLE)
    message(FATAL_ERROR "glslc was not found in the Android NDK shader tools")
endif()

set(GENERATED_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated_shaders")
file(MAKE_DIRECTORY "${GENERATED_SHADER_DIR}")

function(compile_spirv_include input_file shader_stage output_file)
    add_custom_command(
        OUTPUT "${output_file}"
        COMMAND
            "${GLSLC_EXECUTABLE}"
            --target-env=vulkan1.0
            "-fshader-stage=${shader_stage}"
            -mfmt=c
            "${input_file}"
            -o
            "${output_file}"
        DEPENDS "${input_file}"
        VERBATIM
    )
endfunction()

set(PAINT_SPLASH_VERT_SPV "${GENERATED_SHADER_DIR}/paint_splash_vert_spv.inc")
set(PAINT_SPLASH_FRAG_SPV "${GENERATED_SHADER_DIR}/paint_splash_frag_spv.inc")
set(PAINT_SPLASH_COMPOSITE_VERT_SPV "${GENERATED_SHADER_DIR}/paint_splash_composite_vert_spv.inc")
set(PAINT_SPLASH_COMPOSITE_FRAG_SPV "${GENERATED_SHADER_DIR}/paint_splash_composite_frag_spv.inc")

compile_spirv_include(
    "${CMAKE_CURRENT_SOURCE_DIR}/shaders/paint_splash.vert"
    vert
    "${PAINT_SPLASH_VERT_SPV}"
)
compile_spirv_include(
    "${CMAKE_CURRENT_SOURCE_DIR}/shaders/paint_splash.frag"
    frag
    "${PAINT_SPLASH_FRAG_SPV}"
)
compile_spirv_include(
    "${CMAKE_CURRENT_SOURCE_DIR}/shaders/paint_splash_composite.vert"
    vert
    "${PAINT_SPLASH_COMPOSITE_VERT_SPV}"
)
compile_spirv_include(
    "${CMAKE_CURRENT_SOURCE_DIR}/shaders/paint_splash_composite.frag"
    frag
    "${PAINT_SPLASH_COMPOSITE_FRAG_SPV}"
)

add_custom_target(
    zyna_vulkan_chat_shaders
    DEPENDS
        "${PAINT_SPLASH_VERT_SPV}"
        "${PAINT_SPLASH_FRAG_SPV}"
        "${PAINT_SPLASH_COMPOSITE_VERT_SPV}"
        "${PAINT_SPLASH_COMPOSITE_FRAG_SPV}"
)

add_library(
    zyna_vulkan_chat
    SHARED
    VulkanChatRenderer.cpp
)

add_dependencies(zyna_vulkan_chat zyna_vulkan_chat_shaders)

target_compile_features(zyna_vulkan_chat PRIVATE cxx_std_17)
target_include_directories(zyna_vulkan_chat PRIVATE "${GENERATED_SHADER_DIR}")

find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(log-lib log)
find_library(vulkan-lib vulkan)

target_link_libraries(
    zyna_vulkan_chat
    ${android-lib}
    ${jnigraphics-lib}
    ${log-lib}
    ${vulkan-lib}
)
