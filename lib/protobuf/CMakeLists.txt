
find_program(PROTOC protoc)
if(NOT PROTOC)
	message(FATAL_ERROR "Could not find protoc")
endif()

# add_custom_command(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/takion.pb"
#		COMMAND "${PROTOC}" "-o${CMAKE_CURRENT_BINARY_DIR}/takion.pb" "${CMAKE_CURRENT_SOURCE_DIR}/takion.proto" "-I${CMAKE_CURRENT_SOURCE_DIR}"
#		MAIN_DEPENDENCY "${CMAKE_CURRENT_SOURCE_DIR}/takion.proto")

set(SOURCE_FILES "${CMAKE_CURRENT_BINARY_DIR}/takion.pb.c")
set(HEADER_FILES "${CMAKE_CURRENT_BINARY_DIR}/takion.pb.h")

if(UNIX AND IS_ABSOLUTE "${PROTOC}")
	# make sure protoc is in PATH when invoking the generator below, which needs it.
	get_filename_component(PROTOC_PATH "${PROTOC}" DIRECTORY)
	set(GEN_PREFIX "${CMAKE_COMMAND}" -E env "PATH=${PROTOC_PATH}:$ENV{PATH}")
else()
	set(GEN_PREFIX "")
endif()

#add_custom_command(OUTPUT ${SOURCE_FILES} ${HEADER_FILES}
#		COMMAND ${GEN_PREFIX} "${PYTHON_EXECUTABLE}" "${NANOPB_GENERATOR_PY}" "${CMAKE_CURRENT_BINARY_DIR}/takion.pb"
#		MAIN_DEPENDENCY "${CMAKE_CURRENT_BINARY_DIR}/takion.pb")

set(CHIAKI_LIB_PROTO_SOURCE_FILES "${SOURCE_FILES}" PARENT_SCOPE)
set(CHIAKI_LIB_PROTO_HEADER_FILES "${HEADER_FILES}" PARENT_SCOPE)
set(CHIAKI_LIB_PROTO_INCLUDE_DIR "${CMAKE_CURRENT_BINARY_DIR}" PARENT_SCOPE)

add_custom_target(chiaki-pb DEPENDS ${SOURCE_FILES} ${HEADER_FILES})
# lib/protobuf/CMakeLists.txt

# 源 .proto
set(TAKION_PROTO "${CMAKE_CURRENT_SOURCE_DIR}/takion.proto")
# 生成输出目录（使用当前 build 目录）
set(TAKION_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}")
file(MAKE_DIRECTORY "${TAKION_GEN_DIR}")

# 找 protoc
if(NOT Protobuf_PROTOC_EXECUTABLE OR Protobuf_PROTOC_EXECUTABLE STREQUAL "")
  find_program(Protobuf_PROTOC_EXECUTABLE NAMES protoc)
endif()
if(NOT Protobuf_PROTOC_EXECUTABLE)
  message(FATAL_ERROR "Protobuf_PROTOC_EXECUTABLE is not set and protoc not found in PATH")
endif()

# 预编译 nanopb 插件（bat 或 exe）
if(NOT CHIAKI_NANOPB_PLUGIN OR NOT EXISTS "${CHIAKI_NANOPB_PLUGIN}")
  # 按仓库默认位置猜测
  if(EXISTS "${CMAKE_SOURCE_DIR}/third-party/nanopb/generator-bin/protoc-gen-nanopb.bat")
    set(CHIAKI_NANOPB_PLUGIN "${CMAKE_SOURCE_DIR}/third-party/nanopb/generator-bin/protoc-gen-nanopb.bat")
  elseif(EXISTS "${CMAKE_SOURCE_DIR}/third-party/nanopb/generator-bin/protoc-gen-nanopb.exe")
    set(CHIAKI_NANOPB_PLUGIN "${CMAKE_SOURCE_DIR}/third-party/nanopb/generator-bin/protoc-gen-nanopb.exe")
  endif()
endif()
if(NOT EXISTS "${CHIAKI_NANOPB_PLUGIN}")
  message(FATAL_ERROR "CHIAKI_NANOPB_PLUGIN not found: ${CHIAKI_NANOPB_PLUGIN}")
endif()

# 用 protoc + nanopb 插件直接生成 .pb.c/.h（不走 Python）
add_custom_command(
  OUTPUT 
    "${TAKION_GEN_DIR}/takion.pb.c"
    "${TAKION_GEN_DIR}/takion.pb.h"
  
  COMMAND "${CMAKE_COMMAND}" -E env
    "PATH=D:/web/PeaSyo/third-party/nanopb/generator-bin;$ENV{PATH}"
    "${Protobuf_PROTOC_EXECUTABLE}"
    --proto_path "${CMAKE_CURRENT_SOURCE_DIR}"
    --nanopb_out "${TAKION_GEN_DIR}"
    "${TAKION_PROTO}"
  
  DEPENDS 
    "${TAKION_PROTO}"
  
  VERBATIM
)

# 提供一个静态库，供上层链接
add_library(takion_proto STATIC "${TAKION_GEN_DIR}/takion.pb.c")
target_include_directories(takion_proto PUBLIC "${TAKION_GEN_DIR}")