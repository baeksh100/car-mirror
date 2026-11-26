# CarMirror

아이폰 화면을 안드로이드 기반 차량 디스플레이(와글캐스트 등)로 미러링하는 수신 앱.

[gum798/MirrorDrive](https://github.com/gum798/MirrorDrive) v0.92 기반, 차량용 안드로이드 디스플레이(와글캐스트 등)에서 아이폰 화면 미러링을 안정화하기 위한 포크. 라이선스는 원본과 같은 GPL-3.0.

## 원본 대비 변경 (0.92-car1)

| 영역 | 변경 | 파일 |
| --- | --- | --- |
| mDNS 광고 | 네트워크 변경 감지 + 5초 폴링으로 재광고. 앱 시작 시 IP가 없어도 핫스팟 연결 후 자동 노출 | `DiscoveryService.kt` |
| IP 선택 | 사용 가능한 모든 사설 IPv4에 광고, 아이폰 핫스팟(172.20.10.x)·wlan 우선, rmnet/tun 등 제외 | `NetUtils.kt` |
| 소리 | MediaCodec AAC-ELD 거부·오류·무출력(60프레임) 시 fdk-aac 소프트웨어 디코더로 자동 전환 | `AudioRenderer.kt`, `native_bridge.cpp`, `NativeBridge.kt` |
| 빌드 | Linux 호환(`sed -i`), ABI별 `--host`, fdk-aac/libplist `-fPIC` | `scripts/build-*.sh` |
| 진단 | 대기 화면 하단에 `net=`(광고 IP), `err=`, `audio=`(hw/sw) 표시, IP 계속 갱신 | `MainActivity.kt`, `WaitingOverlayView.kt` |
| 식별 | 앱 이름·광고 이름 `CarMirror`, applicationId `com.example.mirrordrive.car` (원본과 동시 설치 가능) | `build.gradle.kts`, `AndroidManifest.xml` |

## 기능 (0.92-car7)

- 시동(부팅) 시 자동 실행: 수신 서비스 시작, "다른 앱 위에 표시" 권한이 있으면 화면도 띄움 (`BootReceiver.kt`)
- 한글 상태 안내: 준비됨 / Wi-Fi 꺼짐 / 주소 대기 등 진단을 한 문장으로 (`Guidance.kt`, 단위 테스트 있음)
- 설정: CARMIRROR 글자를 **길게 누르면** 아이폰 목록 이름 변경, 자동 실행 켜기/끄기 (`CarSettings.kt`)
- 미러링 중 Wi-Fi 저지연 잠금으로 끊김 감소 (`ReceiverService.kt`)
- 연결 상태 알림: "○○의 iPhone 연결됨", 끊기면 다시 선택 안내 (`Session.kt`, 네이티브 `notify_event`)
- 30초 넘게 연결이 없으면 문제 해결 체크리스트 표시 (`checklistFor`, 단위 테스트)
- 디코더 오류 시 자동 복구, 세션 종료 시 디코더 초기화 (`VideoRenderer.kt`)
- 차 화면 크기를 아이폰에 광고(가로형, 1920×1080 이내) → 넓은 화면을 꽉 채움 (`CarDisplay.kt`)
- 밤 자동 어둡게: 조도 센서 우선, 없으면 서울 월별 일몰·일출 기준. 설정에서 끄기 가능

## 아이디어 목록 (다음 후보)

| 아이디어 | 기대 효과 | 난이도 |
| --- | --- | --- |
| 연결 즉시 화면 자동 전환(앱이 뒤에 있어도) | 미러링 시작 시 손 안 대도 됨 | 중 |
| 와글캐스트 핫스팟 모드 안내 버튼(설정 바로가기) | IPv6 전용 핫스팟 우회 | 하 |
| 소리 출력 지연 보정(립싱크) | 영상·소리 어긋남 해소 | 상 |
| 화면 꽉 채우기/원본 비율 전환 | 세로 영상 활용 | 하 |
| 진단 로그 파일 저장·공유 | 원격 문제 분석 | 하 |

## 빌드 (Linux)

```bash
export ANDROID_HOME=<sdk>  ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/26.3.11579264
# sdkmanager "platforms;android-34" "build-tools;34.0.0" "ndk;26.3.11579264" "cmake;3.22.1"
# apt-get install -y autoconf automake libtool pkg-config
./scripts/vendor-uxplay.sh
./scripts/build-openssl.sh && ./scripts/build-libplist.sh && ./scripts/build-fdk-aac.sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

## 실차 진단 기록

| 버전 | 진단 화면 | 결론 / 조치 |
| --- | --- | --- |
| car1 | `net=no-network(retrying)` | Wi-Fi(100iPhone)는 연결됐는데 IP 없음 → car2에서 인터페이스 목록 표시 |
| car3 | `ifs=wlan0:v6only v4-wlan0:192.0.0.4*`, `lp=- ni=- wi=-` | 아이폰 핫스팟이 **IPv6만** 줌(안드로이드가 CLAT로 IPv4 흉내). LAN IPv4가 없어 IPv4 전용 광고는 불가능 |
| car4 | (시험 예정) | 시스템 mDNS(NsdManager)로 광고(IPv4·IPv6 모두), 실패 시 jmDNS(인터페이스당 1개, IPv4 없으면 IPv6 link-local). 대기 화면 겹침 수정, 원작자 피드백 QR 숨김 |

## 검증 상태

- 빌드·단위 테스트(`testDebugUnitTest`): 통과
- APK 내 `nativeAac*` JNI 심볼(arm64-v8a, armeabi-v7a): 확인
- 와글캐스트 실기기 설치·앱 실행: 확인(car1~car3)
- 아이폰 미러링 연결·소리: **미검증**
- IPv6 mDNS 광고: 컨테이너에 IPv6가 없어 로컬 검증 불가, 실차 확인 필요

## 남은 일

- 와글캐스트 실기기에서 `net=` / `audio=` 진단 값 확인
- 증상별 추가 수정 (연결 끊김, 화면 회전, 재연결)

## 출처와 라이선스

- [gum798/MirrorDrive](https://github.com/gum798/MirrorDrive) v0.92에서 갈라져 나온 프로젝트이며, 원본 커밋 이력은 이 저장소에 그대로 보존돼 있다.
- 미러링 엔진은 [UxPlay](https://github.com/FDH2/UxPlay)(GPL-3.0). 따라서 전체가 **GPL-3.0**이다 (`LICENSE`).
- 서드파티 라이선스: `THIRD_PARTY_NOTICES.md`.
- AirPlay 수신 MFi 라이선스가 없으므로 개인·비상업 용도로만 쓴다. "AirPlay" 이름·로고를 쓰지 않는다.
