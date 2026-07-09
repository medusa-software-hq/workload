package software.medusa.workload.server

class InMemoryFleetStoreTest : FleetStoreContractTest() {
  override fun createStore(): FleetStore = InMemoryFleetStore()
}
