package io.github.qasecret.rp.internal

import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ
import java.util.Properties

/**
 * Identifies this reporting agent to ReportPortal. The version is baked in at build time
 * (`kotest-rp.properties`, expanded by Gradle) and surfaced as launch attributes for analytics and
 * filtering (agent name/version, OS, JVM).
 */
internal object RpAgent {

    const val NAME = "kotest-rp"

    val version: String by lazy { loadVersion() }

    /**
     * Attributes added to every launch. Sent as regular (non-system) attributes so they are visible
     * and filterable in the ReportPortal UI — client-supplied `system=true` attributes are dropped by
     * the server on launch creation.
     */
    fun launchAttributes(): Set<ItemAttributesRQ> = setOf(
        ItemAttributesRQ("agent", "$NAME|$version"),
        ItemAttributesRQ("os", System.getProperty("os.name") ?: "unknown"),
        ItemAttributesRQ("jvm", System.getProperty("java.version") ?: "unknown"),
    )

    private fun loadVersion(): String = runCatching {
        RpAgent::class.java.getResourceAsStream("/kotest-rp.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
        }
    }.getOrNull()?.takeIf { it.isNotBlank() && it != "\${version}" } ?: "unknown"
}
