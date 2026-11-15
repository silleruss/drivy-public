# Source from the repository root after installing JDK 21 and the Android SDK.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:$PATH"
