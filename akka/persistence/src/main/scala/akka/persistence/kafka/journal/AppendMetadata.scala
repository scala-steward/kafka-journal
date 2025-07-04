package akka.persistence.kafka.journal

import com.evolution.kafka.journal.{Headers, RecordMetadata}

final case class AppendMetadata(metadata: RecordMetadata = RecordMetadata.empty, headers: Headers = Headers.empty)

object AppendMetadata {
  val empty: AppendMetadata = AppendMetadata()
}
