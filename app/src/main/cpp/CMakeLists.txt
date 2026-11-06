cmake_minimum_required(VERSION 3.22)
project(ReveriePaintNative)

# Direct WSL/NDK builds need the same optimized engine as Gradle's native mode.
# An unspecified configuration otherwise silently compiles the pixel loops at O0.
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Native engine build configuration" FORCE)
endif()
add_compile_options($<$<CONFIG:Release>:-O2>)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Krita headers use the C++ alternative tokens (and/or/xor) as identifiers;
# disable operator-name keywords like the rest of the Krita build does.
add_compile_options(-fno-operator-names)

# Native build environment (developers only - the default prebuilt-jni build
# does not compile C++). Paths default to $HOME conventions but can be
# overridden per machine via -D, e.g.:
#   ./gradlew assembleDebug -PbuildNative #     -PcmakeArgs=-DQT_ANDROID_DIR=/opt/Qt6/6.6.3/android_arm64_v8a
if(NOT ANDROID_ABI)
    set(ANDROID_ABI "arm64-v8a")
endif()

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(DEFAULT_QT_ARCH "android_armv7")
    set(DEFAULT_KRITA_BIN "$ENV{HOME}/Projects/krita-source/build-android-armv7")
    set(DEFAULT_DEPS "$ENV{HOME}/ReveriePaint-android/deps-armv7")
    set(NDK_TARGET_TRIPLE "arm-linux-androideabi")
else()
    set(DEFAULT_QT_ARCH "android_arm64_v8a")
    set(DEFAULT_KRITA_BIN "$ENV{HOME}/Projects/krita-source/build-android")
    set(DEFAULT_DEPS "$ENV{HOME}/ReveriePaint-android/deps")
    set(NDK_TARGET_TRIPLE "aarch64-linux-android")
endif()

set(QT_ANDROID_DIR "$ENV{HOME}/Qt6/6.6.3/${DEFAULT_QT_ARCH}" CACHE PATH "Qt for Android install dir")
set(KRITA_BIN_DIR "${DEFAULT_KRITA_BIN}" CACHE PATH "Krita cross-compile build dir")
set(KRITA_SRC_DIR "$ENV{HOME}/Projects/krita-source" CACHE PATH "Krita source tree")
set(DEPS_DIR "${DEFAULT_DEPS}" CACHE PATH "KF6/deps install prefix")

# --- Krita core include dirs (same set the working APK build used) ---
set(KRITA_INCLUDES
    ${KRITA_SRC_DIR}
    ${KRITA_SRC_DIR}/libs
    ${KRITA_SRC_DIR}/libs/global
    ${KRITA_SRC_DIR}/libs/image
    ${KRITA_SRC_DIR}/libs/pigment
    ${KRITA_SRC_DIR}/libs/ui
    ${KRITA_SRC_DIR}/libs/flake
    ${KRITA_SRC_DIR}/libs/widgetutils
    ${KRITA_SRC_DIR}/libs/widgets
    ${KRITA_BIN_DIR}
    ${KRITA_BIN_DIR}/libs/global
    ${KRITA_BIN_DIR}/libs/image
    ${KRITA_BIN_DIR}/libs/pigment
    ${KRITA_BIN_DIR}/libs/ui
    ${KRITA_BIN_DIR}/libs/flake
    ${KRITA_BIN_DIR}/libs/widgetutils
    ${KRITA_BIN_DIR}/libs/widgets
    ${KRITA_BIN_DIR}/libs/store
    ${KRITA_BIN_DIR}/libs/resources
    ${KRITA_BIN_DIR}/libs/resourcewidgets
    ${KRITA_BIN_DIR}/libs/psd
    ${KRITA_BIN_DIR}/libs/psdutils
    ${KRITA_BIN_DIR}/libs/version
    ${KRITA_BIN_DIR}/libs/metadata
    ${KRITA_BIN_DIR}/libs/libbrush
    ${KRITA_BIN_DIR}/libs/brush
    ${KRITA_BIN_DIR}/libs/command
    ${KRITA_BIN_DIR}/libs/koplugin
    ${KRITA_BIN_DIR}/libs/color
    ${KRITA_BIN_DIR}/libs/multiarch
    ${KRITA_BIN_DIR}/libs/impex
    ${KRITA_SRC_DIR}/libs/brush
    ${KRITA_SRC_DIR}/libs/color
    ${KRITA_SRC_DIR}/libs/command
    ${KRITA_SRC_DIR}/libs/flake/commands
    ${KRITA_SRC_DIR}/libs/flake/resources
    ${KRITA_SRC_DIR}/libs/flake/svg
    ${KRITA_SRC_DIR}/libs/flake/text
    ${KRITA_SRC_DIR}/libs/flake/tools
    ${KRITA_SRC_DIR}/libs/image/commands
    ${KRITA_SRC_DIR}/libs/image/brushengine
    ${KRITA_SRC_DIR}/plugins/paintops/libpaintop
    ${KRITA_SRC_DIR}/plugins/paintops/defaultpaintops
    ${KRITA_SRC_DIR}/plugins/paintops/defaultpaintops/brush
    ${KRITA_SRC_DIR}/plugins/paintops/defaultpaintops/duplicate
    ${KRITA_SRC_DIR}/plugins/paintops/roundmarker
    ${KRITA_SRC_DIR}/plugins/paintops/spray
    ${KRITA_SRC_DIR}/plugins/paintops/sketch
    ${KRITA_SRC_DIR}/plugins/paintops/deform
    ${KRITA_SRC_DIR}/plugins/paintops/filterop
    ${KRITA_SRC_DIR}/plugins/paintops/gridbrush
    ${KRITA_SRC_DIR}/plugins/paintops/experiment
    ${KRITA_SRC_DIR}/plugins/paintops/particle
    ${KRITA_SRC_DIR}/plugins/paintops/curvebrush
    ${KRITA_SRC_DIR}/plugins/paintops/tangentnormal
    ${KRITA_SRC_DIR}/plugins/paintops/hairy
    ${KRITA_SRC_DIR}/plugins/paintops/hatching
    ${KRITA_SRC_DIR}/plugins/paintops/mypaint
    ${DEPS_DIR}/include/libmypaint
    ${KRITA_BIN_DIR}/plugins/paintops/libpaintop
    ${KRITA_BIN_DIR}/plugins/paintops/defaultpaintops
    ${KRITA_SRC_DIR}/libs/image/filter
    ${KRITA_SRC_DIR}/libs/image/generator
    ${KRITA_SRC_DIR}/libs/image/layerstyles
    ${KRITA_SRC_DIR}/libs/image/processing
    ${KRITA_SRC_DIR}/libs/impex
    ${KRITA_SRC_DIR}/libs/koplugin
    ${KRITA_SRC_DIR}/libs/metadata
    ${KRITA_SRC_DIR}/libs/multiarch
    ${KRITA_SRC_DIR}/libs/pigment/compositeops
    ${KRITA_SRC_DIR}/libs/pigment/resources
    ${KRITA_SRC_DIR}/libs/psdutils
    ${KRITA_SRC_DIR}/libs/resources
    ${KRITA_SRC_DIR}/libs/resourcewidgets
    ${KRITA_SRC_DIR}/libs/store
    ${KRITA_SRC_DIR}/libs/surfacecolormanagementapi
    ${KRITA_SRC_DIR}/libs/ui/canvas
    ${KRITA_SRC_DIR}/libs/ui/flake
    ${KRITA_SRC_DIR}/libs/ui/ora
    ${KRITA_SRC_DIR}/libs/ui/tool
    ${KRITA_SRC_DIR}/libs/ui/tool/strokes
    ${KRITA_SRC_DIR}/libs/ui/utils
    ${KRITA_SRC_DIR}/libs/ui/widgets
    ${KRITA_SRC_DIR}/libs/version
    ${KRITA_SRC_DIR}/libs/widgetutils/config
    ${KRITA_SRC_DIR}/libs/widgetutils/xmlgui
    ${DEPS_DIR}/include
    ${DEPS_DIR}/include/KF6
    ${DEPS_DIR}/include/KF6/KConfig
    ${DEPS_DIR}/include/KF6/KConfigCore
    ${DEPS_DIR}/include/KF6/KConfigGui
    ${DEPS_DIR}/include/KF6/KGuiAddons
    ${DEPS_DIR}/include/KF6/KI18n
    ${DEPS_DIR}/include/KF6/KColorScheme
    ${DEPS_DIR}/include/KF6/KCoreAddons
    ${DEPS_DIR}/include/KF6/KWidgetsAddons
    ${DEPS_DIR}/include/KF6/KItemViews
    ${DEPS_DIR}/include/KF6/KArchive
    ${DEPS_DIR}/include/KF6/KCodecs
    ${DEPS_DIR}/include/KF6/KCompletion
    ${DEPS_DIR}/include/freetype2
    ${DEPS_DIR}/include/libunibreak
    ${DEPS_DIR}/include/eigen3
    ${KRITA_SRC_DIR}/libs/psd
    ${KRITA_SRC_DIR}/plugins/impex/psd
    ${KRITA_BIN_DIR}/plugins/impex/psd
)

# Qt modules needed by the Krita core
set(QT_LIBS
    ${QT_ANDROID_DIR}/lib/libQt6Core_${ANDROID_ABI}.so
    ${QT_ANDROID_DIR}/lib/libQt6Gui_${ANDROID_ABI}.so
    ${QT_ANDROID_DIR}/lib/libQt6Widgets_${ANDROID_ABI}.so
    ${QT_ANDROID_DIR}/lib/libQt6Svg_${ANDROID_ABI}.so
    ${QT_ANDROID_DIR}/lib/libQt6OpenGL_${ANDROID_ABI}.so
    ${QT_ANDROID_DIR}/lib/libQt6Network_${ANDROID_ABI}.so
    ${QT_ANDROID_DIR}/lib/libQt6Concurrent_${ANDROID_ABI}.so
    ${QT_ANDROID_DIR}/lib/libQt6Xml_${ANDROID_ABI}.so
    ${QT_ANDROID_DIR}/lib/libQt6Sql_${ANDROID_ABI}.so
    ${QT_ANDROID_DIR}/lib/libQt6Core5Compat_${ANDROID_ABI}.so
)

# Krita core libs needed by the engine. We deliberately do NOT link the
# full kritaui/tool-plugin set: ReverieCore only uses KisImage/KisPainter/
# layer APIs which live in these core libraries.
# Android 上 Krita 的 MODULE 库实际输出成 <name>.so (没有 lib 前缀), 但历史链接清单
# 写的是 lib<name>.so。旧构建目录里恰好留着更早版本生成的同名符号链接, 所以一直没暴露;
# 干净重建 (如重编 armeabi-v7a 闭包) 之后链接期就会报 "missing and no known rule"。
# 这里按实际文件名解析, 两种布局都支持。
function(reverie_krita_module out_var name)
    set(_with_prefix "${KRITA_BIN_DIR}/lib/lib${name}.so")
    set(_plain "${KRITA_BIN_DIR}/lib/${name}.so")
    # CMake 会把落在 target_link_directories 里的绝对 .so 路径折成 -l<name>,
    # 于是链接器去找 lib<name>.so; 而 Android 构建输出的是无 lib 前缀的 MODULE 库。
    # 缺这个符号链接时补一个 (幂等), 旧构建目录里正是靠历史遗留的链接才没暴露。
    if(NOT EXISTS "${_with_prefix}" AND EXISTS "${_plain}")
        execute_process(COMMAND ${CMAKE_COMMAND} -E create_symlink "${_plain}" "${_with_prefix}")
    endif()
    if(EXISTS "${_with_prefix}")
        set(${out_var} "${_with_prefix}" PARENT_SCOPE)
    elseif(EXISTS "${_plain}")
        set(${out_var} "${_plain}" PARENT_SCOPE)
    else()
        message(FATAL_ERROR "找不到 Krita 模块库 ${name}.so (目录 ${KRITA_BIN_DIR}/lib)")
    endif()
endfunction()

set(_KRITA_MODULE_PATHS "")
foreach(_m
        kritacolorsmudgepaintop kritaroundmarkerpaintop kritaspraypaintop
        kritasketchpaintop kritadeformpaintop kritafilterop kritagridpaintop
        kritaexperimentpaintop kritaparticlepaintop kritacurvepaintop
        kritatangentnormalpaintop kritahairypaintop kritahatchingpaintop
        kritamypaintop)
    reverie_krita_module(_p ${_m})
    list(APPEND _KRITA_MODULE_PATHS "${_p}")
endforeach()

set(KRITA_LIBS
    ${KRITA_BIN_DIR}/lib/libkritaimage.so
    ${KRITA_BIN_DIR}/lib/libkritapigment.so
    ${KRITA_BIN_DIR}/lib/libkritaglobal.so
    # 注意: 不要再链接 libkritacolor.so —— 该库对应的 libs/color (KisColorManager) 已被
    # Krita 上游移除, 构建目录里那一份是旧版本残留; 继续链接会把旧库拖进闭包,
    # 且 armv7 干净重建后根本没有这个文件 (1.6.2 闭包混入旧库的同类问题)。
    ${KRITA_BIN_DIR}/lib/libkritacommand.so
    ${KRITA_BIN_DIR}/lib/libkritastore.so
    ${KRITA_BIN_DIR}/lib/libkritaresources.so
    ${KRITA_BIN_DIR}/lib/libkritamultiarch.so
    ${KRITA_BIN_DIR}/lib/libkritapsdutils.so
    ${KRITA_BIN_DIR}/lib/libkritapsd.so
    ${KRITA_BIN_DIR}/lib/libkritametadata.so
    ${KRITA_BIN_DIR}/lib/libkritawidgetutils.so
    ${KRITA_BIN_DIR}/lib/libkritawidgets.so
    ${KRITA_BIN_DIR}/lib/libkritaresourcewidgets.so
    ${KRITA_BIN_DIR}/lib/libkritaflake.so
    ${KRITA_BIN_DIR}/lib/libkritalibbrush.so
    ${KRITA_BIN_DIR}/lib/libkritaui.so
    ${KRITA_BIN_DIR}/lib/libkritalibpaintop.so
    ${KRITA_BIN_DIR}/lib/libkritadefaultpaintops_static.a
    ${_KRITA_MODULE_PATHS}
)

# Some RGB8 Android pigment bundles have no OpenEXR/half exports even when
# their generated KoConfig.h enables it. Keep the default upstream behavior;
# allow these bundles to compile the masking factory without unsupported FLOAT16.
option(REVERIE_MASKING_FLOAT16 "Masking factory supports the pigment library FLOAT16 ABI" ON)
set(REVERIE_MASK_FACTORY ${KRITA_SRC_DIR}/libs/ui/tool/strokes/KisMaskingBrushCompositeOpFactory.cpp)
if(NOT REVERIE_MASKING_FLOAT16)
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${REVERIE_MASK_FACTORY}")
    file(READ ${REVERIE_MASK_FACTORY} REVERIE_MASK_FACTORY_TEXT)
    string(REPLACE "#ifdef HAVE_OPENEXR" "#if 0 // Android pigment bundle has no FLOAT16 ABI"
        REVERIE_MASK_FACTORY_TEXT "${REVERIE_MASK_FACTORY_TEXT}")
    set(REVERIE_MASK_FACTORY ${CMAKE_CURRENT_BINARY_DIR}/KisMaskingBrushCompositeOpFactoryRgb.cpp)
    file(WRITE ${REVERIE_MASK_FACTORY} "${REVERIE_MASK_FACTORY_TEXT}")
endif()

# 核心库: 引擎实现 (不含任何 Java_ 符号)。JNI 层通过 NEEDED 依赖它,
# 这样「Kotlin <-> 引擎」的边界固定在 C ABI (reverie_core.h) 上。
add_library(reverie_core SHARED
    ReverieCoreDocument.cpp
    ReverieCoreLayers.cpp
    ReverieCoreLayerOps.cpp
    ReverieCoreFilters.cpp
    ReverieCoreFilterPlugins.cpp
    ReverieCoreFilterKernels.cpp
    ReverieCoreFilterRegistry.cpp
    ReverieCoreAdjustment.cpp
    ReverieCoreGenerators.cpp
    ReverieCorePatternFill.cpp
    ReverieCoreLayerIO.cpp
    ReverieCoreFilterPreview.cpp
    ReverieCoreFilterPreviewFx.cpp
    ReverieCoreSelection.cpp
    ReverieCoreSelectionTools.cpp
    ReverieCoreSelectionLasso.cpp
    ReverieCoreBrush.cpp
    ReverieCoreStroke.cpp
    ReverieCoreUndoStore.cpp
    ReverieCoreRender.cpp
    ReverieCoreTransform.cpp
    ReverieCoreMiscTools.cpp
    ReverieCoreAnimation.cpp
    ReverieCoreInbetween.cpp
    ReverieCoreIO.cpp
    ReverieCoreIccData.cpp
    ReverieCoreColorSpaceHook.cpp
    ReverieCoreApi.cpp
    ReverieCoreLayerExtras.cpp
    ReverieCorePanel.cpp
    ReverieCoreInstance.cpp
    ReverieCrashHandler.cpp
    ${KRITA_SRC_DIR}/plugins/impex/psd/psd_colormode_block.cpp
    ${KRITA_SRC_DIR}/plugins/impex/psd/psd_image_data.cpp
    # Android 定制版: 掩膜设备色彩空间带 GrayA→alpha8 空指针兜底
    # (上游版本无条件查询 GrayA/U8, Android 无 lcms2 插件与 Gray ICC 时返回
    #  nullptr, 水彩类预设落笔即 SIGSEGV)。详见文件头注释。
    ReverieMaskingBrushRenderer.cpp
    ${REVERIE_MASK_FACTORY}
)

# JNI 薄层: 只做 Java 类型与 C/C++ 之间的搬运
add_library(reverie_jni SHARED
    reverie_jni_core.cpp
    reverie_jni_layers.cpp
    reverie_jni_snapshot.cpp
    reverie_jni_filters.cpp
    reverie_jni_selection.cpp
    reverie_jni_brush.cpp
    reverie_jni_tools.cpp
    reverie_jni_io.cpp
    reverie_jni_panel.cpp
    reverie_jni_animation.cpp
)

set(REVERIE_INCLUDE_DIRS
    ${KRITA_INCLUDES}
    ${QT_ANDROID_DIR}/include
    ${QT_ANDROID_DIR}/include/QtCore
    ${QT_ANDROID_DIR}/include/QtGui
    ${QT_ANDROID_DIR}/include/QtWidgets
    ${QT_ANDROID_DIR}/include/QtXml
    ${QT_ANDROID_DIR}/include/QtNetwork
    ${QT_ANDROID_DIR}/include/QtSvg
    ${QT_ANDROID_DIR}/include/QtSql
    ${QT_ANDROID_DIR}/include/QtOpenGL
    ${QT_ANDROID_DIR}/include/QtConcurrent
    ${QT_ANDROID_DIR}/include/QtCore5Compat
    ${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include
    ${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/${NDK_TARGET_TRIPLE}
)

# All prebuilt dependency dirs so the linker can resolve transitive NEEDED
set(LINK_DIRS
    ${KRITA_BIN_DIR}/lib
    ${QT_ANDROID_DIR}/lib
    ${DEPS_DIR}/lib
    ${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${NDK_TARGET_TRIPLE}/24
)

foreach(_tgt reverie_core reverie_jni)
    target_include_directories(${_tgt} PRIVATE ${REVERIE_INCLUDE_DIRS})
    target_link_directories(${_tgt} PRIVATE ${LINK_DIRS})
    # 16KB page size alignment for Android 15+ compatibility
    target_link_options(${_tgt} PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_libraries(${_tgt}
        ${KRITA_LIBS}
        ${QT_LIBS}
        ${DEPS_DIR}/lib/libKF6I18n.so
        ${DEPS_DIR}/lib/libKF6CoreAddons.so
        ${DEPS_DIR}/lib/libKF6WidgetsAddons.so
        ${DEPS_DIR}/lib/libKF6ItemViews.so
        ${DEPS_DIR}/lib/libKF6Completion.so
        ${DEPS_DIR}/lib/libKF6GuiAddons.so
        ${DEPS_DIR}/lib/libKF6ConfigCore.so
        ${DEPS_DIR}/lib/libmypaint.so
        ${DEPS_DIR}/lib/libjson-c.so
        z
        log
        android
        jnigraphics
    )
    # Android platform libs
    target_link_libraries(${_tgt} -lz -lpng16 -lexpat)
endforeach()

# JNI 只依赖核心库导出的符号 (core() / ReverieCore / C ABI)
target_link_libraries(reverie_jni reverie_core)

add_custom_command(TARGET reverie_jni POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:reverie_jni>
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libreverie_jni.so
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:reverie_core>
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libreverie_core.so
)
