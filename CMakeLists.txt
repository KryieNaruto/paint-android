cmake_minimum_required(VERSION 3.22)
project(paint_android LANGUAGES C CXX)

# paint-android：Android UI 消费者（JNI / Compose / Ink 自备）。
# 本骨架只演示如何把 paintDemo SDK 编进消费者的 JNI 库。

# sdk/ 是 git submodule（路径固定 sdk/），指向 paintDemo 仓库。
add_subdirectory(sdk)

# 消费者的 JNI 库（native 胶水，消费者自备实现）。
add_library(paint_android_jni SHARED
    jni/paint_android_jni.cpp
)

# 只链接 dgc_paint。不要链接 SDK 内部 target。
target_link_libraries(paint_android_jni PRIVATE dgc_paint)

# JNI 符号默认隐藏（Android 规范），显式导出 native 方法。
set_target_properties(paint_android_jni PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
