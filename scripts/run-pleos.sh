#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/android-env.sh

avd_name="${DRIVY_AVD_NAME:-Drivy_Pleos_API34}"
avd_root="${ANDROID_AVD_HOME:-${ANDROID_SDK_HOME:-$HOME}/.android/avd}"
avd_ini="$avd_root/$avd_name.ini"
avd_dir=""
if [[ -f "$avd_ini" ]]; then avd_dir="$(sed -n 's/^path=//p' "$avd_ini" | head -n 1)"; fi
check_dir="${avd_dir:-$avd_root}"
while [[ ! -d "$check_dir" && "$check_dir" != / ]]; do check_dir="$(dirname "$check_dir")"; done

# Conservative preflight, not a substitute for the emulator's own disk check.
minimum_kb=2097152
available_kb="$(df -Pk "$check_dir" | awk 'NR == 2 {print $4}')"
if [[ ! "$available_kb" =~ ^[0-9]+$ ]]; then
    echo "디스크 여유 공간을 확인할 수 없습니다: $check_dir" >&2
    exit 1
fi
if (( available_kb < minimum_kb )); then
    echo "AVD 디스크 여유 공간 부족: $check_dir (남음 $((available_kb / 1024)) MiB, 사전 점검 기준 2048 MiB)." >&2
    echo "Android/app/build는 다시 빌드할 수 있는 산출물입니다. AVD 데이터와 백업은 삭제하지 않습니다." >&2
    if [[ -t 0 && -d Android/app/build ]]; then
        read -r -p "앱 빌드 산출물을 정리하고 다시 확인할까요? [y/N] " answer
        if [[ "$answer" == [yY] || "$answer" == [yY][eE][sS] ]]; then
            ./Android/gradlew -p Android :app:clean
            available_kb="$(df -Pk "$check_dir" | awk 'NR == 2 {print $4}')"
        fi
    fi
    if [[ ! "$available_kb" =~ ^[0-9]+$ ]] || (( available_kb < minimum_kb )); then
        echo "디스크 공간을 추가로 확보한 후 다시 실행하세요. AVD 파일이나 백업은 직접 확인하기 전 삭제하지 마세요." >&2
        exit 1
    fi
    echo "정리 후 여유 공간: $((available_kb / 1024)) MiB. 에뮬레이터를 실행합니다."
fi
exec emulator -avd "$avd_name" -gpu host -feature -Vulkan -no-snapshot-load "$@"
