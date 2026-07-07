package software.medusa.counter.server

import software.medusa.counter.v1.CounterServiceGrpcKt
import software.medusa.counter.v1.DecrementRequest
import software.medusa.counter.v1.DecrementResponse
import software.medusa.counter.v1.GetCountRequest
import software.medusa.counter.v1.GetCountResponse
import software.medusa.counter.v1.IncrementRequest
import software.medusa.counter.v1.IncrementResponse

// 🎨 TEMPLATE EJECT: Update the class name (this will be forced by Protobuf)
class CounterServiceImpl(
    private val counterStore: CounterStore,
) : CounterServiceGrpcKt.CounterServiceCoroutineImplBase() {
  override suspend fun getCount(request: GetCountRequest): GetCountResponse =
      GetCountResponse.newBuilder().setCount(counterStore.getCount(mainCounterId)).build()

  override suspend fun increment(request: IncrementRequest): IncrementResponse =
      IncrementResponse.newBuilder()
          .setCount(counterStore.incrementAndGetCount(mainCounterId))
          .build()

  override suspend fun decrement(request: DecrementRequest): DecrementResponse =
      DecrementResponse.newBuilder()
          .setCount(counterStore.decrementAndGetCount(mainCounterId))
          .build()
}
