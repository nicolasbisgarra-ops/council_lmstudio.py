# Validación nativa de llama.cpp en Android

Fecha: 2026-10-30

## Fuentes y entorno

- llama.cpp upstream: v0.8.0, commit `8642541b255bfb5dc39cd351a178be09013a55d6`.
- Checkout usado: `/workspace/scratch/1a5d65bbe0af/llama-upstream`.
- NDK: `/workspace/scratch/1a5d65bbe0af/tools/android-sdk/ndk/27.2.12479018`.
- CMake: `/workspace/scratch/1a5d65bbe0af/tools/android-sdk/cmake/3.22.1/bin/cmake`.
- Ninja: `/workspace/scratch/1a5d65bbe0af/tools/android-sdk/cmake/3.22.1/bin/ninja`.
- Plataforma de compilación: Android API 26, `c++_shared`, configuración `Release`.

El proyecto fija el commit de llama.cpp en `app/src/main/cpp/CMakeLists.txt`. Las compilaciones locales usaron `FETCHCONTENT_SOURCE_DIR_LLAMA_CPP` para reutilizar el checkout de arriba y no depender de una segunda descarga Git.

## Compilación NDK real

Configuración ARM64:

```sh
/workspace/scratch/1a5d65bbe0af/tools/android-sdk/cmake/3.22.1/bin/cmake \
  -S /workspace/scratch/1a5d65bbe0af/trazo/app/src/main/cpp \
  -B /workspace/scratch/1a5d65bbe0af/build-llama-arm64 -G Ninja \
  -DCMAKE_MAKE_PROGRAM=/workspace/scratch/1a5d65bbe0af/tools/android-sdk/cmake/3.22.1/bin/ninja \
  -DCMAKE_TOOLCHAIN_FILE=/workspace/scratch/1a5d65bbe0af/tools/android-sdk/ndk/27.2.12479018/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_ANDROID_STL_TYPE=c++_shared \
  -DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP=/workspace/scratch/1a5d65bbe0af/llama-upstream \
  -DCMAKE_BUILD_TYPE=Release

/workspace/scratch/1a5d65bbe0af/tools/android-sdk/cmake/3.22.1/bin/cmake \
  --build /workspace/scratch/1a5d65bbe0af/build-llama-arm64 \
  --target trazo_llama --parallel 2
```

La configuración detectó `CMAKE_SYSTEM_PROCESSOR=aarch64`, `GGML_SYSTEM_ARCH=ARM` y generó sin error. La compilación terminó con éxito, 142/142 pasos, e incluyó `llama_jni.cpp`, llama.cpp, MTMD y el enlace de `libtrazo_llama.so`.

Para x86_64 se repitió el mismo comando sustituyendo `-B` por `/workspace/scratch/1a5d65bbe0af/build-llama-x86_64` y pasando `-DANDROID_ABI=x86_64`. La configuración detectó `CMAKE_SYSTEM_PROCESSOR=x86_64`, `GGML_SYSTEM_ARCH=x86`; compiló y enlazó correctamente, 142/142 pasos.

Las salidas nativas están en:

- ARM64: `/workspace/scratch/1a5d65bbe0af/build-llama-arm64/libtrazo_llama.so` y `bin/lib{llama,mtmd,ggml,ggml-cpu,ggml-base}.so`.
- x86_64: `/workspace/scratch/1a5d65bbe0af/build-llama-x86_64/libtrazo_llama.so` y `bin/lib{llama,mtmd,ggml,ggml-cpu,ggml-base}.so`.

Una lectura ELF de esos binarios confirmó dependencias transitivas entre `libtrazo_llama.so`, `libmtmd.so`, `libllama.so` y las bibliotecas GGML. Los dos ABI incluyen los artefactos de todos esos targets. Para repetir la inspección con el NDK:

```sh
READELF=/workspace/scratch/1a5d65bbe0af/tools/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
"$READELF" -l /workspace/scratch/1a5d65bbe0af/build-llama-arm64/libtrazo_llama.so
"$READELF" -d /workspace/scratch/1a5d65bbe0af/build-llama-arm64/libtrazo_llama.so
```

## Alineación de páginas de 16 KB

La comprobación inicial con NDK `llvm-readelf -l` encontró `LOAD Align 0x1000` en las seis bibliotecas de cada ABI. Se habilitó `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` antes de `project()` en CMake y también como argumento de Gradle. Tras reconfigurar ambos directorios CMake, confirmé `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES:BOOL=ON` en ambos `CMakeCache.txt` y `-Wl,-z,max-page-size=16384` en los dos `build.ninja`. El NDK 27.2 añade además `-D__BIONIC_NO_PAGE_SIZE_MACRO`.

## APK final

El build Gradle completo terminó `BUILD SUCCESSFUL in 2m 9s` y ejecutó `:app:assembleDebug`, `:app:testDebugUnitTest`, `:ai:test` y `:core:test` sin fallos (7,747 tests, 0 fallos: core 7,668, AI 62, app 17). APK resultante:

- Ruta: `app/build/outputs/apk/debug/app-debug.apk`.
- Tamaño: 77,604,980 bytes (75 MiB).
- SHA-256: `c646cd54a6ca7f25caae6557df5fa47d11dc9ad6e7580da15e5d38b91ecd1bee`.

Extraje del APK las 16 bibliotecas nativas (8 para cada ABI), incluidas `libllm_inference_engine_jni.so` de MediaPipe y `libandroidx.graphics.path.so` de AndroidX Compose, y pasé `llvm-readelf -l` a todas. Cada LOAD segment en `arm64-v8a` y `x86_64` reporta `Align 0x4000`. Así queda confirmada la alineación ELF de 16 KB para el contenido nativo empaquetado.

`zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk` pasó y `apksigner verify --verbose` confirmó firma APK v2 válida.

La APK se compiló y firmó como variante debug. No se instaló ni ejecutó en un dispositivo o emulador.

## Alcance

Esta evidencia acredita compilación y enlace nativos reales con NDK para ambos ABI. No acredita la compilación Kotlin/Gradle, la creación de un APK, su instalación ni ejecución en dispositivo/emulador. La compilación Android completa depende del resultado del build Gradle que corre por separado.
