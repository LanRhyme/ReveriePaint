cmake_minimum_required(VERSION 3.22)
project(ReveriePaintNative)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Krita headers use the C++ alternative tokens (and/or/xor) as identifiers;
# disable operator-name keywords like the rest of the Krita build does.
add_compile_options(-fno-operator-names)

# Qt for Android 6.6.3 (runtime libs for the Krita core)
set(QT_ANDROID_DIR $ENV{HOME}/Qt6/6.6.3/android_arm64_v8a)
# Krita cross-compiled arm64 libs
set(KRITA_BIN_DIR $ENV{HOME}/Projects/krita-source/build-android)
set(KRITA_SRC_DIR $ENV{HOME}/Projects/krita-source)
set(DEPS_DIR $ENV{HOME}/ReveriePaint-android/deps)

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
    ${KRITA_SRC_DIR}/libs/image/brushengine
    ${KRITA_SRC_DIR}/plugins/paintops/libpaintop
    ${KRITA_SRC_DIR}/plugins/paintops/defaultpaintops
    ${KRITA_SRC_DIR}/plugins/paintops/defaultpaintops/brush
    ${KRITA_SRC_DIR}/plugins/paintops/defaultpaintops/duplicate
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
)

# Qt modules needed by the Krita core
set(QT_LIBS
    ${QT_ANDROID_DIR}/lib/libQt6Core_arm64-v8a.so
    ${QT_ANDROID_DIR}/lib/libQt6Gui_arm64-v8a.so
    ${QT_ANDROID_DIR}/lib/libQt6Widgets_arm64-v8a.so
    ${QT_ANDROID_DIR}/lib/libQt6Network_arm64-v8a.so
    ${QT_ANDROID_DIR}/lib/libQt6Concurrent_arm64-v8a.so
    ${QT_ANDROID_DIR}/lib/libQt6Xml_arm64-v8a.so
    ${QT_ANDROID_DIR}/lib/libQt6Sql_arm64-v8a.so
    ${QT_ANDROID_DIR}/lib/libQt6Core5Compat_arm64-v8a.so
)

# Krita core libs needed by the engine. We deliberately do NOT link the
# full kritaui/tool-plugin set: ReverieCore only uses KisImage/KisPainter/
# layer APIs which live in these core libraries.
set(KRITA_LIBS
    ${KRITA_BIN_DIR}/lib/libkritaimage.so
    ${KRITA_BIN_DIR}/lib/libkritapigment.so
    ${KRITA_BIN_DIR}/lib/libkritaglobal.so
    ${KRITA_BIN_DIR}/lib/libkritacolor.so
    ${KRITA_BIN_DIR}/lib/libkritacommand.so
    ${KRITA_BIN_DIR}/lib/libkritastore.so
    ${KRITA_BIN_DIR}/lib/libkritaresources.so
    ${KRITA_BIN_DIR}/lib/libkritamultiarch.so
    ${KRITA_BIN_DIR}/lib/libkritapsdutils.so
    ${KRITA_BIN_DIR}/lib/libkritametadata.so
    ${KRITA_BIN_DIR}/lib/libkritawidgetutils.so
    ${KRITA_BIN_DIR}/lib/libkritawidgets.so
    ${KRITA_BIN_DIR}/lib/libkritaresourcewidgets.so
    ${KRITA_BIN_DIR}/lib/libkritaflake.so
    ${KRITA_BIN_DIR}/lib/libkritalibbrush.so
    ${KRITA_BIN_DIR}/lib/libkritaui.so
    ${KRITA_BIN_DIR}/lib/libkritalibpaintop.so
    ${KRITA_BIN_DIR}/lib/libkritadefaultpaintops_static.a
)

add_library(reverie_jni SHARED
    reverie_jni.cpp
    ReverieCore.cpp
)

target_include_directories(reverie_jni PRIVATE
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
    ${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/aarch64-linux-android
)

# All prebuilt dependency dirs so the linker can resolve transitive NEEDED
set(LINK_DIRS
    ${KRITA_BIN_DIR}/lib
    ${QT_ANDROID_DIR}/lib
    ${DEPS_DIR}/lib
    ${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/24
)
target_link_directories(reverie_jni PRIVATE ${LINK_DIRS})

target_link_libraries(reverie_jni
    ${KRITA_LIBS}
    ${QT_LIBS}
    ${DEPS_DIR}/lib/libKF6I18n.so
    ${DEPS_DIR}/lib/libKF6CoreAddons.so
    ${DEPS_DIR}/lib/libKF6WidgetsAddons.so
    ${DEPS_DIR}/lib/libKF6ItemViews.so
    ${DEPS_DIR}/lib/libKF6Completion.so
    ${DEPS_DIR}/lib/libKF6GuiAddons.so
    ${DEPS_DIR}/lib/libKF6ConfigCore.so
    z
    log
    android
    jnigraphics
)

# Android platform libs
target_link_libraries(reverie_jni
    -lz -lpng16 -lexpat
)

