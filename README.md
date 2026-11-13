# ChatScope X

تطبيق مراسلة لـ Android، خلفيته على Cloudflare (Workers وD1 وR2).

```
backend/   الخلفية: Cloudflare Worker + قاعدة D1 + اختبارات (54 اختباراً)
android/   تطبيق Android (Java)، يتصل بالخلفية عبر HTTPS
```

## الحالة بصراحة

| الميزة | الحالة |
|---|---|
| الخلفية: حسابات، رسائل، مجموعات، حالات، مكالمات، وسائط | مكتوبة ومختبرة بـ 54 اختباراً محلياً، **غير مجرَّبة على Cloudflare نفسها** |
| تطبيق Android بكل الشاشات | مكتوب، **لم يُترجم ولم يُشغَّل** (لا Android SDK في بيئة الكتابة) |
| المكالمات الصوتية والمرئية | مكتوبة؛ ترنّ **فقط والتطبيق مفتوح**؛ تحتاج TURN لشبكات الهاتف |
| الصور والصوت | تحتاج تفعيل R2 (انظر أدناه) |
| استعادة كلمة المرور | **غير موجودة** (لا يوجد مزوّد بريد) |
| الإشعارات والتشفير الطرفي | **غير موجودة** |

الرسائل محفوظة كنص عادي في D1، وليست مشفّرة طرفاً إلى طرف.

## الرفع والتشغيل (من الجوال)

1. أنشئ مستودعاً **خاصاً** على GitHub.
2. أنشئ الملف `.github/workflows/chatscopex.yml` والصق فيه محتوى `chatscopex.yml` الذي أُرسل لك.
3. ارفع `chatscopex-repo.zip` إلى جذر المستودع (Add file ← Upload files).
4. **Settings ← Secrets and variables ← Actions** أضف السر `CLOUDFLARE_API_TOKEN`:
   من Cloudflare: My Profile ← API Tokens ← Create Token ← قالب *Edit Cloudflare Workers*، وأضف صلاحية **D1: Edit**.
5. **Actions ← ChatScope X ← Run workflow**: شغّل `unpack`، ثم `deploy-backend`، ثم `build-android`.
6. نزّل `ChatScopeX.apk` من **Releases** وثبّته.

## اختياري

- **الصور والصوت:** فعّل R2 في حسابك، ثم أضف متغيراً (Variables) باسم `ENABLE_R2` وقيمته `true`، وأعد `deploy-backend`.
- **المكالمات عبر شبكات الهاتف:** أنشئ TURN key من Cloudflare (Realtime ← TURN)، وأضف السرّين `TURN_KEY_ID` و`TURN_API_TOKEN`، وأعد `deploy-backend`.
- **اسم الـ Worker أو عنوانه:** إن غيّرته، غيّر `name` في `backend/wrangler.toml` و`API_BASE` في `android/app/build.gradle`.

## ما لم أتحقق منه

- رقم إصدار `org.webrtc:google-webrtc:1.0.32006` ومكتبات AndroidX وMaterial (من معرفتي، دون اتصال بالمستودعات).
- شكل رد Cloudflare TURN في `backend/src/calls.js` (تُعالَج الصيغتان المعروفتان، ويرجع إلى STUN عند أي خطأ).
- بناء Gradle نفسه: توقّع جولة أو جولتين لإصلاح أخطاء ترجمة من سجل GitHub Actions.
- توقيع APK ثابت (مفتاح debug عام ضمن المشروع) كي تُثبَّت كل نسخة فوق السابقة. للنشر العام استبدله بمفتاح خاص بك.
