# Changelog

## 1.0.0 (versionCode 1)

- First release: WebView shell for the Mission Control dashboard.
- First-run base URL + token setup with POST /api/login validation; values
  stored in EncryptedSharedPreferences; editable via header settings action.
- Safe-area insets (edge-to-edge + WindowInsets padding), always-dark navy/gold
  theme, no white flash on launch.
- Cookie persistence via CookieManager; mixed content never allowed; file
  access disabled; SSL errors cancel; external links open in the browser.
- Native offline error view with Retry.
- WorkManager critical-alert polling (15 min + on app start), dedupe by alert
  id, notification channel + POST_NOTIFICATIONS on Android 13+.
- GitHub Actions: signed release APK build, artifact upload, tag releases with
  APK + .sha256.
