package com.evolution.kafka.journal.cassandra

import com.evolution.kafka.journal.PayloadType
import com.evolution.kafka.journal.PayloadType.Binary
import com.evolutiongaming.scassandra.{DecodeByName, EncodeByName}

object PayloadTypeExtension {
  implicit val encodeByNamePayloadType: EncodeByName[PayloadType] = EncodeByName[String].contramap { _.name }

  implicit val decodeByNamePayloadType: DecodeByName[PayloadType] = DecodeByName[String].map { name =>
    PayloadType(name) getOrElse Binary
  }
}
