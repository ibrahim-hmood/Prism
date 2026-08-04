# Vendored Hexagon HTP artifacts (from llama.rn, MIT-licensed)

These files are pulled as-is from the `llama.rn@0.12.5` npm package (see `LICENSE-llama.rn`),
which itself builds them from mainline llama.cpp's `ggml-hexagon` backend using a licensed
Qualcomm Hexagon SDK the llama.rn maintainers have access to.

- `skels/arm64-v8a/libggml-htp-v{69,73,75,79,81}.so` — prebuilt Hexagon DSP skel binaries (the
  code that actually runs on the phone's NPU). Compiled with Qualcomm's proprietary Hexagon
  Clang toolchain for the DSP instruction set — impossible to rebuild without the Hexagon SDK,
  so these are vendored as opaque prebuilt binaries, same as llama.rn itself ships them.
- `htp_iface/htp_iface_stub.c` + `htp_iface.h` — the IDL-generated RPC marshalling stub that lets
  the host-side (normal ARM64, NDK-compiled) code talk to the DSP skels above. Normally requires
  the Hexagon SDK's `qidl` tool to generate; pre-generated here so we don't need the SDK just to
  get this file.

**Not currently wired into the build.** `app/src/main/cpp/CMakeLists.txt` gates the Hexagon
backend on `HEXAGON_SDK_ROOT` (and optionally `HEXAGON_TOOLS_ROOT`) being set, and when it is,
lets llama.cpp/ggml's own unmodified `ggml-hexagon` CMakeLists.txt do the full, correct thing —
including building these exact DSP skels from source via the SDK's own toolchain. That's the
lower-risk choice for infrastructure nobody here can actually build-test end-to-end: it defers
entirely to upstream's own well-exercised path instead of us reimplementing a shortcut around it.

These vendored files are kept here as a documented, ready-to-use option for a future pass: if
someone wants a host build that only needs Hexagon SDK **headers** (skipping the DSP toolchain
entirely), the `ggml-hexagon/CMakeLists.txt` under `app/src/main/cpp/llama.cpp/ggml/src/` would
need to be adapted to consume these instead of generating/building its own — pointing
`build_idl()`'s output at `htp_iface/htp_iface_stub.c` and skipping the `build_htp_skel()`
`ExternalProject_Add` calls in favor of installing `skels/arm64-v8a/*.so` directly.

At runtime (once wired in either way), the on-device Qualcomm driver (`libcdsprpc.so`) is
`dlopen`'d from the system by `htp-drv.cpp` — never bundled — the same pattern this project
already uses for the OpenCL GPU backend (`app/src/main/cpp/opencl-icd-loader/`).
