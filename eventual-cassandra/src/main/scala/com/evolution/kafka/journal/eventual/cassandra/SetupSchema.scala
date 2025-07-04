package com.evolution.kafka.journal.eventual.cassandra

import cats.data.NonEmptyList as Nel
import cats.effect.kernel.Temporal
import cats.syntax.all.*
import cats.{MonadThrow, Parallel}
import com.evolution.kafka.journal.cassandra.MigrateSchema.Fresh
import com.evolution.kafka.journal.cassandra.{
  CassandraConsistencyConfig,
  CassandraSync,
  MigrateSchema,
  SettingsCassandra,
}
import com.evolution.kafka.journal.{Origin, Settings}
import com.evolutiongaming.catshelper.LogOf

/**
 * Creates a new schema, or migrates to the latest schema version, if it already exists.
 *
 * Migration is done by checking [[SettingKey]] in `setting` table:
 *   - read the `version` (`value` in table), which represents number of applied migration
 *     statements:
 *     - `0` means first migration has been done
 *     - `1` states that 2 migrations have been applied
 *     - etc
 *   - depending on value of `version`, applies all, some or none of migrations from
 *     [[SetupSchema.migrations]]
 *
 * Migrations are done by [[MigrateSchema]] by restricting concurrent changes using
 * [[CassandraSync]].
 */
private[journal] object SetupSchema {

  val SettingKey = "schema-version"

  def migrations(schema: Schema): Nel[String] = {

    def addHeaders: String =
      JournalStatements.addHeaders(schema.journal)

    def addVersion: String =
      JournalStatements.addVersion(schema.journal)

    def dropMetadata =
      s"DROP TABLE IF EXISTS metadata"

    def createPointer2: String =
      Pointer2Statements.createTable(schema.pointer2)

    def journalAddMetaRecordId: String =
      JournalStatements.addMetaRecordId(schema.journal)

    def metaAddRecordId: String =
      MetaJournalStatements.addRecordId(schema.metaJournal)

    Nel.of(addHeaders, addVersion, dropMetadata, createPointer2, journalAddMetaRecordId, metaAddRecordId)

  }

  private[cassandra] def migrate[F[_]: MonadThrow: CassandraSession](
    schema: Schema,
    fresh: MigrateSchema.Fresh,
    settings: Settings[F],
    cassandraSync: CassandraSync[F],
  ): F[Unit] = {
    val migrateSchema = MigrateSchema.forSettingKey(
      cassandraSync = cassandraSync,
      settings = settings,
      settingKey = SettingKey,
      migrations = migrations(schema),
    )
    migrateSchema.run(fresh)
  }

  def apply[F[_]: Temporal: Parallel: CassandraCluster: CassandraSession: LogOf](
    config: SchemaConfig,
    origin: Option[Origin],
    consistencyConfig: CassandraConsistencyConfig,
  ): F[Schema] = {

    def createSchema(
      implicit
      cassandraSync: CassandraSync[F],
    ): F[(Schema, Fresh)] = CreateSchema(config)

    for {
      cassandraSync <- CassandraSync.of[F](config.keyspace, config.locksTable, origin)
      ab <- createSchema(cassandraSync)
      (schema, fresh) = ab
      settings <- SettingsCassandra.of[F](schema.setting, origin, consistencyConfig)
      _ <- migrate(schema, fresh, settings, cassandraSync)
    } yield schema
  }

}
