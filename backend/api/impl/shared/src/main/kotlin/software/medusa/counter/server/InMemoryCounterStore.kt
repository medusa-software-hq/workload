package software.medusa.counter.server

import java.util.concurrent.atomic.AtomicInteger

class InMemoryCounterStore : CounterStore {
  private val counters = java.util.concurrent.ConcurrentHashMap<CounterId, AtomicInteger>()

  override suspend fun getCount(counterId: CounterId): Int = counters[counterId]?.get() ?: 0

  override suspend fun incrementAndGetCount(counterId: CounterId): Int =
      counters.getOrPut(counterId) { AtomicInteger(0) }.incrementAndGet()

  override suspend fun decrementAndGetCount(counterId: CounterId): Int =
      counters.getOrPut(counterId) { AtomicInteger(0) }.decrementAndGet()
}
