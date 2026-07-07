package software.medusa.counter.server

val mainCounterId = CounterId("main")

@JvmInline
value class CounterId(
    val id: String,
)

interface CounterStore {
  suspend fun getCount(counterId: CounterId): Int

  suspend fun incrementAndGetCount(counterId: CounterId): Int

  suspend fun decrementAndGetCount(counterId: CounterId): Int
}
