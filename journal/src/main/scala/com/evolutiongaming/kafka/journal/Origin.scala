package com.evolutiongaming.kafka.journal

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId}
import com.evolutiongaming.cassandra.{Decode, Encode}
import com.evolutiongaming.hostname
import play.api.libs.json._

case class Origin(value: String) extends AnyVal {

  override def toString = value
}

object Origin {

  implicit val FormatImpl: Format[Origin] = new Format[Origin] {
    def reads(json: JsValue) = json.validate[String].map(Origin(_))
    def writes(origin: Origin) = JsString(origin.value)
  }

  implicit val EncodeImpl: Encode[Origin] = Encode[String].imap((b: Origin) => b.value)

  implicit val DecodeImpl: Decode[Origin] = Decode[String].map(value => Origin(value))

  implicit val EncodeOptImpl: Encode[Option[Origin]] = Encode.opt[Origin]

  implicit val DecodeOptImpl: Decode[Option[Origin]] = Decode.opt[Origin]

  val Empty: Origin = Origin("")

  val HostName: Option[Origin] = {
    hostname.HostName() map { hostname => Origin(hostname) }
  }

  object AkkaHost {

    private case class Ext(origin: Option[Origin]) extends Extension

    private object Ext extends ExtensionId[Ext] {

      def createExtension(system: ExtendedActorSystem): Ext = {
        val address = system.provider.getDefaultAddress
        val origin = for {
          host <- address.host
          port <- address.port
        } yield Origin(s"$host:$port")
        Ext(origin)
      }
    }

    def apply(system: ActorSystem): Option[Origin] = {
      Ext(system).origin
    }
  }

  object AkkaName {
    def apply(system: ActorSystem): Origin = Origin(system.name)
  }
}