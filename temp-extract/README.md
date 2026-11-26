# VoxelCraft Ultra 3.1 — Crash-Free Android Project & CI/CD Pipeline

A technically impressive, high-performance 3D voxel sandbox game engine built for Android and modern web browsers.

## 🛠️ Startup Crash Fixes in v3.1
1. **Window Translucent vs StatusBar Color Conflict**: Resolved the fatal `FLAG_TRANSLUCENT_STATUS` and `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` conflict that crashes Android 8-12 on startup.
2. **Missing App Icon Resource**: Added native vector asset `res/drawable/ic_launcher.xml` to prevent `Resources$NotFoundException`.
3. **API Level Attribute Segregation**: Moved `enforceStatusBarContrast` to `values-v29` and `windowLayoutInDisplayCutoutMode` to `values-v28` so older Android versions never crash inflating unsupported attributes.
4. **Zero External Dependency Fragility**: Removed heavy, fragile AndroidX dependencies in favor of pure, rock-solid Android Framework APIs.
5. **Global Crash Catcher**: Added an emergency `Thread.setDefaultUncaughtExceptionHandler` dialog handler so if any device has a quirk, it alerts you with the exact line instead of an instant native abort.

---

## 🚀 How to Publish on GitHub in 3 Steps

### Step 1: Initialize Git and Push to Your GitHub Repository
```bash
# Inside the extracted folder:
git init
git add .
git commit -m "Update VoxelCraft Ultra v3.1 with crash-free Android framework and CI/CD"
git branch -M main
git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPOSITORY_NAME>.git
git push -u origin main --force
```

### Step 2: Trigger the Automated APK Build & Publish
1. **Push a Release Tag:**
   ```bash
   git tag v3.1.0
   git push origin v3.1.0
   ```
2. **Or Manual 1-Click Trigger:**
   - Go to your repository on GitHub.
   - Click the **Actions** tab.
   - Select **Build APK and Publish GitHub Release** in the sidebar.
   - Click **Run workflow**.

### Step 3: Download Your APK
Once the workflow finishes (typically ~2-3 minutes):
- Navigate to the **Releases** section on your GitHub repository page.
- Download `VoxelCraft-Ultra-v3.1-release.apk` directly onto your Android device!
