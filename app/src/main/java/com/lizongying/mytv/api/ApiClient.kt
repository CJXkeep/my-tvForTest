package com.lizongying.mytv.api


import android.os.Build
import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.net.ssl.SSLContext


class ApiClient {
    private val fUrl = "https://m.fengshows.com/"

    private val okHttpClient: OkHttpClient = buildClient()

    val fAuthService: FAuthService by lazy {
        Retrofit.Builder()
            .baseUrl(fUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(FAuthService::class.java)
    }

    private fun enableTls12OnPreLollipop(client: OkHttpClient.Builder): OkHttpClient.Builder {
        if (Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 22) {
            try {
                val sc = SSLContext.getInstance("TLSv1.2")

                sc.init(null, null, null)

                client.sslSocketFactory(Tls12SocketFactory(sc.socketFactory))

                val cs = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build()

                val specs: MutableList<ConnectionSpec> = ArrayList()
                specs.add(cs)
                specs.add(ConnectionSpec.COMPATIBLE_TLS)
                specs.add(ConnectionSpec.CLEARTEXT)

                client.connectionSpecs(specs)
            } catch (exc: java.lang.Exception) {
                Log.e("OkHttpTLSCompat", "Error while setting TLS 1.2", exc)
            }
        }

        return client
    }

    /**
     * 使用**系统信任链**（不再信任所有证书）。
     *
     * 全局 trust-all 会让 HTTPS 失去意义（可被中间人替换内容），这里只保留两件正当的事：
     * 1. Android 4.4~5.0 默认不带 TLS1.2，显式开启（仍使用系统默认信任管理器）；
     * 2. DNS 结果短期缓存（CDN 换 IP 后可自愈）。
     */
    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder().dns(DnsCache())
        return enableTls12OnPreLollipop(builder).build()
    }
}
