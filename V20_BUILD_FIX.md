# Lakshya Khaki v20 — Build Fix

The GitHub Actions log showed the exact failure:

"Inconsistent JVM-target compatibility detected for tasks
'compileDebugJavaWithJavac' (1.8) and 'kspDebugKotlin' (17)."

Fix applied:
- Android Java sourceCompatibility = Java 17
- Android Java targetCompatibility = Java 17
- Kotlin JVM toolchain = 17
- JDK 17 remains configured in GitHub Actions
- Version bumped to 2.1.3 / versionCode 15

The compileSdk 35 message in the log is only a warning; it is not the failure.
