package app.gamenative.utils

import com.winlator.core.envvars.EnvVars

/**
 * DOOM 2016 startup/runtime performance profiles for the standalone Quest build.
 *
 * Safe leaves the normal container/FEX settings alone.
 * Fast Boot prioritises shorter translation/startup time while keeping FEX TSO enabled.
 * Max keeps the persistent caches but uses more aggressive runtime/JIT settings.
 */
object DoomPerformance {
    const val MODE_SAFE = "safe"
    const val MODE_FAST_BOOT = "fast_boot"
    const val MODE_MAX = "max"

    const val RENDER_OPENGL = "opengl"
    const val RENDER_VULKAN = "vulkan"

    fun normalizeMode(value: String?): String = when (value?.lowercase()) {
        MODE_SAFE -> MODE_SAFE
        MODE_MAX -> MODE_MAX
        else -> MODE_FAST_BOOT
    }

    fun modeLabel(value: String?): String = when (normalizeMode(value)) {
        MODE_SAFE -> "Safe"
        MODE_MAX -> "Max"
        else -> "Fast Boot"
    }

    fun nextMode(value: String?): String = when (normalizeMode(value)) {
        MODE_SAFE -> MODE_FAST_BOOT
        MODE_FAST_BOOT -> MODE_MAX
        else -> MODE_SAFE
    }

    fun normalizeRenderer(value: String?): String =
        if (value.equals(RENDER_VULKAN, ignoreCase = true)) RENDER_VULKAN else RENDER_OPENGL

    fun rendererLabel(value: String?): String =
        if (normalizeRenderer(value) == RENDER_VULKAN) "Vulkan (DOOMx64vk.exe)" else "OpenGL (DOOMx64.exe)"

    fun nextRenderer(value: String?): String =
        if (normalizeRenderer(value) == RENDER_VULKAN) RENDER_OPENGL else RENDER_VULKAN

    /**
     * Applies only launch-time environment overrides. These are intentionally layered after
     * the normal FEX preset so DOOM's profile wins without rewriting the user's container.
     */
    fun apply(
        envVars: EnvVars,
        modeValue: String?,
        diagnostics: Boolean,
        debugRun: Boolean,
    ) {
        val mode = normalizeMode(modeValue)
        if (mode == MODE_SAFE) return

        // Persistent FEX block cache: the main repeat-launch accelerator.
        envVars.put("FEX_DISKCACHE", "1")
        envVars.put("FEX_DISKCACHEFILEMAPPING", "1")
        envVars.put("FEX_DISKCACHEVALIDATION", "0")
        envVars.put("FEX_DISKCACHEANONCACHING", "1")
        envVars.put("FEX_DISKCACHEPRUNESTALEENTRIES", "1")
        envVars.put("FEX_DISKCACHEMAXFILESIZE", "2147483648")

        // Cache policy is mode-specific below. Fast Boot keeps FEX's memory-saving
        // lookup-cache behaviour because Quest RAM pressure is a real constraint.

        // Remove avoidable FEX overhead.
        envVars.put("FEX_DISABLETELEMETRY", "1")
        envVars.put("FEX_SILENTLOG", "1")
        envVars.put("FEX_X87REDUCEDPRECISION", "1")
        envVars.put("FEX_VECTORTSOENABLED", "0")
        envVars.put("FEX_MEMCPYSETTSOENABLED", "0")
        envVars.put("FEX_TSOENABLED", "1")

        // Persistent driver shader cache for the OpenGL/Turnip path.
        envVars.put("MESA_SHADER_CACHE_DISABLE", "false")
        envVars.put("MESA_SHADER_CACHE_MAX_SIZE", "2G")

        if (!diagnostics && !debugRun) {
            envVars.put("DXVK_LOG_LEVEL", "none")
            envVars.put("VKD3D_DEBUG", "none")
        }

        when (mode) {
            MODE_FAST_BOOT -> {
                // FEX documents multiblock as capable of long JIT compile times.
                // Turning it off favours getting through DOOM's startup/profile path faster.
                // Keep the memory-saving L2/L1 defaults because the Quest can approach
                // low-memory conditions during DOOM startup.
                envVars.put("FEX_DISABLEL2CACHE", "1")
                envVars.put("FEX_DYNAMICL1CACHE", "1")
                envVars.put("FEX_MULTIBLOCK", "0")
                envVars.put("FEX_MAXINST", "3000")
                envVars.put("FEX_HALFBARRIERTSOENABLED", "1")
                envVars.put("FEX_SMCCHECKS", "mtrack")
            }

            MODE_MAX -> {
                // Higher steady-state throughput after the disk cache has warmed.
                // This intentionally spends more RAM and is therefore not the default.
                envVars.put("FEX_DISABLEL2CACHE", "0")
                envVars.put("FEX_DYNAMICL1CACHE", "0")
                envVars.put("FEX_MULTIBLOCK", "1")
                envVars.put("FEX_MAXINST", "5000")
                envVars.put("FEX_HALFBARRIERTSOENABLED", "0")
                envVars.put("FEX_SMCCHECKS", "none")
                envVars.put("FEX_SMALLTSCSCALE", "1")
                envVars.put("FEX_VOLATILEMETADATA", "1")
            }
        }
    }

    fun executableForRenderer(gameRootDir: java.io.File, rendererValue: String?): String {
        val renderer = normalizeRenderer(rendererValue)
        val preferred = if (renderer == RENDER_VULKAN) "DOOMx64vk.exe" else "DOOMx64.exe"
        val fallback = if (renderer == RENDER_VULKAN) "DOOMx64.exe" else "DOOMx64vk.exe"
        return when {
            java.io.File(gameRootDir, preferred).isFile -> preferred
            java.io.File(gameRootDir, fallback).isFile -> fallback
            else -> preferred
        }
    }

    fun argumentsForRenderer(arguments: String, rendererValue: String?): String {
        val api = if (normalizeRenderer(rendererValue) == RENDER_VULKAN) "1" else "0"
        val regex = Regex("""(?i)(^|\s)\+r_renderAPI\s+[01](?=\s|$)""")
        val cleaned = arguments.trim()
        return if (regex.containsMatchIn(cleaned)) {
            regex.replace(cleaned) { match ->
                val prefix = match.groupValues[1]
                "${prefix}+r_renderAPI $api"
            }.trim()
        } else {
            listOf(cleaned, "+r_renderAPI $api").filter { it.isNotBlank() }.joinToString(" ")
        }
    }
}
