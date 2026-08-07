package dev.kmemo

/**
 * Encrypts what a [CacheStore] persists, so a database full of user questions is not what an auditor
 * finds.
 *
 * `CacheEntry.prompt` is stored verbatim and has to be: the guards re-read it on every hit, and reading
 * it as text is the whole mechanism. `kmemo-slf4j` redacts prompts by default because prompts are user
 * input and routinely carry personal data, which is the right instinct applied to the one surface where
 * it was cheap. The store is the surface where it matters, and until this the only answers were to veto
 * the write with a [CachePolicy], which means not caching at all, or to encrypt the whole database at
 * rest, which protects nothing from anyone who can read the database.
 *
 * kmemo ships **no cryptography**, for the same reason it ships no embedding model and no price table.
 * The key is yours, the algorithm is yours, and their lifecycle is a problem this library is not
 * qualified to have opinions about. What ships is the seam and
 * [dev.kmemo.store.EncryptedStore], which applies it to every store there is.
 *
 * ### The contract, and the one clause that is not negotiable
 *
 * **Encryption must be randomized.** Encrypting the same plaintext twice must produce different
 * ciphertext. Deterministic encryption would let the guards run against ciphertext and cost nothing on
 * the read path, and it is not an option: it leaks equality, and equality across prompts is exactly
 * what an attacker holding the database wants. Two rows with the same ciphertext are two users who
 * asked the same question, and on a small population that is often enough to identify both.
 * [dev.kmemo.store.EncryptedStore] checks this on its first write and refuses to run against a cipher
 * that fails it, rather than leaving the guarantee to a comment nobody reads.
 *
 * [decrypt] must return exactly what [encrypt] was given, for ciphertext this cipher produced. It is
 * called on the read path, on entries that may have been written a long time ago, so a cipher that
 * rotates keys has to keep decrypting the old ones. [identity] is how a rotation stops being silent.
 *
 * Both methods run on the cache's coroutine and may suspend, which is what makes a key management
 * service or a hardware module usable here rather than only an in-process key.
 *
 * ### What it does not cover
 *
 * **The embedding is not encrypted, and cannot be.** The store finds an entry by comparing vectors, so
 * the vector has to be readable by the store. An embedding is a lossy representation of the prompt and
 * an attacker holding the database and the same embedding model can compare a guess against it. That is
 * a materially smaller disclosure than the sentence itself and it is not nothing, and a deployment
 * whose threat model includes it should not be caching the prompts at all.
 *
 * Tags and metadata are also passed through unencrypted. Tags are meant to be low-cardinality labels
 * about a source of truth (`price-list`, `policy-2026`) rather than about a request, and metadata is
 * caller-supplied payload the cache never reads. Neither is user input unless you make it so.
 *
 * `docs/THREAT-MODEL.md` is the assembled version of all of this: the assets, the adversaries, the
 * trust boundaries, and what is still disclosed once every mitigation the library offers is switched
 * on. It is the document to hand a security review, because deciding whether to trust a library from
 * four class comments is homework nobody should be set.
 */
public interface EntryCipher {

    /**
     * Names the key and algorithm in force, so a rotation is loud rather than silent.
     *
     * Same shape and same purpose as [Embedder.identity], and the same rule: stable for the lifetime of
     * the cipher. Never put key material in it. It is written onto nothing today and exists so a
     * deployment can log or assert on which key a process is running.
     */
    public val identity: String get() = UNDECLARED

    /** Encrypts [plaintext] written in [scope]. Must be randomized: see the class documentation. */
    public suspend fun encrypt(plaintext: String, scope: String): String

    /** Recovers the plaintext [encrypt] was given, for [ciphertext] this cipher produced in [scope]. */
    public suspend fun decrypt(ciphertext: String, scope: String): String

    public companion object {
        /** What [identity] reads when a cipher does not declare one. */
        public const val UNDECLARED: String = "undeclared"
    }
}
