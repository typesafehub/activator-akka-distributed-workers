package worker

import java.io.File

import akka.actor.{Actor, ActorSystem, PoisonPill, Props, RootActorPath}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.cluster.pubsub.{DistributedPubSubMediator, DistributedPubSub}
import akka.cluster.pubsub.DistributedPubSubMediator.{CurrentTopics, GetTopics, Subscribe, SubscribeAck}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object DistributedWorkerSpec {

  val clusterConfig = ConfigFactory.parseString("""
    |akka {
    |  actor.provider = "akka.cluster.ClusterActorRefProvider"
    |  remote.netty.tcp.port=0
    |  cluster.metrics.enabled=off
    |  persistence {
    |    journal.plugin = "akka.persistence.journal.leveldb"
    |    journal.leveldb {
    |      native = off
    |      dir = "target/test-journal"
    |    }
    |    snapshot-store {
    |      plugin = "akka.persistence.snapshot-store.local"
    |      local.dir = "target/test-snapshots"
    |    }
    |  }
    |}
    """.stripMargin)

  val workerConfig = ConfigFactory.parseString("""
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    akka.remote.netty.tcp.port=0
    """)

  class FlakyWorkExecutor extends Actor {
    var i = 0

    override def postRestart(reason: Throwable): Unit = {
      i = 3
      super.postRestart(reason)
    }

    def receive = {
      case n: Int =>
        i += 1
        if (i == 3) throw new RuntimeException("Flaky worker")
        if (i == 5) context.stop(self)

        val n2 = n * n
        val result = s"$n * $n = $n2"
        sender() ! Worker.WorkComplete(result)
    }
  }
}

class DistributedWorkerSpec(_system: ActorSystem)
  extends TestKit(_system)
  with Matchers
  with FlatSpecLike
  with BeforeAndAfterAll
  with ImplicitSender {

  import DistributedWorkerSpec._

  val workTimeout = 3.seconds

  def this() = this(ActorSystem("DistributedWorkerSpec", DistributedWorkerSpec.clusterConfig))

  val backendSystem: ActorSystem = {
    val config = ConfigFactory.parseString("akka.cluster.roles=[backend]").withFallback(clusterConfig)
    ActorSystem("DistributedWorkerSpec", config)
  }

  val workerSystem: ActorSystem = ActorSystem("DistributedWorkerSpec", workerConfig)

  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.snapshot-store.local.dir").map(s => new File(system.settings.config.getString(s)))

  override def beforeAll(): Unit = {
    storageLocations.foreach(dir => FileUtils.deleteDirectory(dir))
  }

  override def afterAll(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val allTerminated = Future.sequence(Seq(
      system.terminate(),
      backendSystem.terminate(),
      workerSystem.terminate()
    ))

    Await.ready(allTerminated, Duration.Inf)

    storageLocations.foreach(dir => FileUtils.deleteDirectory(dir))
  }

  "Distributed workers" should "perform work and publish results" in {
    val clusterAddress = Cluster(backendSystem).selfAddress
    val clusterProbe = TestProbe()
    Cluster(backendSystem).subscribe(clusterProbe.ref, classOf[MemberUp])
    clusterProbe.expectMsgType[CurrentClusterState]
    Cluster(backendSystem).join(clusterAddress)
    clusterProbe.expectMsgType[MemberUp]

    backendSystem.actorOf(
      ClusterSingletonManager.props(
        Master.props(workTimeout),
        PoisonPill,
        ClusterSingletonManagerSettings(system).withRole("backend")),
      "master")


    val initialContacts = Set(RootActorPath(clusterAddress) / "system" / "receptionist")
    val clusterClient = workerSystem.actorOf(
      ClusterClient.props(ClusterClientSettings(system).withInitialContacts(initialContacts)),
      "clusterClient")
    for (n <- 1 to 3)
      workerSystem.actorOf(Worker.props(clusterClient, Props[WorkExecutor], 1.second), "worker-" + n)
    val flakyWorker = workerSystem.actorOf(Worker.props(clusterClient, Props[FlakyWorkExecutor], 1.second), "flaky-worker")

    Cluster(system).join(clusterAddress)
    clusterProbe.expectMsgType[MemberUp]
    val frontend = system.actorOf(Props[Frontend], "frontend")

    val results = TestProbe()
    DistributedPubSub(system).mediator ! Subscribe(Master.ResultsTopic, results.ref)
    expectMsgType[SubscribeAck]

    // make sure pub sub topics are replicated over to the backend system before triggering any work
    within(10.seconds) {
      awaitAssert {
        DistributedPubSub(backendSystem).mediator ! GetTopics
        expectMsgType[CurrentTopics].getTopics() should contain(Master.ResultsTopic)
      }
    }

    // make sure we can get one piece of work through to fail fast if it doesn't
    within(10.seconds) {
      awaitAssert {
        frontend ! Work("1", 1)
        expectMsg(Frontend.Ok)
      }
    }
    results.expectMsgType[WorkResult].workId should be("1")


    // and then send in some actual work
    for (n <- 2 to 100) {
      frontend ! Work(n.toString, n)
      expectMsg(Frontend.Ok)
    }

    results.within(10.seconds) {
      val ids = results.receiveN(99).map { case WorkResult(workId, _) => workId }
      // nothing lost, and no duplicates
      ids.toVector.map(_.toInt).sorted should be((2 to 100).toVector)
    }

  }

}