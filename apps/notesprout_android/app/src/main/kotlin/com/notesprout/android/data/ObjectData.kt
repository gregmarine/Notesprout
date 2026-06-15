package com.notesprout.android.data

import android.graphics.RectF
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * kotlinx.serialization models for the small ad-hoc JSON blobs that used to be
 * parsed/produced with `org.json` and string interpolation (M2).
 *
 * Three shapes live here:
 *  - [BoundingBox] — the `boundingBox` column of every `notebook` row (`{x,y,width,height}`).
 *  - [PageData]    — the `data` column of a `type="page"` row (`{width,height,template,snapshot}`).
 *  - [TemplateData]— the `data` column of a `type="template"` row (`{width,height,name,image}`).
 *
 * Wire format is byte-compatible with the previous org.json/string-template output so no DB
 * migration is required. [encodeDefaults] = true keeps `{"x":0.0,...}` zero fields explicit;
 * [explicitNulls] = false omits the snapshot field when absent.
 */
private val codec = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/** `{x, y, width, height}` — the `boundingBox` column shape. */
@Serializable
data class BoundingBox(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    /** Convert to an Android [RectF] (left/top = x/y, right/bottom = x+width / y+height). */
    fun toRectF(): RectF = RectF(x, y, x + width, y + height)

    companion object {
        fun fromRectF(r: RectF): BoundingBox = BoundingBox(r.left, r.top, r.width(), r.height())

        /** Deserialize; returns null on malformed JSON. */
        fun fromJson(json: String): BoundingBox? =
            try { codec.decodeFromString(serializer(), json) } catch (e: Exception) { null }
    }
}

/** Serialize a [RectF] straight to the `{x,y,width,height}` JSON stored in `boundingBox`. */
fun RectF.toBoundingBoxJson(): String = BoundingBox.fromRectF(this).toJson()

/** Parse a `boundingBox` JSON string to a [RectF]; returns null on malformed input. */
fun parseBoundingBox(json: String): RectF? = BoundingBox.fromJson(json)?.toRectF()

/** `{width, height, template, snapshot}` — the `data` column of a `type="page"` row. */
@Serializable
data class PageData(
    val width: Float = 0f,
    val height: Float = 0f,
    val template: String = "",
    val snapshot: String? = null,
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        /** Deserialize; falls back to an empty [PageData] on malformed JSON. */
        fun fromJson(json: String): PageData =
            try { codec.decodeFromString(serializer(), json) } catch (e: Exception) { PageData() }
    }
}

/** `{width, height, name, image}` — the `data` column of a `type="template"` row. */
@Serializable
data class TemplateData(
    val width: Int = 0,
    val height: Int = 0,
    val name: String = "",
    val image: String = "",
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        /** Deserialize; returns null on malformed JSON. */
        fun fromJson(json: String): TemplateData? =
            try { codec.decodeFromString(serializer(), json) } catch (e: Exception) { null }
    }
}
