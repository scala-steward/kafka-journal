package akka.persistence.kafka.journal

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.persistence._
import akka.persistence.journal.JournalSpec
import akka.testkit.DefaultTimeout
import com.evolutiongaming.nel.Nel
import com.typesafe.config.ConfigFactory
import org.scalatest.Matchers

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Promise}

class ConsistencySpec extends PluginSpec(ConfigFactory.load("consistency.conf"))
  with KafkaPluginSpec
  with DefaultTimeout
  with Matchers {

  implicit lazy val system: ActorSystem = ActorSystem("ConsistencySpec", config.withFallback(JournalSpec.config))

  "A KafkaJournal" should {

    "replay events" in {
      val ref = PersistenceRef()
      val events = Nel("event")
      ref.persist(events)
      ref.stop()
      recoverEvents() shouldEqual events.toList
    }

    "replay events in the same order" in {
      val ref = PersistenceRef()
      val events = (1 to 100).toVector map { _.toString }
      for {
        group <- events.grouped(10)
      } {
        val events = Nel.unsafe(group)
        ref.persist(events)
      }

      ref.stop()

      recoverEvents() shouldEqual events
    }

    "replay events in the same order when half is deleted" in {
      val ref = PersistenceRef()
      val events = (1 to 100).toVector map { _.toString }
      for {
        group <- events.grouped(10)
      } {
        val events = Nel.unsafe(group)
        ref.persist(events)
      }

      val deleteTo = 50
      ref.delete(deleteTo.toLong)
      ref.stop()

      recoverEvents() shouldEqual events.drop(deleteTo)
    }

    "recover new entity from lengthy topic" ignore {
      val ref = PersistenceRef()
      val events = (1 to 1000).toVector map { _.toString }
      for {
        group <- events.grouped(10)
      } {
        val events = Nel.unsafe(group)
        ref.persist(events)
      }
      val state = recoverEvents("new_id")
      state shouldEqual Nil
      recoverEvents("new_id") shouldEqual Nil
    }

    "recover 100000 events" ignore {
      val n = 100000
      val ref = PersistenceRef()
      val batchSize = 100
      val events = (1 to batchSize).toList.map(_.toString)
      for {
        _ <- 1 to (n / batchSize)
      } {
        val batch = Nel(events.head, events.tail)
        ref.persist(batch)
      }

      val count = recover(0, timeout.duration * 10) { case (s, _) => s + 1 }
      count shouldEqual n
    }
  }


  trait PersistenceRef {
    def persist(events: Nel[String]): Unit
    def delete(seqNr: Long): Unit
    def stop(): Unit
  }

  object PersistenceRef {

    def apply(id: String = pid): PersistenceRef = {

      def actor() = new PersistentActor {

        def persistenceId: String = id

        def receiveRecover = PartialFunction.empty

        def receiveCommand: Receive = {
          case Stop                     => context.stop(self)
          case Delete(seqNr)            => deleteMessages(seqNr)
          case x: DeleteMessagesSuccess => testActor.tell(x, self)
          case x: DeleteMessagesFailure => testActor.tell(x, self)
          case Cmd(events)              =>
            val sender = this.sender()
            val last = events.last
            persistAll(events.toList) { event =>
              if (event == last) sender.tell(event, self)
            }
        }
      }

      val props = Props(actor())
      val ref = system.actorOf(props)

      new PersistenceRef {
        def persist(events: Nel[String]) = {
          val cmd = Cmd(events)
          ref.tell(cmd, testActor)
          expectMsg(events.last)
        }

        def delete(seqNr: Long) = {
          val delete = Delete(seqNr)
          ref.tell(delete, testActor)
          expectMsg(DeleteMessagesSuccess(seqNr))
        }

        def stop() = {
          ConsistencySpec.this.stop(ref)
        }
      }
    }
  }


  def recover[S](s: S, timeout: FiniteDuration, id: String = pid)(f: (S, String) => S): S = {
    val promise = Promise[S]()

    var state = s

    def actor() = new PersistentActor {

      def persistenceId: String = id

      def receiveRecover = {
        case event: String     => state = f(state, event)
        case RecoveryCompleted => promise.success(state)
      }

      def receiveCommand: Receive = PartialFunction.empty

      override def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
        promise.failure(cause)
        super.onRecoveryFailure(cause, event)
      }
    }

    val props = Props(actor())
    system.actorOf(props)
    val future = promise.future
    Await.result(future, timeout)
  }

  def recoverEvents(id: String = pid): List[String] = {
    val events = recover(List.empty[String], timeout.duration, id) { (s, e) => e :: s }
    events.reverse
  }


  def stop(ref: ActorRef) = {
    watch(ref)
    ref.tell(Stop, testActor)
    expectTerminated(ref)
  }


  case class Cmd(events: Nel[String])
  case class Delete(seqNr: Long)
  case object Stop
}
