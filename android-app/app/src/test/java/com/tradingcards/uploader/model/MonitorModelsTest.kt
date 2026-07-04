package com.tradingcards.uploader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorModelsTest {
    @Test
    fun healthReportsNotConfiguredBeforeReachability() {
        val status = status(configured = false, reachable = false)

        assertEquals(ScannerHealth.NotConfigured, status.health())
    }

    @Test
    fun healthReportsUnreachableScanner() {
        val status = status(configured = true, reachable = false)

        assertEquals(ScannerHealth.Unreachable, status.health())
    }

    @Test
    fun healthReportsReadyScanner() {
        val status =
            status(
                ready = true,
                scanner = mapOf("components" to mapOf("models" to mapOf("state" to "ready"))),
            )

        assertEquals(ScannerHealth.Ready, status.health())
    }

    @Test
    fun healthReportsWarmupWhileModelWarmupIsPending() {
        val status =
            status(
                ready = false,
                statusCode = 503,
                scanner = mapOf("components" to mapOf("models" to mapOf("state" to "in_progress"))),
            )

        assertEquals(ScannerHealth.WarmingUp, status.health())
    }

    @Test
    fun healthReportsNotReadyForFailedWarmup() {
        val status =
            status(
                ready = false,
                statusCode = 503,
                scanner = mapOf("components" to mapOf("models" to mapOf("state" to "failed"))),
            )

        assertEquals(ScannerHealth.NotReady, status.health())
    }

    @Test
    fun componentSummariesFlattenKnownShapes() {
        val status =
            status(
                ready = true,
                scanner =
                    mapOf(
                        "components" to
                            mapOf(
                                "settings" to "ok",
                                "storage" to true,
                                "models" to mapOf("state" to "ready"),
                                "ocr" to mapOf("enabled" to true, "configured" to false),
                            ),
                    ),
            )

        val summaries = status.componentSummaries().associate { it.name to it.detail }

        assertEquals("ok", summaries["settings"])
        assertEquals("ok", summaries["storage"])
        assertEquals("ready", summaries["models"])
        assertEquals("enabled, not configured", summaries["ocr"])
    }

    @Test
    fun activitiesJoinRawUploadsWithProcessedOutputs() {
        val raw =
            listOf(
                galleryImage("raw/a.jpg", category = "raw", modified = "2026-07-01T10:00:00Z"),
                galleryImage("raw/b.jpg", category = "raw", modified = "2026-07-02T10:00:00Z"),
            )
        val processed =
            listOf(
                galleryImage("processed/a_0.jpg", source = "raw/a.jpg", modified = "2026-07-01T10:05:00Z"),
                galleryImage("processed/a_1.jpg", source = "raw/a.jpg", modified = "2026-07-01T10:06:00Z"),
            )

        val activities = buildSourceActivities(raw, processed)

        assertEquals(listOf("raw/b.jpg", "raw/a.jpg"), activities.map { it.sourceBlobName })
        val pending = activities.first { it.sourceBlobName == "raw/b.jpg" }
        assertEquals(0, pending.cropCount)
        val done = activities.first { it.sourceBlobName == "raw/a.jpg" }
        assertEquals(2, done.cropCount)
        assertEquals("2026-07-01T10:06:00Z", done.lastProcessedUtc)
        assertTrue(done.rawPresent)
    }

    @Test
    fun activitiesSurfaceOrphanedOutputsWhoseRawSourceIsGone() {
        val processed =
            listOf(
                galleryImage("processed/x_0.jpg", source = "raw/x.jpg", modified = "2026-07-01T09:00:00Z"),
            )

        val activities = buildSourceActivities(emptyList(), processed)

        assertEquals(1, activities.size)
        assertEquals("raw/x.jpg", activities.single().sourceBlobName)
        assertFalse(activities.single().rawPresent)
        assertEquals(1, activities.single().cropCount)
    }

    private fun status(
        configured: Boolean = true,
        reachable: Boolean = true,
        statusCode: Int? = 200,
        ready: Boolean? = null,
        scanner: Map<String, Any?>? = null,
    ) = ScannerStatusResponse(
        configured = configured,
        reachable = reachable,
        statusCode = statusCode,
        ready = ready,
        scanner = scanner,
    )

    private fun galleryImage(
        name: String,
        category: String = "processed",
        source: String? = null,
        modified: String? = null,
    ) = GalleryImage(
        category = category,
        name = name,
        sourceBlobName = source,
        size = 1,
        lastModifiedUtc = modified,
        previewUrl = "/api/v1/admin/gallery/image?name=$name",
        canCascade = source != null,
    )
}
