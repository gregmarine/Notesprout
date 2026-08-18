package com.symmetricalpalmtree.notesprout.extension

import android.os.Binder
import com.symmetricalpalmtree.notesprout.core.Slog
import kotlinx.coroutines.runBlocking

/**
 * The `IHandwritingRecognizer` the host lends an object provider as the `recognizer` in-parameter
 * of `createFromInk` (arc 4 / H3 — the capability pattern recorded in arc 3, built here). Minted
 * **per bind** by [ObjectProviderClient] for the provider's uid, [revoke]d in the client's `finally`
 * right after the unbind. Every method first passes [ProxyGate.check] (caller uid + not revoked, else
 * `SecurityException`), then forwards through the host's own [RecognizerClient] — its own bind, its
 * own timeout, its own signature check — so a proxied call is **two hops** and lives inside the
 * consumer call's budget.
 *
 * Forwarding = `runBlocking` on the host's **Binder thread** (never Main — a Binder thread is not the
 * UI thread). Inward caps re-applied: `recognizeInk` / `recognizePage` run [InkCaps.check] +
 * `preContext` truncation before forwarding. Failures map to the Binder-marshalable set:
 * [RecognizerNotReadyException] → `IllegalStateException(RECOGNIZER_NOT_READY)` (typed on both
 * sides), [InkTooLargeException] → `IllegalArgumentException`, any other [ExtensionCallException] →
 * `IllegalStateException(<class>)`. `status()` forwards all four values; `prepare()` forwards
 * (phase-start Q4: a provider may trigger the acquisition — the core still asks the user first in H4).
 * Logs: the gate's refusals and durations — never text.
 */
class RecognizerProxyBinder(
    private val client: RecognizerClient,
    extUid: Int,
) : IHandwritingRecognizer.Stub() {

    private val gate = ProxyGate(extUid, Binder::getCallingUid)

    /** After this every method throws `SecurityException`. */
    fun revoke() = gate.revoke()

    override fun status(): Int {
        gate.check()
        return forward { client.status() }
    }

    override fun prepare() {
        gate.check()
        forward { client.prepare() }
    }

    override fun recognizeInk(strokes: MutableList<InkStroke>?, areaWidth: Float, areaHeight: Float, preContext: String?): String {
        gate.check()
        val ink = strokes?.filterNotNull() ?: throw IllegalArgumentException("strokes is null")
        return forward {
            InkCaps.check(ink, areaWidth, areaHeight)   // re-applied inward; InkTooLargeException → IllegalArgumentException
            client.recognizeInk(ink, areaWidth, areaHeight, InkCaps.preContext(preContext ?: ""))
        }
    }

    override fun recognizePage(strokes: MutableList<InkStroke>?, pageWidth: Float, pageHeight: Float): String {
        gate.check()
        val ink = strokes?.filterNotNull() ?: throw IllegalArgumentException("strokes is null")
        return forward {
            InkCaps.check(ink, pageWidth, pageHeight)
            client.recognizePage(ink, pageWidth, pageHeight)
        }
    }

    /** Two-hop forward on the Binder thread; failures become the marshalable set. */
    private fun <T> forward(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: RecognizerNotReadyException) {
        throw IllegalStateException(ExtensionContract.RECOGNIZER_NOT_READY)
    } catch (e: InkTooLargeException) {
        throw IllegalArgumentException(e.message)
    } catch (e: ExtensionCallException) {
        Slog.d(TAG) { "forward failed: ${e.message}" }
        throw IllegalStateException(e.javaClass.simpleName)
    }

    private companion object {
        const val TAG = "RecognizerProxyBinder"
    }
}
