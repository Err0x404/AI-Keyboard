cmake_minimum_required(VERSION 3.20.1)
project(engine-rime)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(RIME_INCLUDE_DIR "${CMAKE_CURRENT_LIST_DIR}/include")
set(RIME_SOURCE_DIR "" CACHE PATH "Optional librime source checkout")

set(RIME_LINKED_LIBRARY "")
if(RIME_SOURCE_DIR AND EXISTS "${RIME_SOURCE_DIR}/CMakeLists.txt")
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(BUILD_TEST OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_STATIC ON CACHE BOOL "" FORCE)
    set(BUILD_MERGED_PLUGINS OFF CACHE BOOL "" FORCE)
    set(BUILD_SEPARATE_LIBS OFF CACHE BOOL "" FORCE)
    set(ENABLE_LOGGING ON CACHE BOOL "" FORCE)
    set(ENABLE_THREADING ON CACHE BOOL "" FORCE)
    set(ENABLE_TIMESTAMP ON CACHE BOOL "" FORCE)

    set(YAML_CPP_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(YAML_CPP_INSTALL OFF CACHE BOOL "" FORCE)
    add_subdirectory("${RIME_SOURCE_DIR}/deps/spdlog" spdlog EXCLUDE_FROM_ALL)
    add_subdirectory("${RIME_SOURCE_DIR}/deps/yaml-cpp" yaml-cpp EXCLUDE_FROM_ALL)
    set(HAVE_SNAPPY FALSE)
    set(LEVELDB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LEVELDB_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
    set(LEVELDB_INSTALL OFF CACHE BOOL "" FORCE)
    add_subdirectory("${RIME_SOURCE_DIR}/deps/leveldb" leveldb EXCLUDE_FROM_ALL)

    set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
    set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    add_subdirectory("${RIME_SOURCE_DIR}/deps/marisa-trie" marisa EXCLUDE_FROM_ALL)

    set(LEVELDB_BUILD_TESTS OFF)
    set(OPENCC_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(OPENCC_BUILD_TESTS OFF)
    set(USE_SYSTEM_MARISA ON CACHE BOOL "" FORCE)
    set(LIBMARISA marisa CACHE FILEPATH "" FORCE)
    set(OPENCC_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
    set(OPENCC_BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)
    set(OPENCC_DICT_FORMAT "ocd2" CACHE STRING "" FORCE)
    add_subdirectory("${RIME_SOURCE_DIR}/deps/opencc" opencc EXCLUDE_FROM_ALL)
    set_property(
        DIRECTORY "${CMAKE_BINARY_DIR}/opencc/data"
        PROPERTY EXCLUDE_FROM_ALL TRUE)

    set(BOOST_VERSION "1.92.0")
    set(BOOST_ARCHIVE "${CMAKE_BINARY_DIR}/boost-${BOOST_VERSION}-cmake.tar.xz")
    if(NOT EXISTS "${CMAKE_BINARY_DIR}/boost")
        if(NOT EXISTS "${BOOST_ARCHIVE}")
            file(DOWNLOAD
                "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
                "${BOOST_ARCHIVE}"
                EXPECTED_HASH
                    SHA256=9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d
                SHOW_PROGRESS)
        endif()
        file(ARCHIVE_EXTRACT INPUT "${BOOST_ARCHIVE}" DESTINATION "${CMAKE_BINARY_DIR}")
        file(RENAME "${CMAKE_BINARY_DIR}/boost-${BOOST_VERSION}" "${CMAKE_BINARY_DIR}/boost")
    endif()
    set(BOOST_INCLUDE_LIBRARIES
        algorithm crc dll interprocess preprocessor range regex scope_exit
        signals2 utility uuid vmd)
    add_subdirectory("${CMAKE_BINARY_DIR}/boost" boost EXCLUDE_FROM_ALL)
    file(GLOB BOOST_MODULAR_INCLUDE_DIRS
        LIST_DIRECTORIES true
        "${CMAKE_BINARY_DIR}/boost/libs/*/include")
    include_directories(SYSTEM ${BOOST_MODULAR_INCLUDE_DIRS})
    set(Boost_FOUND TRUE)
    set(Boost_INCLUDE_DIRS ${BOOST_MODULAR_INCLUDE_DIRS})

    set(LevelDb_FOUND TRUE)
    set(LevelDb_INCLUDE_PATH "${RIME_SOURCE_DIR}/deps/leveldb/include")
    set(LevelDb_LIBRARY leveldb)
    set(YamlCpp_FOUND TRUE)
    set(YamlCpp_INCLUDE_PATH "${RIME_SOURCE_DIR}/deps/yaml-cpp/include")
    set(YamlCpp_NEW_API "${RIME_SOURCE_DIR}/deps/yaml-cpp/include/yaml-cpp/node/node.h")
    set(YamlCpp_LIBRARY yaml-cpp)
    set(Marisa_FOUND TRUE)
    set(Marisa_INCLUDE_PATH "${RIME_SOURCE_DIR}/deps/marisa-trie/lib")
    set(Marisa_LIBRARY marisa)
    set(Opencc_FOUND TRUE)
    set(Opencc_INCLUDE_PATH "${RIME_SOURCE_DIR}/deps/opencc/src")
    set(Opencc_LIBRARY libopencc)
    file(GLOB OPENCC_HEADERS
        "${RIME_SOURCE_DIR}/deps/opencc/src/*.hpp")
    file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/include/opencc")
    foreach(OPENCC_HEADER ${OPENCC_HEADERS})
        get_filename_component(OPENCC_HEADER_NAME "${OPENCC_HEADER}" NAME)
        configure_file(
            "${OPENCC_HEADER}"
            "${CMAKE_BINARY_DIR}/include/opencc/${OPENCC_HEADER_NAME}"
            COPYONLY)
    endforeach()
    include_directories(SYSTEM "${CMAKE_BINARY_DIR}/include")

    set(RIME_DATA_DIR "" CACHE STRING "" FORCE)
    add_subdirectory("${RIME_SOURCE_DIR}" librime EXCLUDE_FROM_ALL)
    if(TARGET rime-static)
        set(RIME_LINKED_LIBRARY rime-static)
        if(TARGET Boost::regex)
            target_link_libraries(rime-static PUBLIC Boost::regex)
        endif()
    endif()
else()
    set(RIME_ROOT "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}")
    find_library(RIME_LIBRARY NAMES rime librime PATHS "${RIME_ROOT}" NO_DEFAULT_PATH)
    if(RIME_LIBRARY)
        set(RIME_LINKED_LIBRARY "${RIME_LIBRARY}")
    endif()
endif()

add_library(rime_jni SHARED rime_jni.cpp)
target_include_directories(rime_jni PRIVATE "${RIME_INCLUDE_DIR}")
target_link_libraries(rime_jni log)

if(RIME_LINKED_LIBRARY)
    target_compile_definitions(rime_jni PRIVATE RIME_LINKED=1)
    if(RIME_SOURCE_DIR AND TARGET rime-static)
        target_link_libraries(rime_jni rime-static)
    else()
        target_link_libraries(rime_jni "${RIME_LINKED_LIBRARY}")
    endif()
else()
    message(WARNING "librime source is unavailable and no librime.so was found; building an unavailable Rime stub.")
endif()