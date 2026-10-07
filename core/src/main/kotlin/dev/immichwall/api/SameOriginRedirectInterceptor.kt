package dev.immichwall.api

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** A redirect this client will not follow. A subtype so downloads can tell it from a dead link. */
class RedirectRefusedException(message: String) : IOException(message)

/**
 * Redirect policy for a client built with `followRedirects(false)`. OkHttp's own redirect
 * handling re-sends custom headers (our `x-api-key`) to whatever host the server names,
 * including an HTTPS→HTTP downgrade. This follows GET/HEAD redirects only when scheme,
 * host and port are unchanged, and fails the call otherwise.
 */
class SameOriginRedirectInterceptor(private val maxHops: Int = 3) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var hops = 0
        while (response.isRedirect && (request.method == "GET" || request.method == "HEAD")) {
            val target = response.header("Location")?.let { request.url.resolve(it) }
            response.close()
            if (target == null) throw RedirectRefusedException("redirect without a usable Location from ${request.url.host}")
            if (!sameOrigin(request.url, target)) {
                throw RedirectRefusedException("refusing redirect from ${request.url.host} to another origin (${target.scheme}://${target.host}:${target.port})")
            }
            if (++hops > maxHops) throw RedirectRefusedException("too many redirects from ${request.url.host}")
            request = request.newBuilder().url(target).build()
            response = chain.proceed(request)
        }
        return response
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port
}
