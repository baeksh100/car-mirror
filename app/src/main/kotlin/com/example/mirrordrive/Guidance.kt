package com.example.mirrordrive

/** What the waiting screen should tell the driver, derived from the raw diagnostics. */
data class Guidance(val text: String, val ok: Boolean)

/**
 * Turns discovery status + interface dump into one plain Korean sentence. Pure (no Android types)
 * so it is unit-tested.
 *
 * @param status     DiscoveryService.status, e.g. "nsd ok(2/2) CarMirror", "jmdns on fe80::1%wlan0",
 *                   "jmdns:no-network(retrying)", "nsd:registering", "init"
 * @param ifDump     interfaceDump(), e.g. "wlan0:v6only usb0:v6only v4-wlan0:192.0.0.4*"
 * @param name       the receiver name the iPhone will list
 * @param nativeOk   false when the native library failed to load
 */
fun guidanceFor(status: String, ifDump: String, name: String, nativeOk: Boolean = true): Guidance {
    if (!nativeOk) return Guidance("이 기기에서는 미러링 엔진을 실행할 수 없어요", false)
    val hasWifi = ifDump.split(' ').any { it.startsWith("wlan") || it.startsWith("ap") || it.startsWith("swlan") }
    return when {
        status.startsWith("nsd ok") || status.startsWith("jmdns on") ->
            Guidance("준비됐어요 · 아이폰 제어센터 → 화면 미러링 → $name", true)
        status.startsWith("nsd:registering") || status == "init" ->
            Guidance("준비 중이에요…", false)
        status.contains("no-network") && !hasWifi ->
            Guidance("Wi-Fi가 꺼져 있거나 연결이 안 됐어요 · 아이폰 핫스팟에 연결하세요", false)
        status.contains("no-network") ->
            Guidance("Wi-Fi 주소를 기다리는 중이에요 · 아이폰 핫스팟 화면을 열어 두세요", false)
        status == "stopped" -> Guidance("미러링이 꺼져 있어요", false)
        else -> Guidance("연결 상태 확인 중이에요", false)
    }
}
