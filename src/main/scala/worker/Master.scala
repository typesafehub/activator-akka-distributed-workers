package worker

import java.io.File
import scala.concurrent.duration._
import scala.concurrent.duration.Deadline
import scala.concurrent.duration.FiniteDuration
import org.eligosource.eventsourced.core.Eventsourced
import org.eligosource.eventsourced.core.EventsourcingExtension
import org.eligosource.eventsourced.core.Message
import org.eligosource.eventsourced.core.ReplayParams
import org.eligosource.eventsourced.journal.leveldb.LeveldbJournalProps
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.contrib.pattern.DistributedPubSubExtension
import akka.contrib.pattern.DistributedPubSubMediator
import akka.contrib.pattern.DistributedPubSubMediator.Put
import akka.pattern.pipe
import akka.util.Timeout

object Master {

  val ResultsTopic = "results"

  def props(workTimeout: FiniteDuration): Props = Props(classOf[Master], workTimeout)

  case class Ack(workId: String)

  private sealed trait WorkerStatus
  private case object Idle extends WorkerStatus
  private case class Busy(workId: String, deadline: Deadline) extends WorkerStatus
  private case class WorkerState(ref: ActorRef, status: WorkerStatus)

  private case object CleanupTick

}

class Master(workTimeout: FiniteDuration) extends Actor with ActorLogging {
  import Master._
  import WorkState._
  import EventSource._

  val mediator = DistributedPubSubExtension(context.system).mediator

  // TODO processorId must include cluster role to support multiple masters
  def processorId: Int = 1

  val eventSource: ActorRef = {
    val journalDir = new File(context.system.settings.config.getString("journal-dir"))
    val journal = LeveldbJournalProps(journalDir, native = false).createJournal
    val ext = EventsourcingExtension(context.system, journal)
    val ref = ext.processorOf(
      Props(new EventSource with Eventsourced { val id = processorId }), name = Some("eventsource"))
    val replayParams = ReplayParams(
      processorId = processorId)
    implicit val timeout = Timeout(1.minute)
    import context.dispatcher
    val c = for {
      _ ← ext.replay(Seq(replayParams))
      _ ← ext.deliver()
    } yield RecoveryCompleted
    c recover { case e ⇒ RecoveryFailed(e) } pipeTo ref
    ref
  }

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
    SupervisorStrategy.defaultDecider
  }

  mediator ! Put(self)

  // workers state is not event sourced
  private var workers = Map[String, WorkerState]()

  // workState is event sourced
  private var workState = WorkState.empty

  import context.dispatcher
  val cleanupTask = context.system.scheduler.schedule(workTimeout / 2, workTimeout / 2,
    self, CleanupTick)

  override def postStop(): Unit = cleanupTask.cancel()

  def receive = replaying

  def replaying: Actor.Receive = {
    case event: WorkDomainEvent ⇒ workState = workState.updated(event)
    case RecoveryCompleted ⇒
      log.info("Recovery completed")
      context.become(active)
  }

  def active: Actor.Receive = {
    case MasterWorkerProtocol.RegisterWorker(workerId) ⇒
      if (workers.contains(workerId)) {
        workers += (workerId -> workers(workerId).copy(ref = sender))
      } else {
        log.info("Worker registered: {}", workerId)
        workers += (workerId -> WorkerState(sender, status = Idle))
        if (workState.hasWork)
          sender ! MasterWorkerProtocol.WorkIsReady
      }

    case MasterWorkerProtocol.WorkerRequestsWork(workerId) ⇒
      if (workState.hasWork) {
        workers.get(workerId) match {
          case Some(s @ WorkerState(_, Idle)) ⇒
            val work = workState.nextWork
            val event = WorkStarted(work.workId)
            eventSource.forward(Message(event))
            workState = workState.updated(event)
            log.info("Giving worker {} some work {}", workerId, work.workId)
            workers += (workerId -> s.copy(status = Busy(work.workId, Deadline.now + workTimeout)))
            sender ! work
          case _ ⇒
        }
      }

    case event @ WorkStarted(workId) ⇒
      ; // already applied

    case MasterWorkerProtocol.WorkIsDone(workerId, workId, result) ⇒
      // idempotent
      if (workState.isDone(workId)) {
        // previous Ack was lost, confirm again that this is done
        sender ! MasterWorkerProtocol.Ack(workId)
      } else if (!workState.isInProgress(workId)) {
        log.info("Work {} not in progress, reported as done by worker {}", workId, workerId)
      } else {
        log.info("Work {} is done by worker {}", workId, workerId)
        changeWorkerToIdle(workerId, workId)
        eventSource.forward(Message(WorkCompleted(workId, result)))
      }

    case event @ WorkCompleted(workId, result) ⇒
      // domain event was stored
      workState = workState.updated(event)
      mediator ! DistributedPubSubMediator.Publish(ResultsTopic, WorkResult(workId, result))
      // Ack back to original sender
      sender ! MasterWorkerProtocol.Ack(workId)

    case MasterWorkerProtocol.WorkFailed(workerId, workId) ⇒
      if (workState.isInProgress(workId)) {
        log.info("Work {} failed by worker {}", workId, workerId)
        changeWorkerToIdle(workerId, workId)
        eventSource.forward(Message(WorkerFailed(workId)))
      }

    case event @ WorkerFailed(workId) ⇒
      // domain event was stored
      workState = workState.updated(event)
      notifyWorkers()

    case work: Work ⇒
      // idempotent
      if (workState.isAccepted(work.workId)) {
        sender ! Master.Ack(work.workId)
      } else {
        log.info("Accepted work: {}", work.workId)
        eventSource.forward(Message(WorkAccepted(work)))
      }

    case event @ WorkAccepted(work) ⇒
      // domain event was stored
      // Ack back to original sender
      sender ! Master.Ack(work.workId)
      workState = workState.updated(event)
      notifyWorkers()

    case CleanupTick ⇒
      for ((workerId, s @ WorkerState(_, Busy(workId, timeout))) ← workers) {
        if (timeout.isOverdue) {
          log.info("Work timed out: {}", workId)
          workers -= workerId
          eventSource.forward(Message(WorkerTimedOut(workId)))
        }
      }

    case event @ WorkerTimedOut(workId) ⇒
      workState = workState.updated(event)
      notifyWorkers()

  }

  def notifyWorkers(): Unit =
    if (workState.hasWork) {
      // could pick a few random instead of all
      workers.foreach {
        case (_, WorkerState(ref, Idle)) ⇒ ref ! MasterWorkerProtocol.WorkIsReady
        case _                           ⇒ // busy
      }
    }

  def changeWorkerToIdle(workerId: String, workId: String): Unit =
    workers.get(workerId) match {
      case Some(s @ WorkerState(_, Busy(`workId`, _))) ⇒
        workers += (workerId -> s.copy(status = Idle))
      case _ ⇒
      // ok, might happen after standby recovery, worker state is not persisted
    }

  // TODO cleanup old workers
  // TODO cleanup old workIds, doneWorkIds

}

object EventSource {
  case object RecoveryCompleted
  case class RecoveryFailed(e: Throwable)
}

// TODO this actor will not be needed, the Master will be a Processer itself, and send Message to self
class EventSource extends Actor with ActorLogging {
  import EventSource._

  def receive = {
    case msg: Message ⇒
      log.info("Stored/replayed {} {}", msg.sequenceNr, msg.event.getClass.getSimpleName)
      context.parent.forward(msg.event)
    case RecoveryCompleted ⇒
      log.info("Recovery completed")
      context.parent.forward(RecoveryCompleted)
    case RecoveryFailed(e) ⇒ throw e
  }

}