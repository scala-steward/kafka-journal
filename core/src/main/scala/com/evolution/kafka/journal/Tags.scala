package com.evolution.kafka.journal

import scodec.*

private[journal] object Tags {

  val empty: Tags = Set.empty

  implicit val codecTags: Codec[Tags] = {
    val codec = codecs.list(codecs.utf8_32).xmap[Tags](_.toSet, _.toList)
    codecs.variableSizeBytes(codecs.int32, codec)
  }

  def apply(tag: Tag, tags: Tag*): Tags = tags.toSet[Tag] + tag
}
