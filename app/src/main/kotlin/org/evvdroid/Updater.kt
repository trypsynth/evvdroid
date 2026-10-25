package org.evvdroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class UpdateChannel(val id: Int) {
	STABLE(0),
	DEVELOPMENT(1);

	companion object {
		fun fromId(id: Int): UpdateChannel = if (id == DEVELOPMENT.id) DEVELOPMENT else STABLE
	}
}

data class ReleaseAsset(
	val name: String,
	val downloadUrl: String,
	val size: Long
)

data class ReleaseInfo(
	val tagName: String,
	val name: String,
	val body: String,
	val isPrerelease: Boolean,
	val publishedAt: String,
	val asset: ReleaseAsset
)

data class ReleaseNoteItem(
	val text: String,
	val isHeading: Boolean,
	val isBullet: Boolean
)

sealed class UpdateStatus {
	object Idle : UpdateStatus()
	object Checking : UpdateStatus()
	data class UpdateAvailable(val release: ReleaseInfo, val isNewer: Boolean) : UpdateStatus()
	data class UpToDate(val release: ReleaseInfo) : UpdateStatus()
	data class Downloading(
		val release: ReleaseInfo,
		val progressPercent: Int,
		val bytesDownloaded: Long,
		val totalBytes: Long
	) : UpdateStatus()
	data class ReadyToInstall(val release: ReleaseInfo, val apkFile: File) : UpdateStatus()
	data class Error(val message: String) : UpdateStatus()
}

object Updater {

	private const val RELEASES_URL = "https://api.github.com/repos/trypsynth/evvdroid/releases"
	private const val USER_AGENT = "evvdroid-updater"

	suspend fun fetchRelease(channel: UpdateChannel): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
		runCatching {
			val url = URL(RELEASES_URL)
			val conn = (url.openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				connectTimeout = 15_000
				readTimeout = 15_000
				setRequestProperty("User-Agent", USER_AGENT)
				setRequestProperty("Accept", "application/vnd.github.v3+json")
			}

			val responseCode = conn.responseCode
			if (responseCode !in 200..299) {
				val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
				throw Exception("HTTP $responseCode: ${conn.responseMessage} $err".trim())
			}

			val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
			val releasesArray = JSONArray(jsonText)

			var targetRelease: JSONObject? = null
			for (i in 0 until releasesArray.length()) {
				val rel = releasesArray.getJSONObject(i)
				val isPrerelease = rel.optBoolean("prerelease", false)
				val isDraft = rel.optBoolean("draft", false)
				val tag = rel.optString("tag_name", "")

				if (isDraft) continue

				when (channel) {
					UpdateChannel.STABLE -> {
						if (!isPrerelease && tag != "dev") {
							targetRelease = rel
							break
						}
					}
					UpdateChannel.DEVELOPMENT -> {
						if (tag == "dev" || isPrerelease) {
							targetRelease = rel
							break
						}
					}
				}
			}

			val releaseObj = targetRelease
				?: throw Exception("No releases found for channel ${channel.name.lowercase()}")

			val tagName = releaseObj.optString("tag_name", "")
			val name = releaseObj.optString("name", tagName)
			val body = releaseObj.optString("body", "")
			val isPrerelease = releaseObj.optBoolean("prerelease", false)
			val publishedAt = releaseObj.optString("published_at", "")
			val assetsJson = releaseObj.optJSONArray("assets") ?: JSONArray()

			val asset = findCompatibleAsset(assetsJson)
				?: throw Exception("No compatible APK asset found in release $name")

			ReleaseInfo(
				tagName = tagName,
				name = name,
				body = body,
				isPrerelease = isPrerelease,
				publishedAt = publishedAt,
				asset = asset
			)
		}
	}

	fun findCompatibleAsset(assetsJson: JSONArray): ReleaseAsset? {
		val apkAssets = mutableListOf<ReleaseAsset>()
		for (i in 0 until assetsJson.length()) {
			val obj = assetsJson.getJSONObject(i)
			val assetName = obj.optString("name", "")
			val downloadUrl = obj.optString("browser_download_url", "")
			val size = obj.optLong("size", 0L)
			if (assetName.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
				apkAssets.add(ReleaseAsset(assetName, downloadUrl, size))
			}
		}
		return selectAsset(apkAssets)
	}

	fun selectAsset(
		assets: List<ReleaseAsset>,
		supportedAbis: Array<String> = Build.SUPPORTED_ABIS ?: emptyArray()
	): ReleaseAsset? {
		val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank() }
		if (apkAssets.isEmpty()) return null

		for (abi in supportedAbis) {
			// Whole token only, since "x86" is also the start of "x86_64".
			val token = Regex("(?<![A-Za-z0-9_])" + Regex.escape(abi) + "(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
			val match = apkAssets.firstOrNull { token.containsMatchIn(it.name) }
			if (match != null) return match
		}

		val universal = apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
		if (universal != null) return universal

		return apkAssets.firstOrNull()
	}

	fun isNewerVersion(currentVersion: String, remoteTag: String): Boolean {
		val curClean = currentVersion.removePrefix("v").removePrefix("V").trim()
		val remClean = remoteTag.removePrefix("v").removePrefix("V").trim()
		val curParts = curClean.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
		val remParts = remClean.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
		val maxLen = maxOf(curParts.size, remParts.size)
		for (i in 0 until maxLen) {
			val cur = curParts.getOrElse(i) { 0 }
			val rem = remParts.getOrElse(i) { 0 }
			if (rem > cur) return true
			if (rem < cur) return false
		}
		return false
	}

	/** The number on the "build: <n>" line the development workflow puts first in the release notes. */
	fun buildNumberOf(release: ReleaseInfo): Int? =
		Regex("""^\s*build:\s*(\d+)""", RegexOption.MULTILINE).find(release.body)?.groupValues?.get(1)?.toIntOrNull()

	fun isUpdateAvailable(
		release: ReleaseInfo,
		channel: UpdateChannel,
		currentVersionName: String,
		currentBuildNumber: Int = BuildConfig.BUILD_NUMBER
	): Boolean {
		return when (channel) {
			UpdateChannel.STABLE -> {
				isNewerVersion(currentVersionName, release.tagName)
			}
			UpdateChannel.DEVELOPMENT -> {
				// The dev release keeps one tag and its title is rewritten, so the build number in its
				// notes is the only thing that says which build it holds. Without one, offer it.
				val remote = buildNumberOf(release)
				remote == null || remote > currentBuildNumber
			}
		}
	}

	suspend fun downloadApk(
		release: ReleaseInfo,
		context: Context,
		onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit
	): Result<File> = withContext(Dispatchers.IO) {
		runCatching {
			val updatesDir = File(context.cacheDir, "updates")
			if (!updatesDir.exists()) updatesDir.mkdirs()

			// Clean up previous APK downloads
			updatesDir.listFiles()?.filter { it.extension.equals("apk", ignoreCase = true) }?.forEach {
				it.delete()
			}

			val targetFile = File(updatesDir, release.asset.name)

			var currentUrl = release.asset.downloadUrl
			var redirectCount = 0
			var connection: HttpURLConnection? = null

			while (redirectCount < 5) {
				val url = URL(currentUrl)
				connection = (url.openConnection() as HttpURLConnection).apply {
					instanceFollowRedirects = true
					connectTimeout = 20_000
					readTimeout = 20_000
					setRequestProperty("User-Agent", USER_AGENT)
				}
				val responseCode = connection.responseCode
				if (responseCode in 300..399) {
					val location = connection.getHeaderField("Location")
						?: throw Exception("Redirect without Location header")
					currentUrl = location
					connection.disconnect()
					redirectCount++
				} else if (responseCode !in 200..299) {
					throw Exception("HTTP $responseCode: ${connection.responseMessage}")
				} else {
					break
				}
			}

			val conn = connection ?: throw Exception("Failed to open download connection")
			val headerLength = conn.contentLengthLong
			val effectiveTotal = if (headerLength > 0) headerLength else release.asset.size

			conn.inputStream.use { input ->
				FileOutputStream(targetFile).use { output ->
					val buffer = ByteArray(32768)
					var bytesRead: Int
					var downloaded = 0L
					var lastPercent = -1
					var lastProgressTime = 0L

					while (input.read(buffer).also { bytesRead = it } != -1) {
						output.write(buffer, 0, bytesRead)
						downloaded += bytesRead
						val percent = if (effectiveTotal > 0) {
							((downloaded * 100) / effectiveTotal).toInt().coerceIn(0, 100)
						} else {
							-1
						}
						val now = System.currentTimeMillis()
						if (percent != lastPercent || now - lastProgressTime >= 150) {
							lastPercent = percent
							lastProgressTime = now
							withContext(Dispatchers.Main) {
								onProgress(percent, downloaded, effectiveTotal)
							}
						}
					}
					output.flush()
					// A connection that drops cleanly ends the read early without an error.
					if (headerLength > 0 && downloaded != headerLength) {
						throw java.io.IOException("The download ended early ($downloaded of $headerLength bytes)")
					}
					withContext(Dispatchers.Main) {
						onProgress(100, downloaded, effectiveTotal)
					}
				}
			}

			targetFile
		}
	}

	fun parseReleaseNotes(body: String): List<ReleaseNoteItem> {
		val items = mutableListOf<ReleaseNoteItem>()
		val lines = body.lines()
		for (rawLine in lines) {
			val line = rawLine.trim()
			if (line.isBlank()) continue

			if (line.startsWith("#")) {
				val headingText = line.trimStart('#').trim()
				if (headingText.isNotBlank()) {
					items.add(ReleaseNoteItem(headingText, isHeading = true, isBullet = false))
				}
			} else if (line.startsWith("* ") || line.startsWith("- ") || line.startsWith("• ")) {
				val bulletText = line.substring(2).trim()
				val cleanedText = bulletText.replaceFirst(Regex("""^[0-9a-fA-F]{7,8}\s+"""), "")
				if (cleanedText.isNotBlank()) {
					items.add(ReleaseNoteItem(cleanedText, isHeading = false, isBullet = true))
				}
			} else if (line.matches(Regex("""^\d+\.\s+.*"""))) {
				val text = line.replaceFirst(Regex("""^\d+\.\s+"""), "").trim()
				if (text.isNotBlank()) {
					items.add(ReleaseNoteItem(text, isHeading = false, isBullet = true))
				}
			} else {
				items.add(ReleaseNoteItem(line, isHeading = false, isBullet = false))
			}
		}
		return items
	}

	fun canInstallPackages(context: Context): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.packageManager.canRequestPackageInstalls()
		} else {
			true
		}
	}

	fun requestInstallPermission(context: Context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val intent = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
				data = Uri.parse("package:${context.packageName}")
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}
			context.startActivity(intent)
		}
	}

	fun installApk(context: Context, apkFile: File) {
		val uri = FileProvider.getUriForFile(
			context,
			"${context.packageName}.fileprovider",
			apkFile
		)
		val intent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(uri, "application/vnd.android.package-archive")
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		context.startActivity(intent)
	}
}
