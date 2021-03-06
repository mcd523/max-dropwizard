package client

import MaxDropwizardConfiguration
import client.model.league.bracket.BracketType
import client.model.PlayoffMatchup
import client.model.draft.SleeperDraft
import client.model.draft.SleeperPick
import client.model.league.*
import client.model.league.bracket.Bracket
import client.model.player.PlayerTrend
import client.model.player.SleeperPlayer
import client.model.player.TrendingPlayer
import client.model.user.SleeperUser
import com.fasterxml.jackson.databind.ObjectMapper
import net.jodah.failsafe.Failsafe
import net.jodah.failsafe.RetryPolicy
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.function.Predicate
import javax.inject.Inject
import javax.ws.rs.HttpMethod
import javax.ws.rs.ProcessingException
import javax.ws.rs.WebApplicationException
import javax.ws.rs.client.Client
import javax.ws.rs.client.Entity
import javax.ws.rs.client.Invocation
import javax.ws.rs.client.ResponseProcessingException
import javax.ws.rs.core.GenericType
import javax.ws.rs.core.MediaType

class SleeperClient @Inject constructor(config: MaxDropwizardConfiguration, client: Client, private val mapper: ObjectMapper) {
    companion object {
        private val logger = LoggerFactory.getLogger(SleeperClient::class.java)
    }

    private val retryPolicy: RetryPolicy<Any> = RetryPolicy<Any>()
        .withDelay(Duration.ofMillis(100))
        .handleIf(Predicate {
            when (it) {
                is ProcessingException -> it !is ResponseProcessingException
                is WebApplicationException -> {
                    logger.error(it.localizedMessage, it)
                    it.response.status > 500
                }
                else -> {
                    logger.error("Something happened", it)
                    false
                }
            }
        })
        .onRetry {
            logger.info("Retry attempt: ${it.attemptCount}. Retrying execution for ${it.lastFailure}")
        }

    private val webTarget = client
        .target(config.baseUrl)
//        .register(LoggingFeature(logger, Level.INFO, LoggingFeature.Verbosity.HEADERS_ONLY, 8192))

    // USERS
    fun getUser(userId: Long): SleeperUser = getUser(userId.toString())
    fun getUser(userName: String): SleeperUser {
        val path = "/user/$userName"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    // LEAGUES
    fun getLeaguesForSeason(userId: Long, sport: String, season: String): Array<SleeperLeague> {
        val path = "/user/$userId/leagues/$sport/$season"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getLeagueById(leagueId: Long): SleeperLeague {
        val path = "/league/$leagueId"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getRostersForLeague(leagueId: Long): Array<SleeperRoster> {
        val path = "/league/$leagueId/rosters"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getUsersForLeague(leagueId: Long): Array<SleeperUser> {
        val path = "/league/$leagueId/users"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getMatchupsForLeague(leagueId: Long, week: Long): Array<SleeperMatchup>  {
        val path = "/league/$leagueId/matchups/$week"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getPlayoffBracket(leagueId: Long, bracket: BracketType): Bracket {
        val path = "/league/$leagueId/${bracket.bracketName}"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getLeagueTransactions(leagueId: Long, round: Long): Array<SleeperTransaction> {
        val path = "/league/$leagueId/transactions/$round"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getTradedPicksForLeague(leagueId: Long): Array<SleeperTradedPick> {
        val path = "/league/$leagueId/traded_picks"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getState(sport: String): SleeperState {
        val path = "/state/$sport"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    // DRAFTS
    fun getDraftsForUser(userId: Long, sport: String, season: String): Array<SleeperDraft> {
        val path = "/user/$userId/drafts/$sport/$season"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getDraftsForLeague(leagueId: Long): Array<SleeperDraft> {
        val path = "/user/$leagueId/drafts"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getDraft(draftId: Long): SleeperDraft {
        val path = "/draft/$draftId"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getPicksForDraft(draftId: Long): Array<SleeperPick> {
        val path = "/draft/$draftId/picks"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getTradedPicksForDraft(draftId: Long): Array<SleeperTradedPick> {
        val path = "/draft/$draftId/traded_picks"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    // PLAYERS
    fun getAllPlayers(sport: String): Map<String, SleeperPlayer> {
        val path = "/players/$sport"
        val request = buildRequest(path, HttpMethod.GET)

        return execute(request)
    }

    fun getTrendingPlayers(sport: String, type: PlayerTrend, lookbackHours: Long = 24, limit: Long = 25): Array<TrendingPlayer> {
        val path = "/players/$sport/trending/${type.trendName}"
        val queryParams = buildQueryParams(listOf("lookback_hours" to lookbackHours, "limit" to limit))
        val request = buildRequest(path, HttpMethod.GET, queryParams)

        return execute(request)
    }

    private fun buildRequest(
        path: String,
        method: String,
        params: Map<String, List<String>> = mapOf(),
        entity: Entity<*>? = null,
        vararg mediaTypes: MediaType = arrayOf(MediaType.APPLICATION_JSON_TYPE)
    ): Invocation {
        return webTarget
            .path(path)
            .also { params.forEach { (t, u) -> it.queryParam(t, u) } }
            .request()
            .accept(*mediaTypes)
            .build(method, entity)
    }

    private fun buildQueryParams(rawParams: List<Pair<String, Any>>): Map<String, List<String>> {
        val final = mutableMapOf<String, MutableList<String>>()
        rawParams.forEach { (key, value) ->
            if (final[key] != null) {
                final[key]!!.add(value.toString())
            } else {
                final[key] = mutableListOf(value.toString())
            }
        }

        return final
    }

    private inline fun <reified T> execute(request: Invocation): T {
        val result: T = Failsafe.with(retryPolicy).get { _ ->
            request.invoke(GenericType(T::class.java))
        }

        return result
    }
}
