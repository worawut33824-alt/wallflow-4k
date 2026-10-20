# Build และติดตั้งลงเครื่องทดสอบ

---

## ทางเลือกในการ build

| ทาง | ต้องลงอะไร | เหมาะเมื่อ |
|---|---|---|
| **A. GitHub Actions** | ไม่ต้องลงอะไรเลย | อยากได้ AAB ไปอัปโหลด Play |
| **B. Gradle ในเครื่อง** | สคริปต์โหลด SDK + Gradle ให้ (~1.5 GB) | อยากได้ APK ไปติดตั้งทดสอบเร็วๆ |
| **C. Android Studio** | Android Studio (~1 GB) | อยากแก้โค้ดต่อ มี auto-complete กับดีบักเกอร์ |

---

## ทาง A — GitHub Actions

1. สร้างคีย์

   ```powershell
   .\tools\create-keystore.ps1
   ```

2. สร้าง repo **private** บน GitHub แล้ว push

   ```bash
   git init
   git add .
   git commit -m "WallFlow 4K"
   git branch -M main
   git remote add origin https://github.com/ชื่อคุณ/wallflow.git
   git push -u origin main
   ```

   > ตรวจก่อน push: `git status` ต้องไม่มี `keystore/` หรือ `keystore.properties` โผล่มา (`.gitignore` กันไว้แล้ว)

3. ใส่ secret 4 ตัว ที่ **Settings → Secrets and variables → Actions**
   ค่าทั้งหมดอยู่ในไฟล์ `keystore\github-secrets.txt`

4. แท็บ **Actions → Build WallFlow → Run workflow**

5. เมื่อเสร็จ โหลด artifact

   - `wallflow-aab` → ไฟล์ `.aab` สำหรับ Google Play
   - `wallflow-apk` → ไฟล์ `.apk` สำหรับติดตั้งตรง
   - `wallflow-mapping` → ไฟล์ deobfuscation (ไม่บังคับ)

---

## ทาง B — Gradle ในเครื่อง

### ติดตั้ง toolchain ครั้งแรก

```powershell
.\tools\setup-build-toolchain.ps1
```

สคริปต์จะโหลดและวางของไว้ที่ตำแหน่งมาตรฐาน ถ้าภายหลังลง Android Studio มันจะใช้ SDK ชุดเดียวกัน ไม่โหลดซ้ำ

```
%LOCALAPPDATA%\Android\Sdk            Android SDK (platform 36, build-tools 35 + 36)
%LOCALAPPDATA%\Gradle\gradle-8.14.2   Gradle
```

ถ้า Gradle ฟ้องว่า licence ยังไม่ยอมรับ ให้รัน

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

แล้วกด `y` ตามที่ถาม

### build

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$gradle = "$env:LOCALAPPDATA\Gradle\gradle-8.14.2\bin\gradle.bat"

& $gradle assembleDebug      # APK สำหรับทดสอบ เซ็นด้วยคีย์ debug
& $gradle assembleRelease    # APK สำหรับใช้จริง ต้องมี keystore.properties
& $gradle bundleRelease      # AAB สำหรับ Google Play
```

ไฟล์ที่ได้

```
app\build\outputs\apk\debug\app-debug.apk
app\build\outputs\apk\release\app-release.apk
app\build\outputs\bundle\release\app-release.aab
```

---

## ทาง C — Android Studio

1. โหลดจาก https://developer.android.com/studio
2. **Open** แล้วเลือกโฟลเดอร์โปรเจกต์นี้
3. รอ Gradle sync (ครั้งแรกนานหน่อย)
4. ถ้าขึ้นว่าไม่มี Gradle wrapper ให้เลือก **File → Settings → Build Tools → Gradle → Use Gradle from: Specified location** แล้วชี้ไปที่ `%LOCALAPPDATA%\Gradle\gradle-8.14.2` หรือกดให้ Studio สร้าง wrapper ให้
5. กด **Run** โดยต่อเครื่อง S25+ ไว้

---

## ติดตั้งลง Galaxy S25+

### วิธีที่ 1 — คัดลอกไฟล์ (ง่ายสุด สำหรับ debug APK)

1. ต่อสาย USB เลือกโหมด **File transfer / MTP**
2. คัดลอก `app-debug.apk` ไปไว้ใน Downloads ของเครื่อง
3. เปิดแอพ **My Files → Downloads** แตะไฟล์
4. ถ้าถามเรื่องแหล่งที่ไม่รู้จัก ให้เปิดสิทธิ์ให้ My Files แล้วแตะติดตั้งอีกครั้ง

> ตั้งแต่ 30 ก.ย. 2026 ไทยเริ่มบังคับ Android Developer Verification กับแอพที่ติดตั้งบนเครื่องที่ผ่านการรับรอง ถ้าการติดตั้งแบบนี้ถูกบล็อก ให้ใช้ ADB ตามวิธีที่ 2 หรือข้ามไปใช้ Play Internal testing ซึ่งเป็นเส้นทางที่ถูกต้องและถาวรกว่า

### วิธีที่ 2 — ADB

1. บนเครื่อง S25+: **ตั้งค่า → เกี่ยวกับโทรศัพท์ → ข้อมูลซอฟต์แวร์** แตะ **หมายเลขบิลด์** 7 ครั้ง
2. **ตั้งค่า → ตัวเลือกสำหรับนักพัฒนา** เปิด **การแก้จุดบกพร่อง USB**
3. ต่อสาย แล้วบนคอม

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb devices                                        # อนุญาตบนหน้าจอมือถือ
   & $adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

4. ดู log ตอนทดสอบ

   ```powershell
   & $adb logcat -s WallFlowEngine WallFlowApplier WallFlowPlayback WallFlowLibrary WallFlowImage
   ```

---

## ตรวจว่า build ถูกต้อง

```powershell
# ดูว่า APK ขอสิทธิ์อะไรบ้าง ควรเห็น SET_WALLPAPER, RECEIVE_BOOT_COMPLETED
# และ ACCESS_NETWORK_STATE ที่ติดมากับ Media3 เท่านั้น ต้องไม่มี INTERNET
$aapt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt2.exe"
& $aapt dump permissions app\build\outputs\apk\debug\app-debug.apk

# ดูว่า AAB เซ็นด้วยคีย์ไหน
$apksigner = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat"
& $apksigner verify --print-certs app\build\outputs\apk\release\app-release.apk
```

---

## ปัญหาที่เจอบ่อย

**`SDK location not found`**
สร้างไฟล์ `local.properties` ที่รากโปรเจกต์ ใส่

```
sdk.dir=C\:\\Users\\Data3\\AppData\\Local\\Android\\Sdk
```

**`Failed to install ... some licences have not been accepted`**
รัน `sdkmanager --licenses` แล้วกด `y` ทุกข้อ

**Gradle daemon กินแรมเยอะ**
ใส่ `--no-daemon` ต่อท้ายคำสั่ง หรือสั่ง `gradle --stop` เมื่อเลิกใช้

**`Unsupported class file major version`**
JDK เก่าเกินไป โปรเจกต์นี้ต้องการ JDK 17 ขึ้นไป เช็กด้วย `java -version`
