# KisaAgent

C4용 기사에이전트 Android 앱 소스와 배포 APK가 있는 저장소입니다.

다른 컴퓨터에서는 이 저장소를 복제한 뒤 Android Studio에서 저장소 루트를 열고 Android SDK 36을 설치합니다. Android Studio가 로컬 SDK 경로를 설정한 뒤, Windows에서는 아래 명령으로 빌드와 단위 테스트를 실행할 수 있습니다.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

서명 배포는 기존 배포 인증서의 키로만 진행합니다. 서명 설정은 로컬 `signing.properties`에 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 지정합니다. APK와 업데이트 JSON은 저장소 루트에서 함께 관리합니다.
