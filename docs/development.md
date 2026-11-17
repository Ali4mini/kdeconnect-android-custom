# Development setup

Use JDK 21 and the checked-in Gradle wrapper. This checkout compiles and targets
Android API 37 and supports API 23 and above. SDK and dependency versions are
defined in `build.gradle.kts` and `gradle/libs.versions.toml`.

Install Android SDK Command-line Tools or use Android Studio's SDK Manager. With
`ANDROID_HOME` pointing to the SDK and `sdkmanager` on your path, run:

```sh
sdkmanager --licenses
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
./gradlew --console=plain assembleDebug lintDebug testDebugUnitTest
```

You can also set `sdk.dir` in the ignored `local.properties` file. Gradle can
download additional SDK components once the licenses are accepted. The build
resolves plugins and libraries from Google Maven, Maven Central, the Gradle
Plugin Portal, and JitPack. If Java stalls connecting to a repository on a
network with broken IPv6 routing, retry the SDK or Gradle command with
`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`.

## Device verification

The debug APK is generated at `build/outputs/apk/debug/kdeconnect-android-debug.apk`.
Connect an Android device with USB debugging enabled and run:

```sh
adb devices -l
./gradlew installDebug
```

Launch the app and pair it with a KDE Connect desktop peer on the same network.
Check discovery, pairing, a file transfer in each direction, and reconnect after
restarting the Android app. For plugin changes, also exercise the affected
plugin and its permission flow on the device. Record the phone and desktop
versions alongside the result.

## Working with upstream

`origin` is this fork. Add the KDE remote once per clone and fetch it:

```sh
git remote add upstream https://invent.kde.org/network/kdeconnect-android.git
git fetch upstream
git log --left-right --oneline HEAD...upstream/master
```

Use a dedicated branch for upstream updates. Review incoming changes, run the
build and checks above, and record the new upstream commit and any compatibility
issues in the [fork log](fork.md).
