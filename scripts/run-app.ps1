$ErrorActionPreference = "Stop"

$javaHome = "C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"
$sdkRoot = "$env:USERPROFILE\AppData\Local\Android\Sdk"
$adb = "$sdkRoot\platform-tools\adb.exe"
$emulator = "$sdkRoot\emulator\emulator.exe"

$env:JAVA_HOME = $javaHome
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:Path = "$javaHome\bin;$sdkRoot\platform-tools;$env:Path"

Push-Location "$PSScriptRoot\.."

./gradlew assembleDebug

$device = (& $adb devices) -match "emulator-5554\s+device"
if (-not $device) {
  Start-Process -FilePath $emulator -ArgumentList "-avd SchoolAppApi34 -no-audio -gpu swiftshader_indirect"
  Write-Host "Waiting for emulator..."
  & $adb wait-for-device
  while ((& $adb -s emulator-5554 shell getprop sys.boot_completed).Trim() -ne "1") {
    Start-Sleep -Seconds 2
  }
}

& $adb -s emulator-5554 install -r "app\build\outputs\apk\debug\app-debug.apk"
& $adb -s emulator-5554 shell am start -n "com.schoolapp/.MainActivity"

Write-Host "App launched on emulator-5554"

Pop-Location
