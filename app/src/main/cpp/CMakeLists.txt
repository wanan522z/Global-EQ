cmake_minimum_required(VERSION 3.22.1)

project(globalpeq_dvc LANGUAGES C)

add_library(globalpeq_dvc SHARED dvc_raw_bridge.c)

target_compile_options(globalpeq_dvc PRIVATE -Wall -Wextra -Werror)
