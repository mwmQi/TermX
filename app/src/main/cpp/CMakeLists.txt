cmake_minimum_required(VERSION 3.18.1)

project("termx-native")

# PTY library
add_library(
    termx-pty
    SHARED
    termx_pty.c
)
target_link_libraries(
    termx-pty
    android
    log
)

# X11 Virtual Display Server library
add_library(
    termx-x11
    SHARED
    termx_x11.c
)
target_link_libraries(
    termx-x11
    android
    log
)
