package software.medusa.workload.server

import software.medusa.workload.v1.DecrementRequest
import software.medusa.workload.v1.DecrementResponse
import software.medusa.workload.v1.GetCountRequest
import software.medusa.workload.v1.GetCountResponse
import software.medusa.workload.v1.IncrementRequest
import software.medusa.workload.v1.IncrementResponse
import software.medusa.workload.v1.WorkloadServiceGrpcKt

class WorkloadServiceImpl(
    private val counterStore: WorkloadStore,
) : WorkloadServiceGrpcKt.WorkloadServiceCoroutineImplBase() {
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
