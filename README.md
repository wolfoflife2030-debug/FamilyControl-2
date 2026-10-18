# Family Control — التحكم بالواي فاي والبلوتوث (للاستخدام العائلي)

## البناء والتثبيت
1. افتح المجلد في Android Studio (JDK 17) ثم Build > Build APK، أو ارفعه إلى GitHub ليبنيه الـ workflow.
2. ثبّت الـ APK على جهاز الطفل (Side-loading).

## أوامر ADB (على جهاز الطفل، مع تفعيل USB debugging)
adb shell pm grant com.parent.familycontrol android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.parent.familycontrol android.permission.BLUETOOTH_CONNECT   # اختياري، أندرويد 12+ فقط إن لزم

للتحقق:
adb shell dumpsys package com.parent.familycontrol | grep -E "WRITE_SECURE_SETTINGS|BLUETOOTH_CONNECT"

## أول تشغيل
يطلب التطبيق إنشاء رمز سري من 4 أرقام عبر لوحة الأرقام. بعد 5 محاولات فاشلة ينتظر 60 ثانية.
يُقفل التطبيق تلقائياً عند الخروج منه.
