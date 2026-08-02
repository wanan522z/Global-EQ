cmake_minimum_required(VERSION 3.22.1)

project(globalpeq_probe LANGUAGES C)

add_library(globalpeq_probe SHARED dvc_probe_bridge.c)
target_compile_options(globalpeq_probe PRIVATE -Wall -Wextra -Werror)
