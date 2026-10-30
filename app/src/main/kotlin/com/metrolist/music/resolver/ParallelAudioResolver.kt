/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.resolver

import com.metrolist.music.constants.SourcePickMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds a track's audio by querying every enabled provider AT THE SAME TIME instead of a long
 * sequential chain of timeouts. What is waited for depends on the [SourcePickMode]:
 *
 *  - AUTO (default): an exact match plays as soon as it is in, whatever the source, unless it is
 *    below [LOW_KBPS] — then a better-sounding one gets [AUTO_LOW_GRACE_MS] more. The sources still
 *    searching are not cancelled: they finish in [background], and [Outcome.late] reports what they
 *    found, so a better version can be kept for the next play / download and shown as available.
 *  - SPEED: the user's provider order decides between comparable matches; a strong match is taken
 *    at once, the best one so far after [softDeadlineMs].
 *  - QUALITY: waits for an exact match of [QUALITY_TARGET_KBPS]+ for up to [QUALITY_DEADLINE_MS],
 *    then plays the best one in hand.
 *
 * In every mode a wrong track is worse than a poor bitrate: a doubtful match never wins over an
 * exact one by sound quality, and a doubtful match does not end the race while a better-ranked
 * source may still have the exact track. With no match yet, waiting goes on until all providers
 * finish or [hardDeadlineMs] passes, so rare tracks only a slow provider has are still found.
 *
 * Every match that arrived before the decision is returned as an alternate, best first, so a stream
 * failure later can switch provider without searching again.
 */
class ParallelAudioResolver(
    private val softDeadlineMs: Long = SOFT_DEADLINE_MS,
    private val hardDeadlineMs: Long = HARD_DEADLINE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mode: () -> SourcePickMode = { ResolverPreferences.pickMode },
    private val isSlow: (AudioProviderId) -> Boolean = ProviderHealth::isSlow,
    /** Where searches still running after the decision may finish (AUTO / QUALITY); null = cancelled. */
    private val background: CoroutineScope? = null,
) {
    data class Outcome(
        val winner: ProviderMatch?,
        /** Every match seen (winner included), best first. */
        val matches: List<ProviderMatch>,
        /** Providers that answered with no match (not failures/timeouts) — safe to negative-cache. */
        val definitiveMisses: Set<AudioProviderId>,
        val elapsedMs: Long,
        /** One short line per provider: outcome + time, for the audio search log. */
        val report: List<String> = emptyList(),
        /**
         * Sources that were still searching when the winner was chosen and kept going: completes
         * with every match (the early ones too), best first, plus their report lines. Null when
         * nothing was left to wait for.
         */
        val late: Deferred<Late>? = null,
    )

    data class Late(val matches: List<ProviderMatch>, val report: List<String>)

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

        val pickMode = mode()
        // Searches outlive the decision only where something may still be gained by them.
        val keepScope = background.takeIf { pickMode != SourcePickMode.SPEED }
        val answers = Channel<Answer>(capacity = ordered.size)
        val jobs = ArrayList<Job>(ordered.size)
        for (provider in ordered) {
            jobs += (keepScope ?: this).launch {
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

        fun ordered(matches: Collection<ProviderMatch>) = inOrder(matches, pickMode, ::rank)

        /** Sources still searching that are worth waiting for (not one that lately sent no audio in time). */
        fun waitable() = pending.filterNot(isSlow)

        /** A doubtful match from a lower-ranked source while a preferred one is still searching. */
        fun doubtfulWhileBetterPending(best: ProviderMatch) =
            best.confidence < STRONG_MATCH && waitable().any { rank(it) < rank(best.provider) }

        /** A source still searching that could deliver the same track in better sound than [kbps]. */
        fun betterSoundPending(kbps: Int, provider: AudioProviderId) = waitable().any { id ->
            id.typicalKbps > kbps || (id.typicalKbps == kbps && rank(id) < rank(provider))
        }

        fun decideBySpeed(now: Long): ProviderMatch? {
            val best = preferred(found.values, ::rank).firstOrNull() ?: return null
            val higherPending = waitable().any { rank(it) < rank(best.provider) }
            // A doubtful match doesn't end the race early: a slower provider may still have the exact track.
            val couldBeBeaten = best.confidence < STRONG_MATCH && pending.isNotEmpty()
            // The soft deadline only cuts short the wait for a STRONG match: a doubtful SoundCloud
            // re-upload used to win simply because YouTube needed 3 s to answer.
            val deadlinePassed = now - start >= softDeadlineMs && !doubtfulWhileBetterPending(best)
            return if ((!higherPending && !couldBeBeaten) || deadlinePassed) best else null
        }

        fun decide(now: Long): ProviderMatch? {
            if (pending.isEmpty()) return ordered(found.values).firstOrNull()
            return when (pickMode) {
                SourcePickMode.SPEED -> decideBySpeed(now)
                SourcePickMode.AUTO -> {
                    val best = ordered(found.values).firstOrNull() ?: return null
                    when {
                        best.confidence < AUTO_INSTANT_MATCH -> decideBySpeed(now)
                        best.expectedKbps >= LOW_KBPS -> best
                        // Low bitrate: a moment more for the same track in decent sound.
                        now - start >= AUTO_LOW_GRACE_MS -> best
                        waitable().none { it.typicalKbps >= LOW_KBPS } -> best
                        else -> null
                    }
                }
                SourcePickMode.QUALITY -> {
                    val best = ordered(found.values).firstOrNull() ?: return null
                    val strongInHand = found.values.any { it.confidence >= STRONG_MATCH }
                    when {
                        !strongInHand -> null
                        best.expectedKbps >= QUALITY_TARGET_KBPS && !betterSoundPending(best.expectedKbps, best.provider) -> best
                        best.expectedKbps >= QUALITY_TARGET_KBPS && now - start >= QUALITY_TARGET_GRACE_MS -> best
                        !betterSoundPending(best.expectedKbps, best.provider) -> best
                        now - start >= QUALITY_DEADLINE_MS -> best
                        else -> null
                    }
                }
            }
        }

        fun waitUntil(): Long {
            val bestSoFar = ordered(found.values).firstOrNull() ?: return start + hardDeadlineMs
            if (doubtfulWhileBetterPending(bestSoFar)) return start + hardDeadlineMs
            return when (pickMode) {
                SourcePickMode.SPEED -> start + softDeadlineMs
                SourcePickMode.AUTO ->
                    if (bestSoFar.confidence >= AUTO_INSTANT_MATCH) start + AUTO_LOW_GRACE_MS else start + softDeadlineMs
                SourcePickMode.QUALITY ->
                    if (found.values.none { it.confidence >= STRONG_MATCH }) start + hardDeadlineMs
                    else if (bestSoFar.expectedKbps >= QUALITY_TARGET_KBPS) start + QUALITY_TARGET_GRACE_MS
                    else start + QUALITY_DEADLINE_MS
            }
        }

        fun record(answer: Answer) {
            pending -= answer.provider
            when (answer) {
                is Answer.Found -> {
                    found[rank(answer.provider)] = answer.match
                    report += "${answer.provider} ✔ ${answer.elapsedMs}ms '${answer.match.artist} – ${answer.match.title}' " +
                        "(${"%.2f".format(answer.match.confidence)}, ~${answer.match.expectedKbps} kbps)"
                }
                is Answer.Miss -> {
                    misses += answer.provider
                    report += "${answer.provider} – no match ${answer.elapsedMs}ms"
                }
                is Answer.Failed -> report += "${answer.provider} ✘ ${answer.elapsedMs}ms ${answer.reason}"
            }
        }

        var winner: ProviderMatch? = null
        decide(clock())?.let { winner = it }
        while (winner == null && pending.isNotEmpty()) {
            val remaining = waitUntil() - clock()
            if (remaining <= 0) {
                winner = ordered(found.values).firstOrNull()
                break
            }
            val answer = withTimeoutOrNull(remaining) { answers.receive() } ?: continue
            record(answer)
            winner = decide(clock())
        }
        if (winner == null) winner = ordered(found.values).firstOrNull()
        val elapsed = clock() - start

        val late: Deferred<Late>? = if (keepScope != null && pending.isNotEmpty() && winner != null) {
            // The rest finish on their own time; what they find is reported, never waited for here.
            pending.forEach { report += "$it … still searching (finishes in the background)" }
            val result = CompletableDeferred<Late>()
            val lateReport = mutableListOf<String>()
            keepScope.launch {
                try {
                    while (pending.isNotEmpty()) {
                        val left = start + hardDeadlineMs - clock()
                        if (left <= 0) break
                        val answer = withTimeoutOrNull(left) { answers.receive() } ?: break
                        val before = report.size
                        record(answer)
                        lateReport += report.subList(before, report.size)
                    }
                    pending.forEach { lateReport += "$it … gave up at ${hardDeadlineMs}ms" }
                } finally {
                    jobs.forEach { it.cancel() }
                    result.complete(Late(ordered(found.values), lateReport))
                }
            }
            result
        } else {
            pending.forEach { report += "$it … not waited for" }
            jobs.forEach { it.cancel() }
            null
        }
        Outcome(winner, ordered(found.values.toList()), misses.toSet(), elapsed, report.toList(), late)
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

        /** AUTO: a match this exact (95 %+) plays at once from any source. */
        const val AUTO_INSTANT_MATCH = 0.95

        /** Below this a stream counts as low quality (119 kbps and under). */
        const val LOW_KBPS = 120

        /** AUTO: how long an exact but low-bitrate match waits for a decent-sounding one. */
        const val AUTO_LOW_GRACE_MS = 1_200L

        /** QUALITY: the bitrate worth waiting for. */
        const val QUALITY_TARGET_KBPS = 256

        /** QUALITY: with a 256+ match in hand, how long an even better one (lossless) may take. */
        const val QUALITY_TARGET_GRACE_MS = 1_500L

        /** QUALITY: how long to wait for [QUALITY_TARGET_KBPS] before playing what there is. */
        const val QUALITY_DEADLINE_MS = 5_000L

        /** Comparably confident matches in the user's order, then the weaker ones by confidence. */
        fun preferred(matches: Collection<ProviderMatch>, rank: (AudioProviderId) -> Int): List<ProviderMatch> {
            if (matches.isEmpty()) return emptyList()
            val best = matches.maxOf { it.confidence }
            val (strong, weak) = matches.partition { it.confidence >= best - CONFIDENCE_MARGIN }
            return strong.sortedBy { rank(it.provider) } + weak.sortedByDescending { it.confidence }
        }

        /** The order for the current setting: best quality among exact matches, or the user's order. */
        fun inPickOrder(matches: Collection<ProviderMatch>, rank: (AudioProviderId) -> Int): List<ProviderMatch> =
            inOrder(matches, ResolverPreferences.pickMode, rank)

        fun inOrder(matches: Collection<ProviderMatch>, mode: SourcePickMode, rank: (AudioProviderId) -> Int): List<ProviderMatch> =
            when (mode) {
                SourcePickMode.SPEED -> preferred(matches, rank)
                SourcePickMode.AUTO -> byQuality(matches, rank, AUTO_CONFIDENCE_MARGIN)
                SourcePickMode.QUALITY -> byQuality(matches, rank, QUALITY_CONFIDENCE_MARGIN)
            }

        /** QUALITY: exact matches differ by at most this much; among them the better-sounding one wins. */
        const val QUALITY_CONFIDENCE_MARGIN = 0.12

        /** AUTO: only near-identical matches are compared by sound (a wrong version is worse than 128 kbps). */
        const val AUTO_CONFIDENCE_MARGIN = 0.05

        /**
         * The most exact matches first, best quality among them (VK's 320 kbps MP3 over YouTube's
         * ~160 Opus when both found the very same track), the user's order breaking ties; the less
         * exact ones after, by confidence.
         */
        fun byQuality(
            matches: Collection<ProviderMatch>,
            rank: (AudioProviderId) -> Int,
            margin: Double = AUTO_CONFIDENCE_MARGIN,
        ): List<ProviderMatch> {
            if (matches.isEmpty()) return emptyList()
            val best = matches.maxOf { it.confidence }
            val (exact, rest) = matches.partition { it.confidence >= best - margin }
            return exact.sortedWith(compareByDescending<ProviderMatch> { it.expectedKbps }.thenBy { rank(it.provider) }) +
                rest.sortedByDescending { it.confidence }
        }

        /** SPEED: after this, the best match so far is used without waiting for slower, higher-ranked providers. */
        const val SOFT_DEADLINE_MS = 2_500L

        /** Absolute cap when nothing matched yet. */
        const val HARD_DEADLINE_MS = 14_000L
    }
}
