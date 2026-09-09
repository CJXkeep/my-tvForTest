package com.lizongying.mytv

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

/**
 * 局域网远程配置服务（借鉴 my-tv-0）：
 * 手机/电脑浏览器访问 http://<设备IP>:34567 即可修改订阅源/收藏/EPG。
 */
class ConfigServer(private val context: Context) :
    NanoHTTPD("0.0.0.0", PORT) {

    /** 配置变更回调（MainActivity 用于 recreate 刷新频道列表） */
    var onConfigChanged: (() -> Unit)? = null

    private val gson = Gson()

    data class SaveRequest(val source: String?, val favorites: String?, val epg: String?)

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.POST && session.uri == "/api/save" -> {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val body = files["postData"] ?: "{}"
                try {
                    val req = gson.fromJson(body, SaveRequest::class.java)
                    if (req?.source != null) SP.iptvSourceUrl = req.source.trim()
                    if (req?.favorites != null) SP.favorites = req.favorites.trim()
                    if (req?.epg != null) SP.epgUrl = req.epg.trim()
                    handler.post { onConfigChanged?.invoke() }
                    newFixedLengthResponse(
                        Response.Status.OK, "text/plain; charset=utf-8",
                        "已保存，应用正在重新加载频道列表"
                    )
                } catch (e: Exception) {
                    newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, "text/plain; charset=utf-8",
                        "保存失败: ${e.message}"
                    )
                }
            }

            session.uri == "/api/config" -> newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8",
                gson.toJson(
                    mapOf(
                        "source" to SP.iptvSourceUrl,
                        "favorites" to SP.favorites,
                        "epg" to SP.epgUrl,
                    )
                )
            )

            else -> newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8", HTML_PAGE
            )
        }
    }

    companion object {
        const val PORT = 34567
        private val handler = Handler(Looper.getMainLooper())

        /** 局域网 IPv4 地址 */
        fun lanIp(): String? {
            return try {
                for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
                    for (addr in nif.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }

        private fun page(): String {
            val js = """
                fetch('/api/config').then(r=>r.json()).then(j=>{
                  src.value=j.source||'';fav.value=j.favorites||'';epg.value=j.epg||'';
                });
                function save(){
                  fetch('/api/save',{method:'POST',
                    headers:{'Content-Type':'application/json'},
                    body:JSON.stringify({source:src.value,favorites:fav.value,epg:epg.value})
                  }).then(r=>r.text()).then(t=>{msg.innerText=t;});
                }
            """.trimIndent()
            return """
                <html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>我的电视 - 远程配置</title>
                <style>body{font-family:sans-serif;max-width:720px;margin:20px auto;padding:0 12px}
                textarea{width:100%;box-sizing:border-box}button{padding:8px 24px}#msg{color:#2a7}
                h3{border-bottom:1px solid #ddd;padding-bottom:6px}</style></head><body>
                <h3>我的电视 - 远程配置</h3>
                <p>订阅源地址（多个用逗号分隔，支持 m3u / txt / json）：</p>
                <textarea id="src" rows="5"></textarea>
                <p>收藏频道（归一化名，逗号分隔，按顺序置顶编号，可留空）：</p>
                <textarea id="fav" rows="2"></textarea>
                <p>EPG 节目单地址（XMLTV，留空用订阅源自带地址）：</p>
                <textarea id="epg" rows="2"></textarea>
                <br><br><button onclick="save()">保存并重新加载</button>
                <p id="msg"></p>
                <script>$js</script>
                </body></html>
            """.trimIndent()
        }

        val HTML_PAGE: String = page()
    }
}
