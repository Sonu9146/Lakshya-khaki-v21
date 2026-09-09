# Lakshya Khaki v20 — FINAL2 Build Fix

Exact GitHub error addressed:
- `Unresolved reference: NavHostController`
- cascading `Unresolved reference: navigate` errors.

Root cause:
`NavHostController` is provided by `androidx.navigation`, while the source only imported `androidx.navigation.compose.*`.

Fix:
- Added `import androidx.navigation.NavHostController`.
- Kept Java/Kotlin JVM target at 17.
- Added a CI workflow that generates the Gradle wrapper before running it.
- APK is uploaded as `lakshya-khaki-debug`.

Version: 2.1.4 / versionCode 16.
