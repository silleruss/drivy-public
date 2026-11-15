# APK 다운로드와 체크섬

APK 크기가 GitHub의 파일당 100MB 제한을 넘어 바이너리는 [GitHub Releases](https://github.com/silleruss/drivy-public/releases/tag/submission-2026-11-15)에 제공합니다.

- [DRIVY_demo.apk](https://github.com/silleruss/drivy-public/releases/download/submission-2026-11-15/DRIVY_demo.apk): uitest 빌드, Android debug 인증서로 서명. 패키지 `io.github.silleruss.drivy.uitest`. 정상 앱과 저장 경로가 분리되며 테스트 메뉴를 제공합니다.
- [DRIVY_release-unsigned.apk](https://github.com/silleruss/drivy-public/releases/download/submission-2026-11-15/DRIVY_release-unsigned.apk): release 빌드, 서명 없음. 이 파일은 그대로 설치할 수 없습니다. 최종 제출용 서명 APK 준비는 보류 상태입니다.
- `SHA256SUMS`: 두 파일의 SHA-256. 다운로드한 APK를 이 폴더에 두고 `shasum -a 256 -c SHA256SUMS`로 확인합니다. APK 바이너리는 Git에서 제외됩니다.

소유자 관리 키로 서명한 뒤 설치·복원·인증서 확인을 마쳐야 공식 실행 가능한 release APK로 제출할 수 있습니다. debug 서명을 배포 서명으로 표시하지 않습니다.
