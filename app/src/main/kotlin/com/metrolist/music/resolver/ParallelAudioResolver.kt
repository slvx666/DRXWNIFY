/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds a track's audio by querying every enabled provider AT THE SAME TIME instead of a long
 * sequential chain of timeouts.
 *
 * Selection policy (fast + deterministic), using the user's provider order:
 *  - A provider's confident match is taken as soon as every HIGHER-ranked provider has already
 *    answered "no match"/failed — so the preferred source still wins when it is quick.
 *  - Once [softDeadlineMs] has passed, the best-ranked match found so far is taken immediately;
 *    slower higher-ranked providers are not waited for (a blocked YouTube can't hold playback hostage).
 *  - With no match yet, waiting continues until all providers finish or [hardDeadlineMs] passes, so
 *    coverage does not suffer for rare tracks only a slow provider has.
 *
 * Every match that arrived before the decision is returned as an alternate, best-ranked first, so a
 * stream failure later can switch provider without searching again.
 */
class ParallelAudioResolver(
    private val softDeadlineMs: Long = SOFT_DEADLINE_MS,
    private val hardDeadlineMs: Long = HARD_DEADLINE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Quality mode: exact matches are compared by sound quality, and all sources get a chance. */
    private val preferQuality: () -> Boolean = {
        ResolverPreferences.pickMode != com.metrolist.music.constants.SourcePickMode.SPEED
    },
) {
    data class Outcome(
        val winner: ProviderMatch?,
        /** Every match seen (winner included), best-ranked first. */
        val matches: List<ProviderMatch>,
        /** Providers that answered with no match (not failures/timeouts) — safe to negative-cache. */
        val definitiveMisses: Set<AudioProviderId>,
        val elapsedMs: Long,
        /** One short line per provider: outcome + time, for the audio search log. */
        val report: List<String> = emptyList(),
    )

    private sealed interface Answer {
        val provider: AudioProviderId
        val elapsedMs: Long

        data class Found(val match: ProviderMatch, override val elapsedMs: Long) : Answer {
            override val provider get() = match.provider
        }

        data class Miss(override val provider: AudioProviderId, override val elapsedMs: Long) : Answer
        data class Failed(override val provider: AudioProviderId, override val elapsedMs: Long, val reason: String) : Answer
    }

    /**
     * @param order provider ranking, best first; providers missing from it rank last.
     */
    suspend fun resolve(
        query: AudioQuery,
        providers: List<AudioProvider>,
        order: List<AudioProviderId> = AudioProviderId.entries,
    ): Outcome = coroutineScope {
        val start = clock()
        fun rank(id: AudioProviderId): Int = order.indexOf(id).let { if (it < 0) order.size + id.ordinal else it }
        val ordered = providers.sortedBy { rank(it.id) }
        if (ordered.isEmpty()) return@coroutineScope Outcome(null, emptyList(), emptySet(), 0)

        val answers = Channel<Answer>(capacity = ordered.size)
        val jobs = ArrayList<Job>(ordered.size)
        for (provider in ordered) {
            jobs += launch {
                val answer = try {
                    // Wrapped so a provider timeout (null) is distinguishable from "searched, not found".
                    val result = withTimeoutOrNull(provider.searchTimeoutMs) { Result.success(provider.search(query)) }
                    val match = result?.getOrNull()
                    val elapsed = clock() - start
                    when {
                        result == null -> Answer.Failed(provider.id, elapsed, "timeout ${provider.searchTimeoutMs}ms")
                        match != null -> Answer.Found(match, elapsed)
                        else -> Answer.Miss(provider.id, elapsed)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Answer.Failed(provider.id, clock() - start, e.message ?: e.javaClass.simpleName)
                }
                answers.trySend(answer)
            }
        }

        val pending = ordered.map { it.id }.toMutableSet()
        val found = sortedMapOf<Int, ProviderMatch>()
        val misses = mutableSetOf<AudioProviderId>()
        val report = mutableListOf<String>()

        val quality = preferQuality()
        fun ordered(matches: Collection<ProviderMatch>) =
            if (quality) byQuality(matches, ::rank) else preferred(matches, ::rank)

        fun decide(now: Long): ProviderMatch? {
            if (quality) {
                // Every source may still have the same track in better quality: wait for all of them,
                // but not beyond the quality deadline once an exact match is in hand.
                val best = ordered(found.values).firstOrNull() ?: return null
                val strongInHand = found.values.any { it.confidence >= STRONG_MATCH }
                return if (pending.isEmpty() || (strongInHand && now - start >= QUALITY_DEADLINE_MS)) best else null
            }
            val best = preferred(found.values, ::rank).firstOrNull() ?: return null
            val higherPending = pending.any { rank(it) < rank(best.provider) }
            // A doubtful match doesn't end the race early: a slower provider may still have the exact track.
            val couldBeBeaten = best.confidence < STRONG_MATCH && pending.isNotEmpty()
            return if ((!higherPending && !couldBeBeaten) || now - start >= softDeadlineMs) best else null
        }

        var winner: ProviderMatch? = null
        while (pending.isNotEmpty()) {
            val now = clock()
            val waitUntil = when {
                found.isEmpty() -> start + hardDeadlineMs
                quality -> start + QUALITY_DEADLINE_MS
                else -> start + softDeadlineMs
            }
            val remaining = waitUntil - now
            if (remaining <= 0) {
                winner = ordered(found.values).firstOrNull()
                break
            }
            val answer = withTimeoutOrNull(remaining) { answers.receive() } ?: continue
            pending -= answer.provider
            when (answer) {
                is Answer.Found -> {
                    found[rank(answer.provider)] = answer.match
                    report += "${answer.provider} ✔ ${answer.elapsedMs}ms '${answer.match.artist} – ${answer.match.title}' (${"%.2f".format(answer.match.confidence)})"
                }
                is Answer.Miss -> {
                    misses += answer.provider
                    report += "${answer.provider} – no match ${answer.elapsedMs}ms"
                }
                is Answer.Failed -> report += "${answer.provider} ✘ ${answer.elapsedMs}ms ${answer.reason}"
            }
            decide(clock())?.let {
                winner = it
                break
            }
        }
        if (winner == null) winner = ordered(found.values).firstOrNull()
        pending.forEach { report += "$it … not waited for" }

        jobs.forEach { it.cancel() }
        val elapsed = clock() - start
        Outcome(winner, ordered(found.values), misses, elapsed, report)
    }

    companion object {
        /**
         * Matches this much less confident than the best one found are only fallbacks: the user's
         * provider order decides between comparably good matches, never between an exact match and
         * a doubtful one (a 0.77 "Mercury & The Architects – Machine" on Bandcamp used to beat a
         * 1.00 "Architects – Machine" on YouTube just because Bandcamp was ranked higher).
         */
        const val CONFIDENCE_MARGIN = 0.15

        /** Confidence stored for a source the user picked by hand: always wins, never re-checked. */
        const val MANUAL_CONFIDENCE = 10.0

        /** A match at least this confident is taken without waiting for lower-ranked providers. */
        const val STRONG_MATCH = 0.9

        /** Comparably confident matches in the user's order, then the weaker ones by confidence. */
        fun preferred(matches: Collection<ProviderMatch>, rank: (AudioProviderId) -> Int): List<ProviderMatch> {
            if (matches.isEmpty()) return emptyList()
            val best = matches.maxOf { it.confidence }
            val (strong, weak) = matches.partition { it.confidence >= best - CONFIDENCE_MARGIN }
            return strong.sortedBy { rank(it.provider) } + weak.sortedByDescending { it.confidence }
        }

        /** The order for the current setting: best quality among exact matches, or the user's order. */
        fun inPickOrder(matches: Collection<ProviderMatch>, rank: (AudioProviderId) -> Int): List<ProviderMatch> =
            if (ResolverPreferences.pickMode != com.metrolist.music.constants.SourcePickMode.SPEED) {
                byQuality(matches, rank)
            } else {
                preferred(matches, rank)
            }

        /** Exact matches differ by at most this much; among them the better-sounding one wins. */
        const val QUALITY_CONFIDENCE_MARGIN = 0.12

        /** "Accuracy": only near-identical matches are compared by quality. */
        const val ACCURACY_CONFIDENCE_MARGIN = 0.05

        /** Quality mode: how long the other sources may take once an exact match is in hand. */
        const val QUALITY_DEADLINE_MS = 4_500L

        /**
         * The most exact matches first, best quality among them (VK's 320 kbps MP3 over YouTube's
         * ~160 Opus when both found the very same track), the user's order breaking ties; the less
         * exact ones after, by confidence.
         */
        fun byQuality(matches: Collection<ProviderMatch>, rank: (AudioProviderId) -> Int): List<ProviderMatch> {
            if (matches.isEmpty()) return emptyList()
            val best = matches.maxOf { it.confidence }
            val margin = if (ResolverPreferences.pickMode == com.metrolist.music.constants.SourcePickMode.QUALITY) QUALITY_CONFIDENCE_MARGIN else ACCURACY_CONFIDENCE_MARGIN
            val (exact, rest) = matches.partition { it.confidence >= best - margin }
            return exact.sortedWith(compareByDescending<ProviderMatch> { it.expectedKbps }.thenBy { rank(it.provider) }) +
                rest.sortedByDescending { it.confidence }
        }

        /** After this, the best match so far is used without waiting for slower, higher-ranked providers. */
        const val SOFT_DEADLINE_MS = 2_500L

        /** Absolute cap when nothing matched yet. */
        const val HARD_DEADLINE_MS = 14_000L
    }
}
