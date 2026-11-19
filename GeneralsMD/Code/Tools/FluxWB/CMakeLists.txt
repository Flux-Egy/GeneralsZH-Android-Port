# GeneralsX @feature Flux Build 06/11/2026 PLAN-032: the native World Builder.
#
# EA's World Builder (../WorldBuilder) is an MFC program. Its document/terrain/map-writing files are
# compiled here UNCHANGED; the MFC types and dialogs they touch are replaced by small stand-ins in
# compat/, which is searched before ../WorldBuilder/include so a stand-in header wins over EA's. A map
# is therefore read and written by EA's code (WorldHeightMapEdit::saveToFile, DataChunkOutput,
# CompressionManager) and by nothing else.
#
#   fluxwb_core      the shared part: subsystem start-up, map open/save, the EA files.
#   fluxwb           libfluxwb.so, loaded by WorldBuilderActivity (an SDLActivity in the ":wb"
#                    process): SDL_main, the 3D view (WW3D2 over d3d8gles) and the JNI calls the
#                    Java tool bar makes.
#   fluxwb_roundtrip a command-line tool: open each map, save it with EA's code, compare the two.

set(FLUXWB_EA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../WorldBuilder")

add_library(fluxwb_core STATIC)

target_sources(fluxwb_core PRIVATE
    # EA's World Builder, unmodified.
    ${FLUXWB_EA_DIR}/src/WHeightMapEdit.cpp
    ${FLUXWB_EA_DIR}/src/WBHeightMap.cpp
    ${FLUXWB_EA_DIR}/src/MapPreview.cpp
    ${FLUXWB_EA_DIR}/src/DrawObject.cpp
    ${FLUXWB_EA_DIR}/src/Tool.cpp
    ${FLUXWB_EA_DIR}/src/CUndoable.cpp
    ${FLUXWB_EA_DIR}/src/PolygonTool.cpp
    ${FLUXWB_EA_DIR}/src/PointerTool.cpp
    ${FLUXWB_EA_DIR}/src/MoundTool.cpp
    ${FLUXWB_EA_DIR}/src/FeatherTool.cpp
    ${FLUXWB_EA_DIR}/src/BrushTool.cpp
    ${FLUXWB_EA_DIR}/src/ObjectTool.cpp
    ${FLUXWB_EA_DIR}/src/WaypointTool.cpp
    # Flux Build.
    src/FluxWBMfc.cpp
    src/FluxWBDoc.cpp
    src/FluxWBCore.cpp
    src/FluxWBGlobals.cpp
    src/FluxWBPanels.cpp
    src/FluxWBTools.cpp
    src/FluxWBScripts.cpp
    src/FluxWBView.cpp
    ${CMAKE_SOURCE_DIR}/Core/Main/ReferenceFloatMath.cpp
)

target_include_directories(fluxwb_core BEFORE PUBLIC
    # First: makes EA's "../../GameEngine/Include/..." includes land on relfix/GameEngine (see there).
    ${CMAKE_CURRENT_SOURCE_DIR}/relfix/a/b
    ${CMAKE_CURRENT_SOURCE_DIR}/compat
    # EA's headers include "../../GameEngine/Include/..." relative to the WorldBuilder folder, which
    # EA's own CMakeLists puts on the include path; so does this one.
    ${FLUXWB_EA_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${FLUXWB_EA_DIR}/include
    ${FLUXWB_EA_DIR}/res
)

target_compile_definitions(fluxwb_core PUBLIC GX_FLUXWB=1)

target_link_libraries(fluxwb_core PUBLIC
    core_profile_legacy
    d3dx8
    z_gameengine
    z_gameenginedevice
    zi_always
    sdl3lib
    d3d8gles
)

target_precompile_headers(fluxwb_core PRIVATE
    [["Utility/CppMacros.h"]]
    [["always.h"]]
    [["Common/STLTypedefs.h"]]
    [["StdAfx.h"]]
    [["WWCommon.h"]]
)

if(NOT MSVC)
    # As in the game (Main/CMakeLists.txt): the reference float functions must not be rewritten
    # into the builtins they replace, and the PCH's <math.h> must stay out of them.
    set_source_files_properties(${CMAKE_SOURCE_DIR}/Core/Main/ReferenceFloatMath.cpp PROPERTIES
        COMPILE_OPTIONS "-fno-builtin"
        SKIP_PRECOMPILE_HEADERS ON)
endif()

# --- libfluxwb.so -----------------------------------------------------------------------------
add_library(fluxwb SHARED
    src/FluxWBMain.cpp
    src/FluxWBJni.cpp
)
target_link_libraries(fluxwb PRIVATE fluxwb_core log android)
target_precompile_headers(fluxwb PRIVATE
    [["Utility/CppMacros.h"]]
    [["always.h"]]
    [["Common/STLTypedefs.h"]]
    [["StdAfx.h"]]
    [["WWCommon.h"]]
)
set_target_properties(fluxwb PROPERTIES OUTPUT_NAME fluxwb)

# Same reason as libmain.so (Main/CMakeLists.txt): keep the engine's operator new/delete out of the
# dynamic symbol table so they never interpose over SDL3's or the system's.
set(_fluxwb_hide_alloc_map "${CMAKE_CURRENT_BINARY_DIR}/fluxwb_hide_alloc.map")
file(WRITE "${_fluxwb_hide_alloc_map}" [=[
{
  global:
    *;
  local:
    _Znw*;
    _Zna*;
    _Zdl*;
    _Zda*;
};
]=])
target_link_options(fluxwb PRIVATE "-Wl,--version-script,${_fluxwb_hide_alloc_map}")

# --- fluxwb_roundtrip ---------------------------------------------------------------------------
add_executable(fluxwb_roundtrip tools/RoundTrip.cpp)
target_link_libraries(fluxwb_roundtrip PRIVATE fluxwb_core log)
target_precompile_headers(fluxwb_roundtrip PRIVATE
    [["Utility/CppMacros.h"]]
    [["always.h"]]
    [["Common/STLTypedefs.h"]]
    [["StdAfx.h"]]
    [["WWCommon.h"]]
)
