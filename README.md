# LAN Chat

تطبيق أندرويد للمحادثة المباشرة (P2P) بين الهواتف على نفس الشبكة المحلية، بدون سيرفر وبدون إنترنت.

## كيف يعمل

| المرحلة | المنفذ | الآلية |
|---|---|---|
| اكتشاف الأجهزة | UDP `8888` | بث نبضة JSON على عنوان Broadcast للشبكة |
| الرسائل والملفات | TCP `9999` | إرسال مباشر بعد انتهاء الاكتشاف |
| المكالمات الصوتية | TCP + UDP | offer/answer/ringing ثم بث صوت |
| Mesh (بدون راوتر) | Google Nearby | استراتيجية P2P_CLUSTER عبر Play Services |

كل حزمة discovery تحمل `deviceId` والاسم و**المفتاح العام** فقط، فيتبادل الطرفان
مفتاح ECDH (secp256r1) ويشفّران الرسائل بعد ذلك. المفتاح محفوظ في Android Keystore.

## المتطلبات

- **JDK 21** (مطلوب لـ Robolectric مع SDK 36)
- **Android SDK**: compileSdk 36، build-tools 36، minSdk 24
- ملف `local.properties` يحتوي على مسار الـ SDK

```properties
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

## البناء

```bash
./gradlew :app:assembleDebug        # APK للتطوير
./gradlew :app:testDebugUnitTest    # كل الاختبارات
./gradlew :app:assembleRelease      # نسخة موقّعة (تحتاج keystore)
```

ناتج نسخة التطوير: `app/build/outputs/apk/debug/app-debug.apk`

## التوقيع

نسختا Debug و Release تستخدمان مفتاح التوقيع الافتراضي لـ Android Studio ما لم يوجد
ملف `my-upload-key.jks` في جذر المشروع. للنشر على Google Play اضبط متغيرات البيئة:

```
KEYSTORE_PATH, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
```

## الأذونات

- `NEARBY_WIFI_DEVICES` و `BLUETOOTH_*` — للـ Mesh
- `ACCESS_NETWORK_STATE` و `CHANGE_WIFI_MULTICAST_STATE` — للشبكة المحلية
- `RECORD_AUDIO` و `CAMERA` — للمحتوى الصوتي والمرئي
- `POST_NOTIFICATIONS` — للإشعارات

## ملاحظات مهمة عن Mesh

الـ Mesh يعتمد على **Google Play Services Nearby**. إذا كانت الخدمات غير متاحة أو
معطلة على الجهاز، تعرض الواجهة السبب بوضوح بدلاً من الادعاء بأن Mesh يعمل.

اكتشاف الشبكة المحلية (UDP/TCP) **لا يعتمد على Play Services** ويشتغل على أي جهاز
على نفس الراوتر، أو عبر نقطة اتصال (Hotspot)، أو اتصال سلكي.

## البنية

```
data/network/    UdpDiscoveryManager · TcpMessagingManager · NearbyMeshManager
                 NetworkUtils · NetworkPayloads
data/security/   EncryptionManager · PairwiseSessionManager
data/local/      Room: contacts · messages · groups · prefs
ui/              Compose screens + ChatViewModel
service/         LanBackgroundService (foreground)
```

## تشخيص المشاكل

```bash
adb logcat -s UdpDiscovery:V TcpMessaging:V NearbyMeshManager:V NetworkUtils:V
```

- `Cannot listen on UDP port 8888` — تطبيق آخر يمسك المنفذ، والاستكشاف معطّل.
- `Skipping beacon: no broadcast address` — لا توجد شبكة محلية صالحة.
- `Mesh unavailable: ...` — Play Services غير متاح.

السجل الكامل متاح أيضاً من داخل التطبيق في قسم السجلات المتقدمة.
