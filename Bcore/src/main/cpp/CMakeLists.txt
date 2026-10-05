# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.5.1)

# AGP 所有变体都用 RelWithDebInfo（-O2 -g）编 native 且打包时不再自动剥离，
# 这里把 RelWithDebInfo 的编译选项对齐 Release（去掉 -g）；链接加 -s 在链接期直接剥掉符号表
set(CMAKE_C_FLAGS_RELWITHDEBINFO "-O2 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O2 -DNDEBUG")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -s")

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
include_directories(JniHook)
include_directories(./)

aux_source_directory(./ SRC1)
aux_source_directory(Utils SRC2)
aux_source_directory(Hook SRC3)
aux_source_directory(JniHook SRC4)
# 新增源文件后需要重新 configure 才能被 aux_source_directory 收录
add_compile_options(-w)
add_library( # Sets the name of the library.
        blackbox
        # Sets the library as a shared library.
        SHARED
        # Provides a relative path to your source file(s).
        BoxCore.cpp
        ${SRC1}
        ${SRC2}
        ${SRC3}
        ${SRC4}
        )

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
        blackbox
        # Links the target library to the log library
        # included in the NDK.
        ${log-lib}
        z
        )
