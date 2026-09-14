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

    data class SaveRequest(val source: String?, val epg: String?)

    /** 局域网配置鉴权：所有请求都需携带设置页展示的访问令牌 */
    private fun authorized(session: IHTTPSession): Boolean {
        // 先取令牌（会在缺失时生成），避免短路后令牌永远不生成
        val expected = SP.configToken
        val token = session.parameters["token"]?.firstOrNull() ?: ""
        return token.isNotEmpty() && token == expected
    }

    override fun serve(session: IHTTPSession): Response {
        if (!authorized(session)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "text/html; charset=utf-8",
                UNAUTHORIZED_PAGE
            )
        }
        return when {
            session.method == Method.POST && session.uri == "/api/save" -> {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val body = files["postData"] ?: "{}"
                try {
                    val req = gson.fromJson(body, SaveRequest::class.java)
                    if (req?.source != null) {
                        val newSource = req.source.trim()
                        // 换源后旧缓存失效，清掉避免回退时展示上一个源的数据
                        if (newSource != SP.iptvSourceUrl) ChannelCache.clear()
                        SP.iptvSourceUrl = newSource
                    }
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

            // 数据源可用性测试：?source=xxx（留空则测当前已保存的地址）
            session.uri == "/api/test" -> {
                val source = session.parameters["source"]?.firstOrNull()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: SP.iptvSourceUrl
                val result = TVList.testSource(source)
                newFixedLengthResponse(
                    Response.Status.OK, "application/json; charset=utf-8",
                    gson.toJson(result)
                )
            }

            // 恢复默认设置（保留订阅源与收藏）：低频操作，放在远程页而不是电视设置页
            session.uri == "/api/reset" -> {
                SP.reset()
                // EPG 缓存一并清掉，否则恢复默认后节目单仍是旧的
                runCatching { java.io.File(context.filesDir, "epg_cache.json").delete() }
                handler.post { onConfigChanged?.invoke() }
                newFixedLengthResponse(
                    Response.Status.OK, "text/plain; charset=utf-8",
                    "已恢复默认设置（订阅源与收藏已保留）"
                )
            }

            // 查看最近一次崩溃日志：便于用户直接把堆栈反馈出来
            session.uri == "/api/crash" -> {
                val crashDir = java.io.File(context.filesDir, "crash")
                val latest = crashDir.listFiles()?.maxByOrNull { it.name }
                newFixedLengthResponse(
                    Response.Status.OK, "text/plain; charset=utf-8",
                    latest?.readText() ?: "暂无崩溃日志"
                )
            }

            // 数据源健康统计：用成功率与耗时回答"这几个源该留哪几个"
            session.uri == "/api/sources" -> {
                val list = SourceHealth.snapshot()
                    .entries
                    .sortedByDescending { it.value.updatedAt }
                    .map { (url, agg) ->
                        mapOf(
                            "source" to url,
                            "label" to SourceHealth.label(url),
                            "attempts" to agg.attempts,
                            "success" to agg.okCount,
                            "rate" to agg.successRate,
                            "avgMs" to agg.avgMs,
                            "lastOk" to agg.lastOk,
                            "lastMs" to agg.lastMs,
                            "lastChannels" to agg.lastChannels,
                            "via" to agg.lastVia,
                        )
                    }
                newFixedLengthResponse(
                    Response.Status.OK, "application/json; charset=utf-8",
                    gson.toJson(list)
                )
            }

            session.uri == "/api/config" -> newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8",
                gson.toJson(
                    mapOf(
                        "source" to SP.iptvSourceUrl,
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
                const T = new URLSearchParams(location.search).get('token') || '';
                const q = s => s + (s.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(T);
                fetch(q('/api/config')).then(r=>r.json()).then(j=>{
                  src.value=j.source||'';epg.value=j.epg||'';
                });
                function save(){
                  fetch(q('/api/save'),{method:'POST',
                    headers:{'Content-Type':'application/json'},
                    body:JSON.stringify({source:src.value,epg:epg.value})
                  }).then(r=>r.text()).then(t=>{msg.innerText=t;});
                }
                function test(){
                  msg.innerText='测试中…';
                  fetch(q('/api/test?source='+encodeURIComponent(src.value.trim())))
                    .then(r=>r.json()).then(j=>{
                      msg.innerText = j.ok ? ('可用：'+j.groups+' 个分组 / '+j.channels+' 个频道')
                                           : ('不可用：'+j.message);
                    }).catch(e=>{msg.innerText='测试失败：'+e;});
                }
                function resetAll(){
                  if(!confirm('恢复默认设置？（订阅源与收藏会保留）')) return;
                  fetch(q('/api/reset')).then(r=>r.text()).then(t=>{msg.innerText=t;});
                }
                function crash(){
                  log.innerText='读取中…';
                  fetch(q('/api/crash')).then(r=>r.text()).then(t=>{log.innerText=t;});
                }
                function sources(){
                  log.innerText='读取中…';
                  fetch(q('/api/sources')).then(r=>r.json()).then(function(list){
                    if(!list||!list.length){log.innerText='暂无数据（多启动几次后就有了）';return;}
                    log.innerText = list.map(function(s){
                      var head = '[' + (s.lastOk?'正常':'失败') + '] 成功率 ' + s.rate + '%  (' + s.success + '/' + s.attempts + ')'
                        + '  平均 ' + s.avgMs + 'ms  最近 ' + s.lastMs + 'ms  频道 ' + s.lastChannels;
                      var via = (s.via && s.via !== s.source) ? ('\n实际走: ' + s.via) : '';
                      return head + '\n' + s.source + via;
                    }).join('\n\n');
                  }).catch(function(e){log.innerText='读取失败：'+e;});
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
                <p>EPG 节目单地址（XMLTV，留空用订阅源自带地址）：</p>
                <textarea id="epg" rows="2"></textarea>
                <br><br><button onclick="test()">测试源</button>
                <button onclick="save()">保存并重新加载</button>
                <button onclick="resetAll()">恢复默认设置</button>
                <button onclick="crash()">查看崩溃日志</button>
                <button onclick="sources()">源健康</button>
                <p id="msg"></p>
                <pre id="log" style="white-space:pre-wrap;font-size:12px;color:#555"></pre>
                <script>$js</script>
                </body></html>
            """.trimIndent()
        }

        val HTML_PAGE: String = page()

        /** 缺少/错误访问令牌时的提示页（不泄露令牌） */
        val UNAUTHORIZED_PAGE: String = """
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>我的电视</title></head><body>
            <h3>访问地址不正确</h3>
            <p>请在电视的「设置」中查看带访问码的完整地址，再重新打开。</p>
            </body></html>
        """.trimIndent()
    }
}
