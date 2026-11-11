# z stands for Zero Hour, i stands for Interface
add_library(zi_gameengine_include INTERFACE)
add_library(zi_libraries_source_wwvegas INTERFACE)
add_library(zi_main INTERFACE)
add_library(zi_always INTERFACE)
add_library(zi_always_no_pch INTERFACE) # Use this for Shared Libs with MFC AFX

target_include_directories(zi_gameengine_include INTERFACE "GameEngine/Include")
target_include_directories(zi_libraries_source_wwvegas INTERFACE "Libraries/Source/WWVegas")
target_include_directories(zi_main INTERFACE "Main")
target_include_directories(zi_always INTERFACE "CompatLib/Include")  # TheSuperHackers @build 08/02/2026 Add DirectX 8 compat headers

target_compile_definitions(zi_always INTERFACE
    RTS_ZEROHOUR=1
)
target_compile_definitions(zi_always_no_pch INTERFACE
    RTS_ZEROHOUR=1
)
target_link_libraries(zi_gameengine_include INTERFACE
    corei_gameengine_include
)
target_link_libraries(zi_always INTERFACE
    corei_always # Must stay below so headers from game are included first
)
target_link_libraries(zi_always_no_pch INTERFACE
    corei_always_no_pch # Must stay below so headers from game are included first
)

# CompatLib: Platform compatibility layer (Linux headers, d3d8lib config)
add_subdirectory(CompatLib)

# Contains internal libraries
add_subdirectory(Libraries)

# Base GameEngine library
add_subdirectory(GameEngine)

# Platform specific GameEngine code
add_subdirectory(GameEngineDevice)

# Contains the main game exe target.
add_subdirectory(Main)

if (RTS_BUILD_ZEROHOUR_TOOLS OR RTS_BUILD_ZEROHOUR_EXTRAS)
    add_subdirectory(Tools)
endif()

# GeneralsX @feature Flux Build 06/11/2026 PLAN-032: the native World Builder (libfluxwb.so, run by
# the launcher's WorldBuilderActivity in its own ":wb" process) and its map round-trip test tool.
# Android only; it links the engine libraries the game is built from and adds nothing to them.
if(ANDROID)
    option(GX_BUILD_FLUXWB "Build the native World Builder (libfluxwb.so)" ON)
    if(GX_BUILD_FLUXWB)
        add_subdirectory(Tools/FluxWB)
    endif()
endif()
