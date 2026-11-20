Write-Host "=== OpenGate Auto APK Builder ===" -ForegroundColor Cyan
if (Get-Command gradle -ErrorAction SilentlyContinue) {
    gradle assembleDebug
    if ($LASTEXITCODE -eq 0) {
        Write-Host "APK נוצר בהצלחה בנתיב: app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
    } else {
        Write-Host "הבנייה נכשלה - ראה את השגיאות למעלה." -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "לא נמצא Gradle מותקן גלובלית. ניתן לפתוח את התיקייה ב-Android Studio וללחוץ על Build -> Build APK(s), או להעלות ל-GitHub כדי ש-GitHub Actions יקמפל עבורך את ה-APK אוטומטית." -ForegroundColor Yellow
}
