# APK 다운로드와 체크섬

APK 크기가 GitHub의 파일당 100MB 제한을 넘어 바이너리는 [GitHub Releases](https://github.com/silleruss/drivy-public/releases/tag/v0.2.0)에 제공합니다.

- [drivy-signed-release.zip](https://github.com/silleruss/drivy-public/releases/download/v0.2.0/drivy-signed-release.zip): 서명된 `이승룡_drivy_release.apk`가 들어 있습니다. GitHub가 한글 자산명을 변경하므로 ZIP을 압축 해제해 사용하세요. 서명 검증은 통과했고 독립 설치 검증은 아직 완료하지 않았습니다.
- [서명된 APK 직접 다운로드](https://github.com/silleruss/drivy-public/releases/download/v0.2.0/_drivy_release.apk): GitHub가 변경한 파일명은 `_drivy_release.apk`입니다. ZIP에 포함된 APK와 바이트가 같습니다.
- [DRIVY_demo.apk](https://github.com/silleruss/drivy-public/releases/download/v0.2.0/DRIVY_demo.apk): uitest 빌드, Android debug 인증서로 서명. 패키지 `io.github.silleruss.drivy.uitest`. 정상 앱과 저장 경로가 분리되며 테스트 메뉴를 제공합니다.
- [DRIVY_release-unsigned.apk](https://github.com/silleruss/drivy-public/releases/download/v0.2.0/DRIVY_release-unsigned.apk): release 빌드, 서명 없음. 이 파일은 그대로 설치할 수 없습니다. 서명된 제출용 APK는 위 ZIP에 들어 있습니다.
- `SHA256SUMS`: 세 APK의 SHA-256. 다운로드한 APK를 이 폴더에 두고 `shasum -a 256 -c SHA256SUMS`로 확인합니다. APK 바이너리는 Git에서 제외됩니다.

소유자 관리 키로 서명하고 인증서를 확인했습니다. 제출 전에 별도 환경에서 설치와 복원을 확인해야 합니다. debug 서명을 배포 서명으로 표시하지 않습니다.

최종 제출용 파일명은 `이승룡_drivy_release.apk`입니다. APK가 GitHub 파일 크기 제한을 넘고 한글 자산명이 변경되어, GitHub Releases의 ZIP에 원래 파일명을 보존했습니다. 제출 전 압축을 풀고 별도 환경에서 설치를 확인해 주세요.
