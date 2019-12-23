package com.evolutiongaming.kafka.journal

import cats.implicits._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import com.evolutiongaming.kafka.journal.ExpireAfter.implicits._
import com.evolutiongaming.kafka.journal.util.PlayJsonHelper._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.util.Try


class ActionHeaderJsonSpec extends AnyFunSuite with Matchers {

  val origins = List(Origin("origin").some, none)
  val metadata = List(
    ("metadata", RecordMetadata(data = Json.obj(("key", "value")).some)),
    ("none"    , RecordMetadata(data = none)),
    ("legacy"  , RecordMetadata(data = none)))
  val payloadTypes = List(PayloadType.Binary, PayloadType.Json)

  for {
    origin <- origins
  } {
    val originStr = origin.fold("None")(_.toString)
    val expireAfter = origin.fold { 1.day.toExpireAfter.some } { _ => none[ExpireAfter] }
    for {
      payloadType             <- payloadTypes
      (metadataStr, metadata) <- metadata
    } {
      test(s"Append format, origin: $origin, payloadType: $payloadType, metadata: $metadataStr") {
        val range = SeqRange.unsafe(1, 5)
        val header = ActionHeader.Append(
          range = range,
          origin = origin,
          payloadType = payloadType,
          metadata = metadata,
          expireAfter = expireAfter)
        verify(header, s"Append-$originStr-$payloadType-$metadataStr")
      }
    }

    test(s"Delete format, origin: $origin") {
      val seqNr = SeqNr.unsafe(3)
      val header = ActionHeader.Delete(seqNr, origin)
      verify(header, s"Delete-$originStr")
    }

    test(s"Purge format, origin: $origin") {
      val header = ActionHeader.Purge(origin)
      verify(header, s"Purge-$originStr")
    }

    test(s"Mark format, origin: $origin") {
      val header = ActionHeader.Mark("id", origin)
      verify(header, s"Mark-$originStr")
    }
  }

  test("not supported ActionHeader") {
    val json = Json.obj(("new", Json.obj()))
    json.validate[Option[ActionHeader]] shouldEqual none[ActionHeader].pure[JsResult]
  }

  private def verify(value: ActionHeader, name: String) = {

    def verify(json: JsValue) = {
      val actual = json.validate[Option[ActionHeader]]
      actual shouldEqual value.some.pure[JsResult]
    }

    verify(Json.toJson(value))

    val json = for {
      byteVector <- ByteVectorOf[Try](getClass, s"$name.json")
      json       <- Try { Json.parse(byteVector.toArray) }
    } yield json

    verify(json.get)
  }
}
