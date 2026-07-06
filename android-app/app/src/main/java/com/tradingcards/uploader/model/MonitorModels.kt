package com.tradingcards.uploader.model

/**
 * Proxy response from `GET v1/admin/scanner/status`. The `scanner` payload is
 * the scanner's own `/api/ready` body passed through untouched; it is kept
 * loosely typed because this app does not own that contract.
 */
data class ScannerStatusResponse(
    val configured: Boolean,
    val reachable: Boolean,
    val statusCode: Int?,
    val ready: Boolean?,
    val scanner: Map<String, Any?>?,
)

enum class ScannerHealth {
    NotConfigured,
    Unreachable,
    WarmingUp,
    NotReady,
    Ready,
}

private val WARMUP_PENDING_STATES = setOf("not_started", "in_progress")

fun ScannerStatusResponse.health(): ScannerHealth =
    when {
        !configured -> ScannerHealth.NotConfigured
        !reachable -> ScannerHealth.Unreachable
        ready == true -> ScannerHealth.Ready
        modelWarmupState() in WARMUP_PENDING_STATES -> ScannerHealth.WarmingUp
        else -> ScannerHealth.NotReady
    }

/** The scanner's background model-warmup state (`not_started/in_progress/ready/failed`), when reported. */
fun ScannerStatusResponse.modelWarmupState(): String? = componentStateText(componentsMap()?.get("models"))

data class ScannerComponent(
    val name: String,
    val detail: String,
)

/**
 * Flattens the scanner's `components.{settings, models, storage, ocr}` block
 * into displayable name/detail pairs, tolerating shape drift since the
 * component payloads belong to the scanner's contract.
 */
fun ScannerStatusResponse.componentSummaries(): List<ScannerComponent> =
    componentsMap()
        ?.mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            ScannerComponent(name = name, detail = describeComponentValue(value))
        }.orEmpty()

private fun ScannerStatusResponse.componentsMap(): Map<*, *>? = scanner?.get("components") as? Map<*, *>

private fun componentStateText(value: Any?): String? =
    when (value) {
        is String -> value
        is Map<*, *> -> (value["state"] ?: value["status"]) as? String
        else -> null
    }

private fun describeComponentValue(value: Any?): String =
    when (value) {
        null -> "unknown"
        is String -> value
        is Boolean -> if (value) "ok" else "unavailable"
        is Map<*, *> -> describeComponentMap(value)
        else -> value.toString()
    }

private fun describeComponentMap(value: Map<*, *>): String {
    componentStateText(value)?.let { return it }
    val enabled = value["enabled"] as? Boolean
    val configured = value["configured"] as? Boolean
    return when {
        enabled == false -> "disabled"
        enabled == true && configured == false -> "enabled, not configured"
        enabled == true -> "enabled"
        else ->
            value.entries
                .filter { it.value is String || it.value is Boolean || it.value is Number }
                .joinToString(", ") { "${it.key}: ${it.value}" }
                .ifEmpty { "unknown" }
    }
}

/**
 * One row of pipeline activity: a raw upload joined with the crops the
 * scanner derived from it (matched through the scanner's lineage manifests
 * via `sourceBlobName`).
 */
data class SourceActivity(
    val sourceBlobName: String,
    val uploadedAtUtc: String?,
    val cropCount: Int,
    val lastProcessedUtc: String?,
    val rawPresent: Boolean,
)

/**
 * Joins raw uploads with processed outputs by source blob. Uploads with no
 * outputs surface as pending work; outputs whose raw source is gone surface
 * as orphaned so cleanup is visible. Sorted by most recent activity.
 */
fun buildSourceActivities(
    rawItems: List<GalleryImage>,
    processedItems: List<GalleryImage>,
): List<SourceActivity> {
    val outputsBySource =
        processedItems
            .filter { it.sourceBlobName != null }
            .groupBy { requireNotNull(it.sourceBlobName) }
    val rawRows =
        rawItems.map { raw ->
            val outputs = outputsBySource[raw.name].orEmpty()
            SourceActivity(
                sourceBlobName = raw.name,
                uploadedAtUtc = raw.lastModifiedUtc,
                cropCount = outputs.size,
                lastProcessedUtc = outputs.mapNotNull { it.lastModifiedUtc }.maxOrNull(),
                rawPresent = true,
            )
        }
    val rawNames = rawItems.mapTo(mutableSetOf()) { it.name }
    val orphanRows =
        outputsBySource
            .filterKeys { it !in rawNames }
            .map { (source, outputs) ->
                SourceActivity(
                    sourceBlobName = source,
                    uploadedAtUtc = null,
                    cropCount = outputs.size,
                    lastProcessedUtc = outputs.mapNotNull { it.lastModifiedUtc }.maxOrNull(),
                    rawPresent = false,
                )
            }
    return (rawRows + orphanRows).sortedByDescending { activity ->
        // ISO-8601 UTC timestamps compare chronologically as strings.
        maxOf(activity.lastProcessedUtc.orEmpty(), activity.uploadedAtUtc.orEmpty())
    }
}
