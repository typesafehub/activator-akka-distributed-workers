package worker

object MainManyMasters extends Startup {

  def main(args: Array[String]): Unit = {
    val joinAddress = startBackend(None, "backend-shard1")
    Thread.sleep(5000)
    startBackend(Some(joinAddress), "backend-shard1")
    startWorker(joinAddress)
    Thread.sleep(5000)
    startFrontend(joinAddress)

    startBackend(Some(joinAddress), "backend-shard2")
    startBackend(Some(joinAddress), "backend-shard2")
    startWorker(joinAddress)
    startWorker(joinAddress)
    startWorker(joinAddress)
  }

}