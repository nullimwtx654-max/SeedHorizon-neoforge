# NewBlackbox + microG (Google Login & Play Services Edition)

An enhanced, bug-fixed Android virtual engine and app cloner based on **NewBlackbox** with full built-in **microG (`GmsCore` + `FakeStore`)** support for seamless Google Account sign-in, OAuth2 / Google Sign-In (`AuthSignInActivity`, `AssistedSignInActivity`, `GamesSignInActivity`), Firebase Cloud Messaging (GCM/FCM), DroidGuard / SafetyNet, and Google Play Services inside every virtual user space.

## Key Features & Fixes

### 1. Built-In microG (`com.google.android.gms` & `com.android.vending`) Integration
- **Bundled microG APKs**: Automatically compiles `gms-core` (`:play-services-core:assembleMapboxDefaultRelease` and `:vending-app:assembleDefaultRelease`) and bundles `GmsCore.apk` and `FakeStore.apk` directly into `Bcore/src/main/assets/gms/`.
- **Official Google Signature Spoofing (`FAKE_PACKAGE_SIGNATURE`)**: `PackageManagerCompat` and `IPackageManagerProxy` spoof `PackageInfo.signatures` and `PackageInfo.signingInfo` (Android 9–15+) with the official Google certificate (`38918a453d07199354f8b19af05ec6562ced5788`) for `com.google.android.gms`, `com.android.vending`, `com.google.android.gsf`, and any package declaring `fake-signature` metadata.
- **Single-UID Virtual Sandbox Compatibility in `gms-core`**:
  - Flattened multi-process attributes (`:ui`, `:persistent`, `:background`, `.unstable`) across `gms-core` manifests so microG components run cohesively inside BlackBox's `:pX` virtual processes.
  - Patched `PackageUtils.java`, `SettingsProvider.kt`, `SettingsContract.kt`, `AuthSignInActivity.kt`, `AssistedSignInActivity.kt`, `GamesSignInActivity.kt`, `CredentialPickerActivity.kt`, `UserConsentPromptActivity.kt`, `AuthenticatorActivity.kt`, and `AskPermissionActivity.java` to resolve virtual caller packages and enable Check-In, GCM, Auth Visibility, SafetyNet, DroidGuard, and Play Licensing/Billing by default.
- **Direct Google Login & microG Settings UI**:
  - Added **Google Login** and **microG** buttons per virtual user space in **GMS Manager** (`GmsManagerActivity`) and **Settings** (`SettingFragment`).

### 2. Core Virtual Engine Bug Fixes (`Bcore`)
- **`BAccountManagerService`**:
  - Fixed `getAuthenticatorTypes()` to return registered authenticators from `mAuthenticatorCache` even before any account is added, with automatic fallback registration for `"com.google"` (`org.microg.gms.auth.loginservice.GoogleLoginService`).
  - Replaced blocked `com.android.internal.R$styleable.AccountAuthenticator` reflection with direct XML `AttributeSet` parsing (`http://schemas.android.com/apk/res/android`).
  - Fixed incremental `loadAuthenticatorCache(packageName)` so installing a new package no longer wipes existing authenticators.
  - Fixed `getAccountsForPackage()`, `getAccountsByTypeForPackage()`, `getAccountsAsUser(null, userId)`, and `resolveAccountVisibility()` so logged-in Google accounts are visible to all cloned apps by default.
  - Implemented `addAccountAsUser()` and `confirmCredentialsAsUser()`, and fixed `getAccounts()` `ArrayStoreException`.
- **Virtual ContentProvider Routing & `AttributionSource` (Android 12–15+)**:
  - Fixed `IActivityManagerProxy.GetContentProvider` to resolve virtual ContentProviders (`com.google.android.gms.*`, `com.google.android.gsf.gservices`, `com.android.vending.*`) via `BPackageManager` first, including same-process provider resolution.
  - Fixed `ContentProviderStub` corrupting SQL selection/authority strings by only replacing `args[0]` (`callingPkg`) and properly fixing `AttributionSourceState` UID/package via `ContextCompat.fixAttributionSourceState`.
  - Removed cache-poisoning `GoogleAccountManagerProxy`, `AuthenticationProxy`, `GmsProxy`, `WorkManagerProxy`, and `AudioPermissionProxy` from `HookManager`.
- **Intent Resolution, Activity Result Delivery & Native Hooks**:
  - Added `queryIntentActivities` and `queryIntentServices` hooks in `IPackageManagerProxy` and fixed `BPackageManagerService.queryIntentActivities` when `pkgName == null`.
  - Fixed `ComponentResolver` returning non-null `ResolveInfo` with `null` inner `ServiceInfo`/`ActivityInfo`/`ProviderInfo`.
  - Fixed `ActivityStack.startActivityLocked` for `startActivityForResult` (`requestCode >= 0`) so sign-in results return to the calling activity.
  - Fixed `HCallbackProxy` on Android 13–15+ (`isTiramisu()`) to update `ActivityClientRecord` and inject `IActivityClientProxy`.
  - Fixed heap buffer overflow in `IO.cpp`, `JNIEnv` local ref leak and Android 14/15 `ArtMethod` access flag offset detection in `JniHook.cpp`, and GPU-breaking `ro.hardware` spoofing in `VirtualSpoof.cpp`.
  - Fixed `ProGuard` rules (`-keep class black.** { *; } -keep interface black.** { *; }`) so Release builds do not obfuscate reflection interfaces.

## Building via GitHub Actions (Recommended)

1. Push this repository to GitHub.
2. Open the **Actions** tab in your GitHub repository and select **Build BlackBox with microG** (runs automatically on `push` to `main`/`master` or manually via **Run workflow**).
3. Download the compiled APKs from the workflow run's **Artifacts** section:
   - `BlackBox-APKs`: Debug and Release APKs (`arm64-v8a`, `armeabi-v7a`, and `universal`) with microG pre-bundled.
   - `microG-APKs`: Standalone patched `GmsCore.apk` and `FakeStore.apk`.
   - `Bcore-AARs`: Debug and Release `Bcore` library AARs.

## Building Locally

```bash
./scripts/build_with_microg.sh
```
Or simply run `./gradlew assembleRelease` (the `:Bcore:prepareMicroGAssets` Gradle task will automatically build `gms-core` and copy `GmsCore.apk` and `FakeStore.apk` into `Bcore/src/main/assets/gms/`).
