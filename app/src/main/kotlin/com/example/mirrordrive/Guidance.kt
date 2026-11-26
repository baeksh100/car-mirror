package com.example.mirrordrive

/** What the waiting screen should tell the driver, derived from the raw diagnostics. */
data class Guidance(val text: String, val ok: Boolean)

private fun hasWifi(ifDump: String) =
    ifDump.split(' ').any { it.startsWith("wlan") || it.startsWith("ap") || it.startsWith("swlan") }

private fun advertising(status: String) = status.startsWith("nsd ok") || status.startsWith("jmdns on")

/**
 * Turns discovery status + interface dump + session state into one plain Korean sentence. Pure (no
 * Android types) so it is unit-tested.
 *
 * @param status     DiscoveryService.status, e.g. "nsd ok(2/2) CarMirror", "jmdns on fe80::1%wlan0",
 *                   "jmdns:no-network(retrying)", "nsd:registering", "init"
 * @param ifDump     interfaceDump(), e.g. "wlan0:v6only usb0:v6only v4-wlan0:192.0.0.4*"
 * @param name       the receiver name the iPhone will list
 * @param nativeOk   false when the native library failed to load
 * @param session    current mirroring session state
 */
fun guidanceFor(
    status: String,
    ifDump: String,
    name: String,
    nativeOk: Boolean = true,
    session: SessionSnapshot = SessionSnapshot(),
): Guidance {
    if (!nativeOk) return Guidance("이 기기에서는 미러링 엔진을 실행할 수 없어요", false)
    val who = session.iphoneName ?: "아이폰"
    return when {
        session.running -> Guidance("$who 연결됨", true)
        advertising(status) && session.endedAgoMs != null && session.endedAgoMs < RECONNECT_HINT_MS ->
            Guidance("연결이 끊겼어요 · $who 에서 화면 미러링 → $name 을 다시 선택하세요", false)
        advertising(status) ->
            Guidance("준비됐어요 · 아이폰 제어센터 → 화면 미러링 → $name", true)
        status.startsWith("nsd:registering") || status == "init" ->
            Guidance("준비 중이에요…", false)
        status.contains("no-network") && !hasWifi(ifDump) ->
            Guidance("Wi-Fi가 꺼져 있거나 연결이 안 됐어요 · 아이폰 핫스팟에 연결하세요", false)
        status.contains("no-network") ->
            Guidance("Wi-Fi 주소를 기다리는 중이에요 · 아이폰 핫스팟 화면을 열어 두세요", false)
        status == "stopped" -> Guidance("미러링이 꺼져 있어요", false)
        else -> Guidance("연결 상태 확인 중이에요", false)
    }
}

/**
 * After [CHECKLIST_AFTER_MS] of waiting with no stream, a short troubleshooting checklist like the
 * big receiver apps show. Each line: "✓ …" (verified), "✗ …" (verified problem), "• …" (something
 * only the driver can check on the iPhone). Empty list = don't show.
 */
fun checklistFor(status: String, ifDump: String, session: SessionSnapshot): List<String> {
    if (session.running || session.waitingMs < CHECKLIST_AFTER_MS) return emptyList()
    val wifi = hasWifi(ifDump)
    val wlanV4 = ifDump.split(' ').any { (it.startsWith("wlan") || it.startsWith("ap")) && !it.contains("v6only") }
    val out = ArrayList<String>()
    out += if (wifi) "✓ Wi-Fi 연결됨" else "✗ Wi-Fi 연결 안 됨 → 와글캐스트 Wi-Fi를 아이폰 핫스팟에 연결"
    if (wifi) out += if (wlanV4) "✓ 주소: IPv4" else "• 주소: IPv6 전용 (안 보이면 와글캐스트 핫스팟에 아이폰을 연결해 보세요)"
    out += if (advertising(status)) "✓ 아이폰에 이 기기를 알리는 중" else "✗ 기기 알림 실패 → 앱을 껐다 켜 보세요"
    out += "• 아이폰 무선 카플레이 끄기 (켜져 있으면 목록에 안 나와요)"
    out += "• 아이폰 개인용 핫스팟 → '호환성 최대화' 켜기"
    out += "• 아이폰 잠금 해제 → 제어센터 → 화면 미러링"
    return out
}

const val RECONNECT_HINT_MS = 120_000L
const val CHECKLIST_AFTER_MS = 30_000L
