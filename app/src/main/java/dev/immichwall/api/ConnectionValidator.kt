package dev.immichwall.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

data class CheckResult(val name: String, val ok: Boolean, val detail: String)

/**
 * Per-scope validation of a server URL + API key, run from the onboarding wizard.
 * Exercises each API-key permission the app needs and names the exact missing
 * scope on 403, so the user can fix the key instead of guessing:
 *
 *  1. Server reachable      — GET /api/server/version (no auth)
 *  2. People list           — person.read
 *  3. Asset search          — asset.read (also supplies the test asset for 4 & 5)
 *  4. Thumbnail download    — asset.view
 *  5. Face data             — face.read
 *
 * Synchronous; callers dispatch on Dispatchers.IO.
 */
class ConnectionValidator(private val client: ImmichApiClient) {

    fun validate(): List<CheckResult> {
        val results = mutableListOf<CheckResult>()

        results += runCheck("Server reachable") {
            val v = client.getServerVersion()
            "Immich v${v.major}.${v.minor}.${v.patch}"
        }

        results += runCheck("People list (person.read)") {
            val people = client.getPeople(page = 1, size = 1)
            "${people.total} people on server"
        }

        var testAsset: AssetDto? = null
        results += runCheck("Asset search (asset.read)") {
            val assets = client.searchRandom(buildJsonObject { put("type", "IMAGE") }, size = 1)
            testAsset = assets.firstOrNull()
            if (testAsset != null) "Search OK" else "Search OK — but the library has no images"
        }

        val asset = testAsset
        if (asset != null) {
            results += runCheck("Thumbnail download (asset.view)") {
                val tmp = File.createTempFile("immichwall-check", ".img")
                try {
                    val download = client.downloadAssetImage(asset.id, tmp)
                    "${download.contentType} (${download.tier}), ${tmp.length() / 1024} KB"
                } finally {
                    tmp.delete()
                    File(tmp.path + ".tmp").delete() // leftover from a failed download, if any
                }
            }
            results += runCheck("Face data (face.read)") {
                val faces = client.getFaces(asset.id)
                "${faces.size} face(s) on test asset"
            }
        } else {
            val searchOk = results.last().ok
            val detail =
                if (searchOk) "Skipped — library has no images to test with"
                else "Skipped — asset search failed, no test asset"
            results += CheckResult("Thumbnail download (asset.view)", searchOk, detail)
            results += CheckResult("Face data (face.read)", searchOk, detail)
        }

        return results
    }

    private fun runCheck(name: String, block: () -> String): CheckResult {
        return try {
            CheckResult(name, true, block())
        } catch (e: ApiException) {
            val detail = if (e.code == 403 && e.scopeHint != null) {
                "HTTP 403 — API key is missing the '${e.scopeHint}' permission"
            } else {
                e.message ?: "HTTP ${e.code}"
            }
            CheckResult(name, false, detail)
        } catch (e: IOException) {
            CheckResult(name, false, "Network error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            CheckResult(name, false, "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
