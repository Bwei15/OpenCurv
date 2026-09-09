package com.motoroute.data.brouter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A routing profile the rider can pick.
 */
data class RoutingProfile(
    val id: String,
    val displayName: String,
    val description: String,
    val file: File,
    val isBuiltIn: Boolean,
)

/**
 * Owns the .brf profiles and the lookups.dat table they need.
 *
 * The bundled profiles are copied out of the APK assets into app storage on
 * first run, because BRouter reads profiles as plain files from disk. Copies
 * are refreshed whenever the app version changes so an update actually ships
 * its new profile, but a profile the rider edited by hand is left alone.
 *
 * lookups.dat is the exception: it is rewritten on every start. It is not a
 * rider-owned file, it is the tag table the downloaded .rd5 tiles were built
 * against, and a stale copy does not degrade the route - it stops routing dead
 * with "lookup version mismatch". Thirty kilobytes on every start is a cheap
 * price for that never happening.
 */
class ProfileManager(private val context: Context) {

    val profileDir: File
        get() = File(context.filesDir, "profiles").apply { mkdirs() }

    val lookupsFile: File get() = File(profileDir, LOOKUPS)

    /**
     * Copies bundled assets into [profileDir]. Safe to call on every start.
     *
     * Bundled profiles are overwritten when the app version changes, so an
     * update actually ships its new routing rules. Rider-owned profiles live
     * under their own file names and are never touched. lookups.dat is always
     * rewritten, so the tag table can never lag the .rd5 tiles behind it.
     */
    suspend fun ensureInstalled(appVersion: String) = withContext(Dispatchers.IO) {
        val dir = profileDir
        val stamp = File(dir, ".version")
        val installed = stamp.takeIf { it.isFile }?.readText()?.trim()
        val refresh = installed != appVersion

        for (asset in BUILT_IN.keys) {
            val target = File(dir, asset)
            if (target.isFile && !refresh) continue
            copyAsset(asset, target)
        }
        copyAsset(LOOKUPS, File(dir, LOOKUPS))
        stamp.writeText(appVersion)
    }

    private fun copyAsset(asset: String, target: File) {
        context.assets.open("$ASSET_DIR/$asset").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** Imports a rider-supplied .brf. Returns the stored file. */
    suspend fun importProfile(name: String, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val safeName = name.substringAfterLast('/').let {
            if (it.endsWith(".brf")) it else "$it.brf"
        }
        require(safeName !in BUILT_IN.keys) { "cannot overwrite a bundled profile" }
        File(profileDir, safeName).apply { writeBytes(bytes) }
    }

    /** Every usable profile, bundled ones first. */
    fun profiles(): List<RoutingProfile> {
        val files = profileDir.listFiles { f -> f.extension == "brf" }.orEmpty().sortedBy { it.name }
        return files.map { file ->
            val builtIn = BUILT_IN[file.name]
            RoutingProfile(
                id = file.nameWithoutExtension,
                displayName = builtIn?.first ?: file.nameWithoutExtension,
                description = builtIn?.second ?: "imported profile",
                file = file,
                isBuiltIn = builtIn != null,
            )
        }.sortedByDescending { it.isBuiltIn }
    }

    fun profile(id: String): RoutingProfile? = profiles().firstOrNull { it.id == id }

    fun defaultProfile(): RoutingProfile? =
        profile(DEFAULT_PROFILE_ID) ?: profiles().firstOrNull()

    fun delete(profile: RoutingProfile): Boolean =
        !profile.isBuiltIn && profile.file.delete()

    companion object {
        const val ASSET_DIR = "profiles"
        const val LOOKUPS = "lookups.dat"
        const val DEFAULT_PROFILE_ID = "motorcycle_curvy"

        /** file name -> (display name, description) */
        val BUILT_IN = linkedMapOf(
            "motorcycle_curvy.brf" to
                ("Curvy" to "Hunts bends, dodges main roads, paved only"),
            "motorcycle_fast.brf" to
                ("Fast" to "Direct route, motorways allowed"),
            "motorcycle_enduro.brf" to
                ("Enduro" to "Allows gravel, tracks and unpaved surfaces"),
        )
    }
}
