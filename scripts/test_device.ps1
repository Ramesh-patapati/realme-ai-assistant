# ==============================================================================
# SiriPulse / Jarvis Android Automated Verification Test Harness
# Runs automated headless tests against connected Realme 7 Pro via ADB.
# ==============================================================================

param(
    [string]$AdbPath = "C:\Users\ADMIN\AppData\Local\Temp\platform-tools\adb.exe",
    [string]$PackageName = "com.assistant.voiceagent"
)

Write-Host "`n=== SIRIPULSE AUTOMATED DEVICE VERIFICATION HARNESS ===" -ForegroundColor Cyan
$passCount = 0
$failCount = 0

function Assert-Step([string]$stepName, [scriptblock]$condition) {
    Write-Host -NoNewline "  [*] $stepName... "
    try {
        $result = & $condition
        if ($result) {
            Write-Host "PASS" -ForegroundColor Green
            $script:passCount++
        } else {
            Write-Host "FAIL" -ForegroundColor Red
            $script:failCount++
        }
    } catch {
        Write-Host "ERROR: $_" -ForegroundColor Red
        $script:failCount++
    }
}

# 1. Device Connectivity
Assert-Step "Checking ADB device connection" {
    $devices = & $AdbPath devices
    return ($devices -match "device\b")
}

# 2. Package Installed
Assert-Step "Verifying package installation ($PackageName)" {
    $installed = & $AdbPath shell "pm list packages $PackageName"
    return ($installed -match "package:$PackageName")
}

# 3. Process Running
Assert-Step "Checking background process is alive" {
    $ps = & $AdbPath shell "ps -A | grep $PackageName"
    return ($ps -match $PackageName)
}

# 4. Notification Listener Active
Assert-Step "Checking WhatsApp notification listener permission" {
    $listeners = & $AdbPath shell "settings get secure enabled_notification_listeners"
    return ($listeners -match "$PackageName\.service\.WhatsAppNotificationService")
}

# 5. Microphone Permission Granted
Assert-Step "Checking RECORD_AUDIO permission" {
    $dumpsys = & $AdbPath shell "dumpsys package $PackageName | grep RECORD_AUDIO"
    return ($dumpsys -match "granted=true")
}

# 6. Test Direct Intent Command: Battery
Assert-Step "Injecting battery command intent" {
    & $AdbPath logcat -c
    & $AdbPath shell "am start-foreground-service -n $PackageName/.service.LockScreenVoiceService -a com.assistant.voiceagent.TEST_COMMAND --es command 'what is my battery'" | Out-Null
    Start-Sleep -Seconds 2
    $logs = & $AdbPath logcat -d -v time | Select-String -Pattern "PhoneActions|VoiceService|TtsManager"
    return ($logs -match "battery|Speaking")
}

# 7. Test Direct Intent Command: Time
Assert-Step "Injecting time command intent" {
    & $AdbPath logcat -c
    & $AdbPath shell "am start-foreground-service -n $PackageName/.service.LockScreenVoiceService -a com.assistant.voiceagent.TEST_COMMAND --es command 'what time is it'" | Out-Null
    Start-Sleep -Seconds 2
    $logs = & $AdbPath logcat -d -v time | Select-String -Pattern "CommandParser|VoiceService|TtsManager"
    return ($logs -match "It is|Speaking")
}

# 8. Test Direct Intent Command: Greeting
Assert-Step "Injecting greeting command intent ('hi')" {
    & $AdbPath logcat -c
    & $AdbPath shell "am start-foreground-service -n $PackageName/.service.LockScreenVoiceService -a com.assistant.voiceagent.TEST_COMMAND --es command 'hi'" | Out-Null
    Start-Sleep -Seconds 2
    $logs = & $AdbPath logcat -d -v time | Select-String -Pattern "CommandParser|VoiceService|TtsManager"
    return ($logs -match "Hello! How can I help you today|Speaking")
}

# 9. Logcat Health / Crash Scan
Assert-Step "Scanning for app crashes or ANRs" {
    $crashLogs = & $AdbPath logcat -d -v time --pid (& $AdbPath shell "pidof $PackageName") | Select-String -Pattern "FATAL|AndroidRuntime|SIGSEGV"
    return ($null -eq $crashLogs -or $crashLogs.Count -eq 0)
}

Write-Host "`n=== TEST RESULTS SUMMARY ===" -ForegroundColor Cyan
Write-Host "  Passed: $passCount" -ForegroundColor Green
Write-Host "  Failed: $failCount" -ForegroundColor $(if ($failCount -gt 0) { "Red" } else { "Green" })

if ($failCount -eq 0) {
    Write-Host "`nAll verification checks passed perfectly!`n" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`nSome checks failed. Review output above.`n" -ForegroundColor Yellow
    exit 1
}
