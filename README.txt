[QC261127] Android APK 빌드 가이드
1) GitHub 저장소에 압축을 풀어 업로드(.github 폴더 포함)하면 Actions 탭에서 서명된 .apk와 Play Store용 .aab(bundleRelease)가 자동 빌드됩니다.
2) 로컬 Android Studio에서 열려면 폴더를 그대로 Import Project로 열면 됩니다.
3) 웹 앱 내 엑셀(.xlsx)·CSV·JSON·ZIP 등 파일 저장(blob:/data:)은 Android 다운로드(Download) 폴더에 자동 저장되도록 네이티브 브릿지가 포함되어 있습니다.
4) Google Play Billing(9.1.0)이 자동 탑재되어 웹 앱에서 window.AndroidBilling.purchase(productId) / restorePurchases()를 바로 호출할 수 있으며, 결과는 android-billing 커스텀 이벤트로 수신됩니다.
5) Google AdMob(play-services-ads:25.5.0, API 36 호환)이 자동 탑재되어 웹 앱에서 window.AndroidAds.showBanner() / hideBanner() / showInterstitial() / setAdFree(true|false)를 호출할 수 있습니다.

[GitHub Secrets 고정 서명 키 등록 방법 (앱 업데이트 필수)]
- GitHub 저장소 > Settings > Secrets and variables > Actions > New repository secret에서 아래 4개를 등록하세요.
  1. KEYSTORE_BASE64 : 키스토어 파일(.keystore/.jks/.p12)의 Base64 문자열
  2. KEYSTORE_PASS   : 키스토어(Store) 비밀번호
  3. KEY_ALIAS       : 키 별칭 (예: securepack)
  4. KEY_PASS        : 키 비밀번호 (Store 비밀번호와 같으면 동일 값 입력)
- 등록 후 Actions를 실행하면 매 빌드마다 동일한 고정 키로 .apk와 .aab가 안전하게 서명됩니다.