package dev.belikhun.luna.smp.crops

import org.bukkit.Bukkit
import org.bukkit.World
import java.lang.reflect.Method

/**
 * What season a world is in, asked of RealisticSeasons.
 *
 * Reached by reflection rather than compiled against, and deliberately: the
 * plugin is on survival and on nothing else, no repository carries the build
 * that runs there, and a backend without it must still start. What a backend
 * without it gets is [Season.NONE], which every caller reads as "every crop is
 * in season" - crops then grow all year and nothing ever withers, which is the
 * only safe way to be wrong about a season.
 *
 * The lookup is done once and cached, including the failure: a miss costs one
 * class lookup at boot rather than one per crop per tick.
 */
object Seasons {

	/** A season, plus the answer for a world nobody is tracking. */
	enum class Season {
		SPRING, SUMMER, FALL, WINTER,

		/** No season plugin, or seasons switched off for this world. */
		NONE,
	}

	private const val API_CLASS = "me.casperge.realisticseasons.api.SeasonsAPI"

	/**
	 * `SeasonsAPI.getInstance().getSeason(world)`, resolved once.
	 *
	 * Held as the pair of methods rather than as an instance, because the API
	 * object is created when the plugin enables and asking for it before then
	 * caches a null forever.
	 */
	private val bridge: Pair<Method, Method>? by lazy { resolve() }

	private fun resolve(): Pair<Method, Method>? {
		if (Bukkit.getPluginManager().getPlugin("RealisticSeasons") == null) {
			return null
		}

		return try {
			val api = Class.forName(API_CLASS)

			api.getMethod("getInstance") to api.getMethod("getSeason", World::class.java)
		} catch (_: ReflectiveOperationException) {
			null
		}
	}

	/**
	 * The season [world] is in.
	 *
	 * The plugin answers with its own enum, which carries two values that are
	 * not seasons at all (`DISABLED` for a world it is not tracking, `RESTORE`
	 * for one it is handing back to vanilla); both come out of here as
	 * [Season.NONE], since neither is a season a crop could be out of.
	 */
	fun of(world: World): Season {
		val (instance, getSeason) = bridge ?: return Season.NONE

		return try {
			val api = instance.invoke(null) ?: return Season.NONE
			val season = getSeason.invoke(api, world) as? Enum<*> ?: return Season.NONE

			when (season.name) {
				"SPRING" -> Season.SPRING
				"SUMMER" -> Season.SUMMER
				"FALL" -> Season.FALL
				"WINTER" -> Season.WINTER
				else -> Season.NONE
			}
		} catch (_: ReflectiveOperationException) {
			Season.NONE
		}
	}

	/**
	 * Whether [spec] may grow in [world] right now.
	 *
	 * A world with no season is always in season, which is what keeps a backend
	 * without the plugin (and a world it does not track) growing everything.
	 */
	fun inSeason(spec: CropCatalog.Spec, world: World): Boolean {
		val season = of(world)

		if (season == Season.NONE) {
			return true
		}

		return spec.seasons.any { it.name == season.name }
	}

	/** The season's name in the language the console speaks to players in. */
	fun label(season: CropCatalog.Season): String = when (season) {
		CropCatalog.Season.SPRING -> "Xuân"
		CropCatalog.Season.SUMMER -> "Hạ"
		CropCatalog.Season.FALL -> "Thu"
		CropCatalog.Season.WINTER -> "Đông"
	}
}
