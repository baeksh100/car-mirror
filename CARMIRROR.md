# CarMirror

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
