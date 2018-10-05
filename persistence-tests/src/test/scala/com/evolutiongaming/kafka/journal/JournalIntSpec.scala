package com.evolutiongaming.kafka.journal

import java.time.Instant
import java.util.UUID

import com.evolutiongaming.cassandra.CreateCluster
import com.evolutiongaming.concurrent.async.Async
import com.evolutiongaming.kafka.journal.FoldWhileHelper.Switch
import com.evolutiongaming.kafka.journal.eventual.cassandra.{EventualCassandra, EventualCassandraConfig}
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.skafka.producer.Producer
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.Await
import scala.concurrent.duration._

class JournalIntSpec extends WordSpec with ActorSpec with Matchers {

  implicit lazy val ec = system.dispatcher

  lazy val log = ActorLog(system, getClass)

  val timeout = 30.seconds

  val origin = Origin("JournalIntSpec")

  lazy val (journal, cassandra) = {
    val conf = system.settings.config.getConfig("evolutiongaming.kafka-journal.persistence.journal")
    val (eventual, cassandra) = {
      val config = EventualCassandraConfig(conf.getConfig("cassandra"))
      val cassandra = CreateCluster(config.client)
      val session = Await.result(cassandra.connect(), timeout)
      val eventual = EventualCassandra(session, config, Log.empty(Async.unit))
      (eventual, cassandra)
    }

    val journalConfig = JournalConfig(conf)
    val ecBlocking = system.dispatchers.lookup("evolutiongaming.kafka-journal.persistence.journal.blocking-dispatcher")
    val producer = Producer(journalConfig.producer, ecBlocking)
    val topicConsumer = TopicConsumer(journalConfig.consumer, ecBlocking)
    val journal = Journal(producer, Some(origin), topicConsumer, eventual)

    (journal, cassandra)
  }

  override def beforeAll() = {
    super.beforeAll()
    IntegrationSuit.start()
    journal
  }

  override def afterAll() = {
    Safe {
      Await.result(cassandra.close(), timeout)
    }
    super.afterAll()
  }

  "Journal" should {

    "append, delete, read, lastSeqNr" in {
      val key = Key(id = UUID.randomUUID().toString, topic = "journal")

      val timestamp = Instant.now()

      def read() = {
        journal.read[List[Event]](key, SeqNr.Min, Nil) { (xs, x) => Switch.continue(x :: xs) }.get(timeout)
      }

      def lastSeqNr() = journal.lastSeqNr(key, SeqNr.Min).get(timeout)

      def delete() = journal.delete(key, SeqNr.Max, timestamp).get(timeout)

      lastSeqNr() shouldEqual None
      read() shouldEqual Nil
      delete() shouldEqual None
      val event = Event(SeqNr.Min)
      val partition = journal.append(key, Nel(event), timestamp).get(timeout).partition
      read() shouldEqual List(event)
      delete().map(_.partition) shouldEqual Some(partition)
      lastSeqNr() shouldEqual Some(SeqNr.Min)
      read() shouldEqual Nil
    }
  }
}