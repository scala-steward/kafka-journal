package com.evolution.kafka.journal.cassandra

import com.datastax.driver.core.{GettableByNameData, SettableData}
import com.evolution.kafka.journal.PartitionOffset
import com.evolution.kafka.journal.cassandra.SkafkaHelperExtension.*
import com.evolutiongaming.scassandra.syntax.*
import com.evolutiongaming.scassandra.{DecodeRow, EncodeRow}
import com.evolutiongaming.skafka.{Offset, Partition}

object PartitionOffsetExtension {
  implicit val encodeRowPartitionOffset: EncodeRow[PartitionOffset] = new EncodeRow[PartitionOffset] {

    def apply[B <: SettableData[B]](data: B, value: PartitionOffset): B = {
      data
        .encode("partition", value.partition)
        .encode("offset", value.offset)
    }
  }

  implicit val decodeRowPartitionOffset: DecodeRow[PartitionOffset] = (data: GettableByNameData) => {
    PartitionOffset(partition = data.decode[Partition]("partition"), offset = data.decode[Offset]("offset"))
  }
}
