cmake_minimum_required(VERSION 3.11)

if (NOT ANDROID_NDK_TOOLCHAIN_INCLUDED)
    message(FATAL_ERROR "-- Toolchain file not included, see https://developer.android.com/ndk/guides/cmake")
endif ()

include(FetchContent)
FetchContent_Declare(
    octomap
    GIT_REPOSITORY https://github.com/OctoMap/octomap.git
    GIT_TAG v1.10.0
)
FetchContent_GetProperties(octomap)
if (NOT octomap_POPULATED)
    FetchContent_Populate(octomap)
endif ()
add_subdirectory(${octomap_SOURCE_DIR}/octomap ${octomap_BINARY_DIR})

set(OCTOMAP_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/octomap/${ANDROID_ABI}")
foreach(octomap_target octomath octomap octomath-static octomap-static)
    if(TARGET ${octomap_target})
        set_target_properties(
            ${octomap_target}
            PROPERTIES
                LIBRARY_OUTPUT_DIRECTORY "${OCTOMAP_OUTPUT_DIR}"
                ARCHIVE_OUTPUT_DIRECTORY "${OCTOMAP_OUTPUT_DIR}"
                RUNTIME_OUTPUT_DIRECTORY "${OCTOMAP_OUTPUT_DIR}"
        )
    endif()
endforeach()

add_library(
    example-cpp-custom-layer MODULE
    ${PROJECT_SOURCE_DIR}/example_custom_layer.cpp
    ${PROJECT_SOURCE_DIR}/octomap_renderer.cpp
)

target_include_directories(
    example-cpp-custom-layer
    PRIVATE
        ${PROJECT_SOURCE_DIR}
        ${octomap_SOURCE_DIR}/octomap/include
)

# Use the same compile_options as in mapbox-gl
target_compile_options(
    example-cpp-custom-layer
    PRIVATE
        -Wno-error=return-type
        $<$<CONFIG:Release>:-Oz>
        $<$<CONFIG:Release>:-Qunused-arguments>
        $<$<CONFIG:Release>:-flto>
        $<$<CONFIG:Release>:-fvisibility=hidden>
        $<$<CONFIG:Release>:-fvisibility-inlines-hidden>)

# Use the same linker options as in mapbox-gl
target_link_libraries(
    example-cpp-custom-layer
    PRIVATE
        GLESv2
        log
        android
        octomap
        octomath
        $<$<CONFIG:Release>:-Wl,--gc-sections>
        $<$<CONFIG:Release>:-Wl,--icf=all>
        $<$<CONFIG:Release>:-flto>
        $<$<CONFIG:Release>:-fuse-ld=lld>
        $<$<CONFIG:Release>:-Wl,--lto-new-pass-manager>
        $<$<CONFIG:Release>:-Wl,--pack-dyn-relocs=android>
        $<$<CONFIG:Release>:-Wl,--lto-O3>
)
