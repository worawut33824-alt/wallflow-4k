# อัปโหลด WallFlow ขึ้น Google Play ทีละขั้น

อัปเดตข้อมูลนโยบาย ณ 19 ตุลาคม 2026

---

## ตอนที่ 0 — เลือกเส้นทางก่อน

Play มีหลายช่องทาง และช่องทางที่ถูกจะช่วยให้คุณไม่ต้องไปติดกฎที่ไม่เกี่ยวกับคุณ

| ช่องทาง | แอพเข้าไลบรารี Play | ข้อกำหนด | เวลารอ | เหมาะกับคุณไหม |
|---|---|---|---|---|
| **Internal testing** | ใช่ | สมัคร Play Console $25 เท่านั้น | ไม่กี่ชั่วโมง–1 วัน | **ใช่ เลือกอันนี้** |
| Closed testing | ใช่ | เหมือนบน | 1–3 วัน | ไม่จำเป็น |
| Production | ใช่ | บัญชีส่วนบุคคลต้องผ่าน closed test **12 คน ติดกัน 14 วัน** ก่อน | 3–7 วัน | ไม่คุ้ม สำหรับใช้คนเดียว |
| Internal app sharing | ไม่ | สมัคร Play Console | ทันที | ใช้ทดสอบเร็วๆ ได้ แต่ไม่ขึ้นไลบรารี |
| Sideload APK | ไม่ | **ไทยบังคับ Developer Verification ตั้งแต่ 30 ก.ย. 2026** | – | ติดข้อจำกัดแล้ว |

**ข้อสรุป: ใช้ Internal testing**

- รองรับผู้ทดสอบได้ถึง 100 บัญชี (คุณใช้แค่ 1)
- **ไม่ติดกฎ 12 testers 14 วัน** เพราะกฎนั้นผูกกับการขอ Production access เท่านั้น
- แอพขึ้นในไลบรารี Play ของบัญชีคุณ อัปเดตอัตโนมัติเหมือนแอพทั่วไป
- ไม่มีใครอื่นค้นเจอ ไม่ต้องทำการตลาด ไม่ต้องมีสกรีนช็อตสวยงาม

**เรื่อง Developer Verification ที่เพิ่งเริ่มบังคับในไทย** ตั้งแต่ 30 กันยายน 2026 Google เริ่มบังคับในไทย บราซิล อินโดนีเซีย สิงคโปร์ ว่าแอพที่ติดตั้งบนเครื่อง Android ที่ผ่านการรับรองต้องมาจากนักพัฒนาที่ยืนยันตัวตนแล้ว ไม่ว่าจะโหลดจากไหน การสมัคร Play Console และยืนยันตัวตนในขั้นตอนข้างล่างครอบคลุมเรื่องนี้ให้อยู่แล้ว

---

## ตอนที่ 1 — สมัคร Google Play Console

เว็บ: https://play.google.com/console/signup

1. ล็อกอินด้วยบัญชี Google ที่จะใช้ยาวๆ **ห้ามเปลี่ยนภายหลัง** แนะนำให้ใช้บัญชีส่วนตัวที่คุณคุมเอง ไม่ใช่บัญชีบริษัทที่อาจถูกปิด
2. เลือกประเภทบัญชี
   - **Personal / ส่วนบุคคล** — ใช้บัตรประชาชนยืนยัน ไม่ต้องมี D-U-N-S เหมาะกับงานนี้
   - Organization — ต้องมีนิติบุคคล + เลข D-U-N-S ใช้เวลานานกว่า แต่ไม่ติดกฎ 12 testers ตอนขึ้น Production
3. จ่ายค่าธรรมเนียม **US$25 ครั้งเดียวตลอดชีพ** ด้วยบัตรเครดิต/เดบิตที่รองรับ
4. กรอกข้อมูล แล้วยืนยันตัวตน
   - ชื่อ-นามสกุล ตรงกับเอกสาร
   - ที่อยู่ในไทย
   - เบอร์โทรศัพท์
   - อัปโหลดบัตรประชาชนหรือพาสปอร์ต
   - ยืนยันอีเมลและเบอร์โทร
5. รอผลยืนยัน ปกติ 1–3 วันทำการ บางครั้งถึง 1 สัปดาห์

> ระหว่างรอยืนยัน คุณสร้างแอพและเตรียมทุกอย่างในคอนโซลได้แล้ว แต่จะยังปล่อย release ไม่ได้

---

## ตอนที่ 2 — สร้างคีย์เซ็นชื่อ

เปิด PowerShell ที่โฟลเดอร์โปรเจกต์

```powershell
.\tools\create-keystore.ps1
```

จะได้ไฟล์

```
keystore\release.jks            ← คีย์ ห้ามหาย ห้าม commit
keystore.properties             ← ให้ Gradle ใช้ตอน build ในเครื่อง
keystore\github-secrets.txt     ← ค่า 4 ตัวสำหรับ GitHub Actions
```

> **สำรองคีย์กับรหัสผ่านทันที** ลงใน password manager หรือ Google Drive ส่วนตัว ถ้าหายทั้งคู่ คุณจะอัปเดตแอพตัวเดิมไม่ได้อีก

**Play App Signing** ตอนอัปโหลด AAB ครั้งแรก Play จะชวนเปิด Play App Signing ให้ **กดเปิด** Google จะถือคีย์ตัวจริงที่ใช้เซ็นให้ผู้ใช้ ส่วนคีย์ของคุณกลายเป็น "upload key" ถ้า upload key หาย คุณยังขอรีเซ็ตกับ Google ได้ ซึ่งปลอดภัยกว่ามาก

---

## ตอนที่ 3 — Build ไฟล์ AAB

Play รับเฉพาะ **Android App Bundle (.aab)** สำหรับแอพใหม่ ไม่รับ .apk แล้ว

### ทาง A — GitHub Actions (แนะนำ ไม่ต้องลงอะไรเพิ่ม)

1. สร้าง repo แบบ **Private** บน GitHub
2. push โปรเจกต์ขึ้นไป (`.gitignore` กันไฟล์คีย์ไว้ให้แล้ว ตรวจอีกรอบว่า `keystore/` ไม่ติดไปด้วย)
3. ไปที่ **Settings → Secrets and variables → Actions → New repository secret** ใส่ทีละตัวตามไฟล์ `keystore\github-secrets.txt`

   | ชื่อ secret | ค่า |
   |---|---|
   | `KEYSTORE_BASE64` | ข้อความ base64 ยาวๆ ท้ายไฟล์ |
   | `STORE_PASSWORD` | รหัสผ่าน keystore |
   | `KEY_ALIAS` | `wallflow-upload` |
   | `KEY_PASSWORD` | รหัสผ่านเดียวกัน |

4. แท็บ **Actions → Build WallFlow → Run workflow**
5. รอประมาณ 5–8 นาที แล้วโหลด artifact **wallflow-aab**

### ทาง B — Android Studio

เปิดโฟลเดอร์โปรเจกต์ แล้ว **Build → Generate Signed App Bundle / APK → Android App Bundle** เลือก `keystore\release.jks` ใส่รหัสผ่าน แล้วเลือก build variant `release`

ไฟล์จะอยู่ที่ `app\build\outputs\bundle\release\app-release.aab`

---

## ตอนที่ 4 — สร้างแอพใน Play Console

**All apps → Create app**

| ช่อง | ใส่อะไร |
|---|---|
| App name | `WallFlow 4K` (เปลี่ยนได้ทีหลัง สูงสุด 30 ตัวอักษร) |
| Default language | `ไทย – th-TH` |
| App or game | **App** |
| Free or paid | **Free** |
| Declarations | ติ๊กทั้งสองช่อง (Developer Program Policies, US export laws) |

กด **Create app**

---

## ตอนที่ 5 — กรอกแบบสอบถามใน Dashboard

หน้า Dashboard จะมีรายการ **Set up your app** ต้องเคลียร์ให้ครบก่อนปล่อย release ได้ คำตอบที่ถูกต้องสำหรับแอพนี้

### 5.1 App access
เลือก **All functionality is available without special access**
(แอพไม่มีระบบล็อกอิน ไม่มีส่วนที่ล็อกไว้)

### 5.2 Ads
**No, my app does not contain ads**

### 5.3 Content ratings
กรอกแบบสอบถาม IARC

- Category: **Utility, Productivity, Communication, or Other**
- ตอบ **No** ทุกข้อเรื่องความรุนแรง เพศ ยาเสพติด การพนัน
- มีการแชร์ตำแหน่ง / ข้อมูลส่วนตัว / แชทระหว่างผู้ใช้: **No** ทั้งหมด
- ผลที่ได้จะเป็นเรต **3+ / ทุกวัย**

### 5.4 Target audience and content
- Target age group: เลือก **18 and over** เท่านั้น (เลี่ยงกฎคุ้มครองเด็กที่ไม่เกี่ยวกับเรา)
- Appeal to children: **No**

### 5.5 News apps
**No**

### 5.6 Data safety ← ข้อสำคัญที่สุด

แอพนี้ไม่มีสิทธิ์ `INTERNET` เลย กรอกง่ายมาก

| คำถาม | คำตอบ |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | ไม่ต้องตอบ (ข้ามอัตโนมัติ) |
| Do you provide a way for users to request that their data is deleted? | ไม่ต้องตอบ |

> ถ้ากรอก **No** ไว้แล้วภายหลังเพิ่มฟีเจอร์ที่ต่อเน็ต ต้องกลับมาแก้ ไม่งั้นผิดนโยบายและโดนถอดแอพ

### 5.7 Government apps
**No**

### 5.8 Financial features
**My app doesn't provide any financial features**

### 5.9 Health apps
**No**

### 5.10 Privacy policy

Play บังคับให้ทุกแอพมี URL นโยบายความเป็นส่วนตัวที่เปิดดูได้สาธารณะ ไฟล์ [PRIVACY.md](../PRIVACY.md) เขียนไว้ให้แล้ว เอาขึ้นเว็บฟรีได้ทาง

**วิธีที่ง่ายที่สุด — GitHub Gist (ใช้เวลา 1 นาที)**

1. ไป https://gist.github.com
2. ตั้งชื่อไฟล์ `privacy-policy.md` วางเนื้อหาจาก `PRIVACY.md`
3. เลือก **Create public gist**
4. คัดลอก URL มาใส่ในช่อง Privacy policy

**หรือ GitHub Pages** ถ้า repo เป็น public: Settings → Pages → Deploy from branch → `main` / root จะได้ URL แบบ `https://ชื่อคุณ.github.io/wallflow/PRIVACY`

---

## ตอนที่ 6 — Store listing

**Grow → Store presence → Main store listing**

Internal testing ไม่ได้โชว์ต่อสาธารณะ แต่ช่องบังคับยังต้องกรอก

| ช่อง | ตัวอย่างที่ใช้ได้เลย |
|---|---|
| App name | `WallFlow 4K` |
| Short description (≤80 ตัวอักษร) | `วอลเปเปอร์รูปและวิดีโอไม่จำกัด พร้อมนาฬิกาปรับสีตามภาพ` |
| Full description (≤4000) | ดูข้อความเต็มใน [docs/store-listing.txt](store-listing.txt) |
| App icon | `store/icon-512.png` (สร้างให้แล้ว) |
| Feature graphic | `store/feature-1024x500.png` (สร้างให้แล้ว) |
| Phone screenshots | ต้องมีอย่างน้อย **2 รูป** ถ่ายจากเครื่องจริง |

**วิธีได้สกรีนช็อต** ติดตั้งไฟล์ debug APK ลง S25+ ก่อน (ดู `docs/01-build-และติดตั้ง.md`) เปิดแอพ กดปุ่ม Power + ลดเสียงพร้อมกัน ถ่าย 2–3 หน้า แล้วอัปโหลดไฟล์ PNG ตรงๆ ได้เลย Play รับขนาด 320–3840 px ด้านสั้น ซึ่งสกรีนช็อตของ S25+ ผ่านอยู่แล้ว

| ช่อง | ใส่อะไร |
|---|---|
| App category | **Personalization** |
| Tags | Wallpapers, Customization |
| Email | อีเมลคุณ (จำเป็น) |
| Website / Phone | เว้นว่างได้ |

---

## ตอนที่ 7 — อัปโหลดและปล่อย Internal testing

**Test and release → Testing → Internal testing**

### 7.1 สร้างรายชื่อผู้ทดสอบ
1. แท็บ **Testers → Create email list**
2. ตั้งชื่อลิสต์ เช่น `ตัวเอง`
3. ใส่อีเมล Gmail ของคุณ (ต้องเป็นบัญชีเดียวกับที่ล็อกอิน Play Store บน S25+)
4. **Save**

### 7.2 สร้าง release
1. แท็บ **Releases → Create new release**
2. ป๊อปอัป **Play App Signing** ขึ้นมา → กด **Continue / Use Play App Signing**
3. ลาก `app-release.aab` เข้าช่อง App bundles
4. Release name: ใส่เอง เช่น `1.0.0 (1)`
5. Release notes:
   ```xml
   <th-TH>
   เวอร์ชันแรก
   </th-TH>
   ```
6. **Next** → ตรวจคำเตือน (ถ้ามีแค่ warning สีเหลือง ปล่อยผ่านได้) → **Save and publish**

### 7.3 รอรีวิว
Internal testing ปกติผ่านใน **ไม่กี่ชั่วโมงถึง 1 วัน** สถานะจะเปลี่ยนจาก *In review* เป็น *Available to internal testers*

---

## ตอนที่ 8 — ติดตั้งลง Galaxy S25+

1. กลับไปแท็บ **Testers** เลื่อนลงล่างสุด หา **How testers join your test** → **Copy link**
   ลิงก์หน้าตาแบบ `https://play.google.com/apps/internaltest/4701xxxxxxxxxxxxx`
2. เปิดลิงก์นี้บนเครื่อง S25+ ด้วยบัญชี Google ที่ใส่ไว้ในลิสต์
3. กด **Become a tester** → **Download it on Google Play**
4. ติดตั้งตามปกติ

จากนี้แอพจะอยู่ในไลบรารี Play ของคุณ และอัปเดตเองเมื่อคุณปล่อยเวอร์ชันใหม่

> ถ้าหน้าเว็บบอกว่าไม่พบแอพ ให้รอ 10–30 นาทีหลังสถานะเป็น Available แล้วลองใหม่ บางทีแคชของ Play ต้องใช้เวลาอัปเดต

---

## ตอนที่ 9 — ปล่อยเวอร์ชันถัดไป

1. แก้ `app/build.gradle.kts`
   ```kotlin
   versionCode = 2        // ต้องเพิ่มขึ้นทุกครั้ง ห้ามซ้ำ ห้ามลด
   versionName = "1.0.1"  // ข้อความที่ผู้ใช้เห็น
   ```
2. Build AAB ใหม่
3. Internal testing → **Create new release** → อัปโหลด → publish

---

## ปัญหาที่เจอบ่อย

**"You need to use a different package name"**
`com.admrichest.wallflow` ถูกใช้ไปแล้ว เปลี่ยนในสามที่ให้ตรงกัน: `namespace` และ `applicationId` ใน `app/build.gradle.kts` กับ `android:settingsActivity` ใน `app/src/main/res/xml/live_wallpaper.xml` (ชื่อโฟลเดอร์ซอร์สเปลี่ยนตามด้วยก็ได้ แต่ไม่จำเป็น)

**"Your app bundle is signed with the wrong key"**
AAB เซ็นด้วย keystore คนละตัวกับครั้งแรก ใช้ `keystore\release.jks` ตัวเดิม หรือขอรีเซ็ต upload key ที่ Play Console → Setup → App integrity → Request upload key reset

**"This release is not compliant with the Google Play 64-bit requirement"**
ไม่ควรเกิดกับโปรเจกต์นี้เพราะเป็น Kotlin ล้วน ไม่มี native library ถ้าเจอให้ตรวจว่าไม่ได้เผลอเพิ่ม dependency ที่มี `.so` แบบ 32-bit อย่างเดียว

**"Target API level requirement"**
ตั้งแต่ 31 ส.ค. 2026 แอพใหม่ต้อง `targetSdk = 36` ขึ้นไป โปรเจกต์นี้ตั้งไว้ 36 แล้ว

**อัปโหลดแล้วขึ้น warning เรื่อง deobfuscation file**
ไม่บังคับ แต่ถ้าอยากให้ crash report อ่านรู้เรื่อง โหลด artifact `wallflow-mapping` จาก GitHub Actions แล้วอัปโหลดในหน้า release เดียวกัน

**ลืมว่าปล่อยเวอร์ชันไหนไปแล้ว**
Play Console → Test and release → Releases overview ดูได้ทุก track

---

## แหล่งอ้างอิง

- [Target API level requirements for Google Play](https://developer.android.com/google/play/requirements/target-sdk)
- [Android developer verification guides](https://developer.android.com/developer-verification/guides)
- [Play Console: testing requirements for personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-GB)
- [Update your target API level by August 31, 2026](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en-419)
