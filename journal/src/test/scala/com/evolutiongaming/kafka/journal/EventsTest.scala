package com.evolutiongaming.kafka.journal

import cats.data.NonEmptyList as Nel
import cats.syntax.all.*
import com.evolutiongaming.kafka.journal.Event.*
import com.evolutiongaming.kafka.journal.util.ScodecHelper.{nelCodec, *}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scodec.*
import scodec.bits.ByteVector

import scala.util.Try

class EventsTest extends AnyFunSuite with Matchers {

  test("decode newer version") {
    implicit val jsonCodec: JsonCodec[Try] = JsonCodec.jsoniter[Try]
    val codec = {
      val eventsCodec =
        nelCodec(codecs.listOfN(codecs.int32, codecs.variableSizeBytes(codecs.int32, Event.codecEventPayload)))
      val version = ByteVector.fromByte(100)
      (codecs.constant(version) ~> eventsCodec)
        .xmap[Events[Payload]](a => Events(a, PayloadMetadata.empty), _.events)
    }

    val events = Events(Nel.of(Event(SeqNr.min, payload = Payload.text("text").some)), PayloadMetadata.empty)
    val actual = for {
      bits <- codec.encode(events)
      result <- Events.codecEvents[Payload].decode(bits)
    } yield {
      result.value
    }
    actual shouldEqual events.pure[Attempt]
  }
}
