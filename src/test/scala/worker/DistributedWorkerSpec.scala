package worker

import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, FlatSpec }
import org.scalatest.matchers.ShouldMatchers
import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.contrib.pattern.DistributedPubSubExtension
import akka.contrib.pattern.DistributedPubSubMediator.Subscribe
import akka.contrib.pattern.DistributedPubSubMediator.SubscribeAck
import akka.cluster.Cluster
import akka.contrib.pattern.ClusterSingletonManager
import akka.actor.Props
import akka.actor.PoisonPill
import akka.actor.RootActorPath
import akka.contrib.pattern.ClusterClient
import akka.actor.ActorRef
import akka.actor.Actor
import akka.testkit.ImplicitSender
import akka.testkit.TestProbe
import org.apache.commons.io.FileUtils
import com.typesafe.config.ConfigFactory
import java.io.File

object DistributedWorkerSpec {
  class FlakyWorkExecutor extends Actor {
    var i = 0

    override def postRestart(reason: Throwable): Unit = {
      i = 3
      super.postRestart(reason)
    }

    def receive = {
      case n: Int ⇒
        i += 1
        if (i == 3) throw new RuntimeException("Flaky worker")
        if (i == 5) context.stop(self)

        val n2 = n * n
        val result = s"$n * $n = $n2"
        sender ! Worker.WorkComplete(result)
    }
  }
}

class DistributedWorkerSpec(_system: ActorSystem)
  extends TestKit(_system)
  with ShouldMatchers
  with FlatSpec
  with BeforeAndAfterAll
  with ImplicitSender {

  import DistributedWorkerSpec._

  val workTimeout = 3.seconds
  val journalDir = new File(system.settings.config.getString("akka.persistence.journal.leveldb.dir"))

  def this() = this(ActorSystem("DistributedWorkerSpec",
    ConfigFactory.parseString("akka.persistence.journal.leveldb.dir=target/test-journal").
      withFallback(ConfigFactory.load())))

  override def beforeAll: Unit =
    FileUtils.deleteDirectory(journalDir)

  override def afterAll: Unit = {
    system.shutdown()
    system.awaitTermination(10.seconds)
    FileUtils.deleteDirectory(journalDir)
  }

  "Distributed workers" should "perform work and publish results" in {
    val clusterAddress = Cluster(system).selfAddress
    Cluster(system).join(clusterAddress)
    system.actorOf(ClusterSingletonManager.props(_ ⇒ Master.props(workTimeout), "active",
      PoisonPill, None), "master")

    val initialContacts = Set(
      system.actorSelection(RootActorPath(clusterAddress) / "user" / "receptionist"))
    val clusterClient = system.actorOf(ClusterClient.props(initialContacts), "clusterClient")
    for (n ← 1 to 3)
      system.actorOf(Worker.props(clusterClient, Props[WorkExecutor], 1.second), "worker-" + n)
    val flakyWorker = system.actorOf(Worker.props(clusterClient, Props[FlakyWorkExecutor], 1.second), "flaky-worker")

    val frontend = system.actorOf(Props[Frontend], "frontend")

    val results = TestProbe()
    DistributedPubSubExtension(system).mediator ! Subscribe(Master.ResultsTopic, results.ref)
    expectMsgType[SubscribeAck]

    // might take a while for things to get connected
    within(10.seconds) {
      awaitAssert {
        frontend ! Work("1", 1)
        expectMsg(Frontend.Ok)
      }
    }

    within(10.seconds) {
      results.expectMsgType[WorkResult].workId should be("1")
    }

    for (n ← 2 to 100) {
      frontend ! Work(n.toString, n)
      expectMsg(Frontend.Ok)
    }

    results.within(10.seconds) {
      val ids = results.receiveN(99).map { case WorkResult(workId, _) ⇒ workId }
      // nothing lost, and no duplicates
      ids.toVector.map(_.toInt).sorted should be((2 to 100).toVector)
    }

  }

}