package hu.schoollive.player.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object ApiClient {

    private var retrofit: Retrofit? = null
    private var currentBaseUrl: String = ""

    fun get(baseUrl: String): ApiService {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (retrofit == null || currentBaseUrl != url) {
            currentBaseUrl = url
            retrofit = Retrofit.Builder()
                .baseUrl(url)
                .client(buildOkHttp())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!.create(ApiService::class.java)
    }

    /**
     * Ugyanaz a HTTP-kliens, amit az API is használ.
     *
     * A hangfájlok letöltésének (BellSoundStore) UGYANAZT a TLS-beállítást
     * kell használnia, mint a többi hívásnak – különben a régi Android
     * eszközökön (elavult tanúsítvány-tár) a letöltés elbukna, miközben
     * minden más működik. A tünet néma és félrevezető lenne: offline mindig
     * a gyári default szólna, pedig a hang "fel van töltve".
     */
    fun httpClient(): OkHttpClient {
        if (sharedClient == null) sharedClient = buildOkHttp()
        return sharedClient!!
    }

    private var sharedClient: OkHttpClient? = null

    private fun buildOkHttp(): OkHttpClient {
        // Belső alkalmazás – minden tanúsítványt elfogadunk
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}