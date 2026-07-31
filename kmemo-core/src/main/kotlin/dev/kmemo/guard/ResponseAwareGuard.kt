package dev.kmemo.guard

/**
 * A [MatchGuard] that also reads the candidate's **stored response**, not just the two prompts.
 *
 * Every other guard compares a query with a cached prompt, because that is all the match path used to
 * offer. That leaves one shape of near miss structurally invisible: two prompts that read as honest
 * paraphrases, where the thing separating the answers lives in the *answer*. "What is the capital
 * gains tax rate when I sell a second home" and "…a primary residence" clear every guard in
 * [MatchGuards.standard] — no number differs, no unit, no negation, nothing either prompt says is
 * evidence. The cached answer opens "Gain on a second home is taxable in full", and serving it to the
 * other question is a confident, wrong answer about somebody's tax bill.
 *
 * **Only the candidate's response exists at this point.** The query's answer is precisely what the
 * cache is trying not to pay for, so a response-aware guard cannot compare two answers. It can only
 * ask whether *this* answer was written for *this* question.
 *
 * The prompt-only [evaluate] abstains, so dropping one of these into a plain `List<MatchGuard>` — or
 * into a call path that has no entry to hand, such as [dev.kmemo.SemanticCache.explain] on a store
 * that returned no response — is safe rather than silently wrong. Abstaining is always the right move
 * over guessing; see [MatchGuard] for why the cost of the two mistakes is not symmetric.
 *
 * Implementations carry the same contract as any guard: pure, thread-safe, and fast enough to run on
 * every candidate above the threshold. A response can be far longer than a prompt, so "fast" is the
 * binding constraint here — read the response, do not parse it.
 */
public interface ResponseAwareGuard : MatchGuard {

    /**
     * Judges serving [candidateResponse], cached for [candidate], in answer to [query].
     *
     * @return [GuardVerdict.Reject] only with a concrete reason; [GuardVerdict.Accept] otherwise.
     */
    public fun evaluate(query: String, candidate: String, candidateResponse: String): GuardVerdict

    /**
     * Abstains. A response-aware guard shown only the two prompts has nothing it can say that the
     * prompt-side guards have not already said.
     */
    override fun evaluate(query: String, candidate: String): GuardVerdict = GuardVerdict.Accept
}
