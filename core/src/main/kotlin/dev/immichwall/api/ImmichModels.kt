package dev.immichwall.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Single shared Json instance for the whole app (API DTOs, SourceSpec persistence,
 * cache manifest). Per CONTRACTS.md: `ignoreUnknownKeys = true; encodeDefaults = true`.
 *
 * Note: SourceSpec's `"mode"` class discriminator is declared on the sealed class itself
 * (via @JsonClassDiscriminator in source/SourceSpec.kt), not here, so this instance stays
 * exactly as the contract specifies.
 */
object ApiJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

@Serializable
data class ServerVersionDto(
    val major: Int,
    val minor: Int,
    val patch: Int,
)

@Serializable
data class PersonDto(
    val id: String,
    val name: String = "",
    val thumbnailPath: String? = null,
    val isHidden: Boolean = false,
    val birthDate: String? = null,
)

@Serializable
data class PeopleResponseDto(
    val people: List<PersonDto>,
    val total: Int = 0,
    val hidden: Int = 0,
    val hasNextPage: Boolean = false,
)

@Serializable
data class AssetDto(
    val id: String,
    val type: String,
    val width: Int? = null,
    val height: Int? = null,
    val thumbhash: String? = null,
    val originalFileName: String? = null,
    val fileCreatedAt: String? = null,
    val isFavorite: Boolean = false,
    val exifInfo: ExifLiteDto? = null,
)

/**
 * EXIF subset. Search responses omit exifInfo entirely; the per-asset detail endpoint
 * ([ImmichApiClient.getAsset]) fills the full set — the quality scorer's inputs.
 */
@Serializable
data class ExifLiteDto(
    val city: String? = null,
    val country: String? = null,
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val fNumber: Double? = null,
    val focalLength: Double? = null,
    val iso: Int? = null,
    val rating: Int? = null,
)

@Serializable
data class AssetFaceDto(
    val id: String,
    val boundingBoxX1: Int,
    val boundingBoxY1: Int,
    val boundingBoxX2: Int,
    val boundingBoxY2: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val person: PersonDto? = null,
    val sourceType: String? = null,
)

@Serializable
data class AlbumDto(
    val id: String,
    val albumName: String,
    val assetCount: Int = 0,
)

@Serializable
data class SearchAssetsPage(
    val items: List<AssetDto> = emptyList(),
    val total: Int = 0,
    val count: Int = 0,
    val nextPage: String? = null,
)

@Serializable
data class SearchResponseDto(
    val assets: SearchAssetsPage = SearchAssetsPage(),
)

@Serializable
data class MemoryDto(
    val id: String = "",
    val type: String = "",
    val assets: List<AssetDto> = emptyList(),
    val data: MemoryYearDto? = null,
)

/** `data` payload of an on-this-day memory: which year the photos come from. */
@Serializable
data class MemoryYearDto(
    val year: Int = 0,
)

/**
 * Thrown for any non-2xx HTTP response. Transport-level failures surface as [java.io.IOException].
 *
 * @param code HTTP status code.
 * @param scopeHint on 403, the Immich API-key permission the key is most likely missing for
 *   the endpoint that failed (e.g. `person.read`, `asset.read`, `asset.view`, `face.read`,
 *   `album.read`, `memory.read`); null otherwise.
 */
class ApiException(
    val code: Int,
    message: String,
    val scopeHint: String? = null,
) : Exception(message)
