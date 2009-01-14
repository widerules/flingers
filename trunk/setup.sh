#!/bin/bash

export JAVA_HOME=c:/Program\ Files/Java/jdk1.6.0_11

alias ant="/c/asdk/apache-ant-1.7.1/bin/ant"
alias emulator="/c/asdk/android-sdk-windows-1.0_r2/tools/emulator.exe"
alias adb="/c/asdk/android-sdk-windows-1.0_r2/tools/adb"

# To setup build.xml from AndroidManifest.xml:
# ../tools/activitycreator.bat com.tulrich.flingers.Flingers

# To start emulator:
# emulator &

# To build & install on emulator:
# ant && adb -e install -r bin/Flingers-debug.apk

# To build & install on device:
# ant && adb -d install -r bin/Flingers-debug.apk

# To see logs:
# adb -e logcat

# To open a shell on the android:
# adb -e shell
