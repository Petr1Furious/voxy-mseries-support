# Metal JNI backend

Objective-C++ sources implement the port's GPU contracts and IOSurface/CGL interop. Build on macOS arm64 with JDK 25, CMake 3.20+ and Xcode command-line tools:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
./gradlew buildMetalNative --max-workers=2
```

Run Gradle from the repository root. CMake builds with two jobs; the ignored dylib is copied to the resource directory and packaged locally by `build`. No native binary is tracked. See [architecture](../../docs/METAL_26_3_PORT.md), [tests](../../docs/TESTING.md), and [rights](../../docs/RIGHTS_AND_DEPENDENCIES.md). This is a Metal LOD renderer with OpenGL composition; a failed Metal initialization does not silently select the GL world renderer.
