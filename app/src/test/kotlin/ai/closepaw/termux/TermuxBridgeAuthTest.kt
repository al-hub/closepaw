package ai.closepaw.termux

import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

class TermuxBridgeAuthTest {
    @Test
    fun `concurrent first token requests converge on one persisted token`() {
        val stored = AtomicReference<String?>(null)
        val generated = AtomicInteger(0)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val results = Collections.synchronizedList(mutableListOf<String>())

        repeat(32) {
            pool.submit {
                start.await()
                results += getOrCreateBridgeToken(
                    read = { stored.get() },
                    persist = { token -> stored.set(token); true },
                    generate = { "token-${generated.incrementAndGet()}-0123456789abcdef0123456789abcdef" },
                )
            }
        }

        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue()

        assertThat(generated.get()).isEqualTo(1)
        assertThat(results.toSet()).containsExactly(stored.get())
    }
}
