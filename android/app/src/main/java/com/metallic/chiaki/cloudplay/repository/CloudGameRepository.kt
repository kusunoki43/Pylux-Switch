// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.repository

import android.content.Context
import android.util.Log
import com.metallic.chiaki.cloudplay.CloudLocaleBootstrap
import com.metallic.chiaki.cloudplay.api.Ps5CloudCatalogResult
import com.metallic.chiaki.cloudplay.api.PsCloudOwnership
import com.metallic.chiaki.cloudplay.api.PsnCatalogService
import com.metallic.chiaki.cloudplay.api.PsCloudCatalogService
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.cloudplay.model.PsnResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Repository for cloud game catalog data
 * Handles caching and data fetching
 */
class CloudGameRepository(
	private val context: Context,
	private val preferences: com.metallic.chiaki.common.Preferences
)
{
	companion object
	{
		private const val TAG = "CloudGameRepository"
		private const val CACHE_DIR = "cloud_catalog_cache"

		fun invalidateCatalogCache(context: Context)
		{
			try
			{
				val cacheDir = File(context.cacheDir, CACHE_DIR)
				cacheDir.listFiles()?.forEach { file ->
					if (file.isFile)
						file.delete()
				}
				Log.i(TAG, "Catalog cache invalidated (locale change)")
			}
			catch (e: Exception)
			{
				Log.w(TAG, "Error invalidating catalog cache", e)
			}
		}
		private const val PSNOW_CACHE_FILE = "psnow_catalog.json"
		private const val PSCLOUD_CACHE_FILE = "pscloud_catalog.json"
		private const val PS5_CATALOG_V3_CACHE_FILE = "ps5_cloud_catalog_v3.json"
		private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours
	}
	
	private val psnowCatalogService = PsnCatalogService(preferences)
	private val pscloudCatalogService = PsCloudCatalogService()
	private val cacheDir: File by lazy {
		File(context.cacheDir, CACHE_DIR).apply {
			if (!exists()) mkdirs()
		}
	}
	
	/**
	 * Fetch PSNow catalog with caching
	 */
	suspend fun fetchPsnowCatalog(npssoToken: String, forceRefresh: Boolean = false): PsnResult<List<CloudGame>>
	{
		return withContext(Dispatchers.IO)
		{
			// Check cache first if not forcing refresh
			if (!forceRefresh)
			{
				val cachedGames = loadCachedGames(PSNOW_CACHE_FILE)
				if (cachedGames != null)
				{
					Log.i(TAG, "Returning ${cachedGames.size} PSNow games from cache")
					return@withContext PsnResult.Success(cachedGames)
				}
			}
			
			// Fetch from network
			Log.i(TAG, "Fetching fresh PSNow catalog from network")
			val result = psnowCatalogService.fetchPsnowCatalog(npssoToken)
			
			// Cache if successful
			if (result is PsnResult.Success)
			{
				cacheGames(result.data, PSNOW_CACHE_FILE)
			}
			
			result
		}
	}
	
	/**
	 * Fetch PS5 Cloud catalog with caching
	 */
	suspend fun fetchPs5CloudCatalog(npssoToken: String, forceRefresh: Boolean = false): PsnResult<List<CloudGame>>
	{
		return withContext(Dispatchers.IO)
		{
			CloudLocaleBootstrap.ensureConfigured(preferences, npssoToken)

			// Check cache first if not forcing refresh
			if (!forceRefresh)
			{
				val cachedGames = loadCachedGames(PSCLOUD_CACHE_FILE)
				if (cachedGames != null)
				{
					Log.i(TAG, "Returning ${cachedGames.size} PS5 games from cache (ownership already cached)")
					// Return cached games with their cached ownership status
					return@withContext PsnResult.Success(cachedGames)
				}
			}
			
		// Fetch from network
		Log.i(TAG, "Fetching fresh PS5 Cloud catalog from network")
		try
		{
			val stored = preferences.getCloudLanguage()
			val locale = com.metallic.chiaki.cloudplay.CloudLocale.toImagicLocale(stored)
			Log.i(TAG, "PS5 catalog locale: stored=$stored imagic=$locale forceRefresh=$forceRefresh")

			val catalog = (if (!forceRefresh) loadCachedPs5CatalogV3() else null)
				?: run {
					val fetched = pscloudCatalogService.fetchPs5CloudCatalog(locale)
					cachePs5CatalogV3(fetched)
					fetched
				}

			val gamesWithOwnership = crossReferenceOwnership(catalog, npssoToken)

			cacheGames(gamesWithOwnership, PSCLOUD_CACHE_FILE)
			PsnResult.Success(gamesWithOwnership)
		}
			catch (e: Exception)
			{
				Log.e(TAG, "Failed to fetch PS5 catalog", e)
				PsnResult.Error("Failed to fetch PS5 catalog: ${e.message}", e)
			}
		}
	}
	
	/**
	 * Cross-reference public catalog with owned games to mark ownership status
	 */
	private suspend fun crossReferenceOwnership(catalog: Ps5CloudCatalogResult, npssoToken: String): List<CloudGame>
	{
		if (npssoToken.isEmpty())
			return catalog.browseGames.map { it.copy(isOwned = false) }

		return try
		{
			val ownedCrossRef = pscloudCatalogService.getOwnedPs5CloudGames(
				npssoToken,
				catalog.browseGames,
				catalog.plusLibrarySupplement
			)
			PsCloudOwnership.mergeOwnedIntoBrowseCatalog(catalog.browseGames, ownedCrossRef)
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Failed to cross-reference ownership, returning games as not owned", e)
			catalog.browseGames.map { it.copy(isOwned = false) }
		}
	}
	
	/**
	 * Fetch owned PS5 games (user's library)
	 */
	suspend fun fetchOwnedPs5Games(npssoToken: String, forceRefresh: Boolean = false): PsnResult<List<CloudGame>>
	{
		return withContext(Dispatchers.IO)
		{
			CloudLocaleBootstrap.ensureConfigured(preferences, npssoToken)

			// Owned games cache is separate from public catalog
			val OWNED_CACHE_FILE = "pscloud_owned.json"
			
			// Check cache first if not forcing refresh
			if (!forceRefresh)
			{
				val cachedGames = loadCachedGames(OWNED_CACHE_FILE)
				if (cachedGames != null)
				{
					Log.i(TAG, "Returning ${cachedGames.size} owned PS5 games from cache")
					return@withContext PsnResult.Success(cachedGames)
				}
			}
			
		Log.i(TAG, "Fetching owned PS5 games from network")
		try
		{
			val stored = preferences.getCloudLanguage()
			val locale = com.metallic.chiaki.cloudplay.CloudLocale.toImagicLocale(stored)
			Log.i(TAG, "PS5 catalog locale: stored=$stored imagic=$locale forceRefresh=$forceRefresh")

			val catalog = (if (!forceRefresh) loadCachedPs5CatalogV3() else null)
				?: run {
					val fetched = pscloudCatalogService.fetchPs5CloudCatalog(locale)
					cachePs5CatalogV3(fetched)
					fetched
				}

			val games = pscloudCatalogService.getOwnedPs5CloudGames(
				npssoToken,
				catalog.browseGames,
				catalog.plusLibrarySupplement
			)
			cacheGames(games, OWNED_CACHE_FILE)
			PsnResult.Success(games)
		}
			catch (e: Exception)
			{
				Log.e(TAG, "Failed to fetch owned PS5 games", e)
				PsnResult.Error("Failed to fetch owned PS5 games: ${e.message}", e)
			}
		}
	}
	
	/**
	 * Load games from cache if valid
	 */
	private fun loadCachedGames(cacheFileName: String): List<CloudGame>?
	{
		try
		{
			val cacheFile = File(cacheDir, cacheFileName)
			
			if (!cacheFile.exists())
			{
				Log.d(TAG, "No cache file found: $cacheFileName at ${cacheFile.absolutePath}")
				Log.d(TAG, "Cache directory exists: ${cacheDir.exists()}, contents: ${cacheDir.listFiles()?.map { it.name }}")
				return null
			}
			
			// Check if cache is still valid
			val cacheAge = System.currentTimeMillis() - cacheFile.lastModified()
			if (cacheAge > CACHE_DURATION_MS)
			{
				Log.d(TAG, "Cache expired (age: ${cacheAge / 1000}s, max: ${CACHE_DURATION_MS / 1000}s)")
				cacheFile.delete()
				return null
			}
			
			// Read and parse cache
			val json = cacheFile.readText()
			val jsonArray = JSONArray(json)
			val games = mutableListOf<CloudGame>()
			
			for (i in 0 until jsonArray.length())
			{
				val obj = jsonArray.getJSONObject(i)
				// Handle landscapeImageUrl (may be missing in old cache)
				val landscapeImageUrl = obj.optString("landscapeImageUrl", obj.getString("imageUrl"))
				
				games.add(CloudGame(
					productId = obj.getString("productId"),
					name = obj.getString("name"),
					imageUrl = obj.getString("imageUrl"),
					landscapeImageUrl = landscapeImageUrl,
					thumbnailUrl = obj.optString("thumbnailUrl", obj.getString("imageUrl")),
					platform = obj.optString("platform", "ps4"),
					serviceType = obj.optString("serviceType", "psnow"),
					conceptUrl = obj.optString("conceptUrl", ""),
					conceptId = obj.optString("conceptId", ""),
					isOwned = obj.optBoolean("isOwned", false),
					entitlementId = obj.optString("entitlementId", ""),
					storeProductId = obj.optString("storeProductId", "")
				))
			}
			
			Log.i(TAG, "Loaded ${games.size} games from cache: $cacheFileName")
			return games
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Error loading cache: $cacheFileName", e)
			return null
		}
	}
	
	/**
	 * Save games to cache
	 */
	private fun cacheGames(games: List<CloudGame>, cacheFileName: String)
	{
		try
		{
			val jsonArray = JSONArray()
			
			for (game in games)
			{
				val obj = JSONObject()
				obj.put("productId", game.productId)
				obj.put("name", game.name)
				obj.put("imageUrl", game.imageUrl)
				obj.put("landscapeImageUrl", game.landscapeImageUrl)
				obj.put("thumbnailUrl", game.thumbnailUrl)
				obj.put("platform", game.platform)
				obj.put("serviceType", game.serviceType)
				obj.put("conceptUrl", game.conceptUrl)
				obj.put("conceptId", game.conceptId)
				obj.put("isOwned", game.isOwned)
				obj.put("entitlementId", game.entitlementId)
				obj.put("storeProductId", game.storeProductId)
				jsonArray.put(obj)
			}
			
			val cacheFile = File(cacheDir, cacheFileName)
			cacheFile.writeText(jsonArray.toString())
			
			Log.i(TAG, "Cached ${games.size} games to: ${cacheFile.absolutePath}")
			Log.d(TAG, "Cache file size: ${cacheFile.length()} bytes, lastModified: ${cacheFile.lastModified()}")
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Error caching games to $cacheFileName", e)
		}
	}
	
	private fun loadCachedPs5CatalogV3(): Ps5CloudCatalogResult?
	{
		try
		{
			val cacheFile = File(cacheDir, PS5_CATALOG_V3_CACHE_FILE)
			if (!cacheFile.exists())
				return null

			val cacheAge = System.currentTimeMillis() - cacheFile.lastModified()
			if (cacheAge > CACHE_DURATION_MS)
			{
				cacheFile.delete()
				return null
			}

			val root = JSONObject(cacheFile.readText())
			val browse = parseGameArray(root.optJSONArray("games") ?: JSONArray())
			val supplement = parseGameArray(root.optJSONArray("plusLibrarySupplement") ?: JSONArray())
			Log.i(TAG, "Loaded PS5 catalog v3 from cache: ${browse.size} browse, ${supplement.size} supplement")
			return Ps5CloudCatalogResult(browse, supplement)
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Error loading PS5 catalog v3 cache", e)
			return null
		}
	}

	private fun cachePs5CatalogV3(catalog: Ps5CloudCatalogResult)
	{
		try
		{
			val root = JSONObject()
			root.put("games", gamesToJsonArray(catalog.browseGames))
			root.put("plusLibrarySupplement", gamesToJsonArray(catalog.plusLibrarySupplement))
			root.put("total", catalog.browseGames.size)

			val cacheFile = File(cacheDir, PS5_CATALOG_V3_CACHE_FILE)
			cacheFile.writeText(root.toString())
			Log.i(TAG, "Cached PS5 catalog v3: ${catalog.browseGames.size} browse, ${catalog.plusLibrarySupplement.size} supplement")
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Error caching PS5 catalog v3", e)
		}
	}

	private fun parseGameArray(jsonArray: JSONArray): List<CloudGame>
	{
		val games = mutableListOf<CloudGame>()
		for (i in 0 until jsonArray.length())
		{
			val obj = jsonArray.getJSONObject(i)
			val landscapeImageUrl = obj.optString("landscapeImageUrl", obj.getString("imageUrl"))
			games.add(
				CloudGame(
					productId = obj.getString("productId"),
					name = obj.getString("name"),
					imageUrl = obj.getString("imageUrl"),
					landscapeImageUrl = landscapeImageUrl,
					platform = obj.optString("platform", "ps5"),
					serviceType = obj.optString("serviceType", "pscloud"),
					conceptUrl = obj.optString("conceptUrl", ""),
					conceptId = obj.optString("conceptId", ""),
					isOwned = obj.optBoolean("isOwned", false),
					entitlementId = obj.optString("entitlementId", ""),
					storeProductId = obj.optString("storeProductId", "")
				)
			)
		}
		return games
	}

	private fun gamesToJsonArray(games: List<CloudGame>): JSONArray
	{
		val jsonArray = JSONArray()
		for (game in games)
		{
			val obj = JSONObject()
			obj.put("productId", game.productId)
			obj.put("name", game.name)
			obj.put("imageUrl", game.imageUrl)
			obj.put("landscapeImageUrl", game.landscapeImageUrl)
			obj.put("platform", game.platform)
			obj.put("serviceType", game.serviceType)
			obj.put("conceptUrl", game.conceptUrl)
			obj.put("conceptId", game.conceptId)
			obj.put("isOwned", game.isOwned)
			obj.put("entitlementId", game.entitlementId)
			obj.put("storeProductId", game.storeProductId)
			jsonArray.put(obj)
		}
		return jsonArray
	}

	/**
	 * Clear all cached data
	 */
	fun clearCache()
	{
		try
		{
			cacheDir.listFiles()?.forEach { it.delete() }
			Log.i(TAG, "Cache cleared")
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Error clearing cache", e)
		}
	}
}

