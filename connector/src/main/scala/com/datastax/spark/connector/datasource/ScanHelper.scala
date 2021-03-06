package com.datastax.spark.connector.datasource

import java.io.IOException

import com.datastax.oss.driver.api.core.CqlIdentifier._
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata
import com.datastax.oss.driver.api.core.{ConsistencyLevel, CqlSession}
import com.datastax.spark.connector.cql.{CassandraConnector, ColumnDef, ScanResult, Scanner, TableDef}
import com.datastax.spark.connector.rdd.CassandraLimit.limitToClause
import com.datastax.spark.connector.rdd.partitioner.dht.{Token, TokenFactory}
import com.datastax.spark.connector.rdd.partitioner.{CassandraPartitionGenerator, CqlTokenRange, DataSizeEstimates}
import com.datastax.spark.connector.rdd.{CassandraLimit, ClusteringOrder, CqlWhereClause}
import com.datastax.spark.connector.types.ColumnType
import com.datastax.spark.connector.util.CqlWhereParser.{EqPredicate, InListPredicate, InPredicate, Predicate, RangePredicate}
import com.datastax.spark.connector.util.Quote.quote
import com.datastax.spark.connector.util.{CqlWhereParser, Logging}
import com.datastax.spark.connector.{ColumnName, ColumnRef, TTL, WriteTime}

import scala.collection.JavaConverters._

object ScanHelper extends Logging {


  def checkColumnsExistence(columns: Seq[ColumnRef], tableDef: TableDef): Seq[ColumnRef] = {
    val allColumnNames = tableDef.columns.map(_.columnName).toSet
    val regularColumnNames = tableDef.regularColumns.map(_.columnName).toSet
    val keyspaceName = tableDef.keyspaceName
    val tableName = tableDef.tableName

    def checkSingleColumn(column: ColumnRef) = {
      column match {
        case ColumnName(columnName, _) =>
          if (!allColumnNames.contains(columnName))
            throw new IOException(s"Column $column not found in table $keyspaceName.$tableName")
        case TTL(columnName, _) =>
          if (!regularColumnNames.contains(columnName))
            throw new IOException(s"TTL can be obtained only for regular columns, " +
              s"but column $columnName is not a regular column in table $keyspaceName.$tableName.")
        case WriteTime(columnName, _) =>
          if (!regularColumnNames.contains(columnName))
            throw new IOException(s"TTL can be obtained only for regular columns, " +
              s"but column $columnName is not a regular column in table $keyspaceName.$tableName.")
        case _ =>
      }

      column
    }

    columns.map(checkSingleColumn)
  }


  //We call the types from the void
  type V = t forSome {type t}
  type T = t forSome {type t <: Token[V]}

  def fetchTokenRange(scanner: Scanner,
                      tableMetadata: TableMetadata,
                      queryParts: CqlQueryParts,
                      range: CqlTokenRange[_, _],
                      consistencyLevel: ConsistencyLevel,
                      fetchSize: Int): ScanResult = {

    val session = scanner.getSession()

    val (cql, values) = tokenRangeToCqlQuery(range, tableMetadata, queryParts)

    logDebug(
      s"Fetching data for range ${range.cql(partitionKeyStr(tableMetadata))} " +
        s"with $cql " +
        s"with params ${values.mkString("[", ",", "]")}")

    val stmt = prepareScanStatement(session, cql, values: _*)
      .setConsistencyLevel(consistencyLevel)
      .setPageSize(fetchSize)
      .setRoutingToken(range.range.startNativeToken())

    val scanResult = scanner.scan(stmt)
    logDebug(s"Row iterator for range ${range.cql(partitionKeyStr(tableMetadata))} obtained successfully.")

    scanResult
  }

  def partitionKeyStr(tableMetadata: TableMetadata) = {
    TableDef.partitionKey(tableMetadata).map(ColumnDef.columnName(_)).map(quote).mkString(", ")
  }

  def tokenRangeToCqlQuery(range: CqlTokenRange[_, _],
                           tableMetadata: TableMetadata,
                           cqlQueryParts: CqlQueryParts): (String, Seq[Any]) = {

    val columns = cqlQueryParts.selectedColumnRefs.map(_.cql).mkString(", ")

    val (cql, values) = if (containsPartitionKey(tableMetadata, cqlQueryParts.whereClause)) {
      ("", Seq.empty)
    } else {
      range.cql(partitionKeyStr(tableMetadata))
    }
    val filter = (cql +: cqlQueryParts.whereClause.predicates).filter(_.nonEmpty).mkString(" AND ")
    val limitClause = limitToClause(cqlQueryParts.limitClause)
    val orderBy = cqlQueryParts.clusteringOrder.map(_.toCql(tableMetadata)).getOrElse("")
    val keyspaceName = fromInternal(TableDef.keyspaceName(tableMetadata))
    val tableName = fromInternal(TableDef.tableName(tableMetadata))
    val queryTemplate =
      s"SELECT $columns " +
        s"FROM ${keyspaceName.asCql(true)}.${tableName.asCql(true)} " +
        s"WHERE $filter $orderBy $limitClause ALLOW FILTERING"
    val queryParamValues = values ++ cqlQueryParts.whereClause.values
    (queryTemplate, queryParamValues)
  }

  def containsPartitionKey(tableMetadata: TableMetadata, clause: CqlWhereClause): Boolean = {
    val pk = TableDef.partitionKey(tableMetadata).map(ColumnDef.columnName(_)).toSet
    val wherePredicates: Seq[Predicate] = clause.predicates.flatMap(CqlWhereParser.parse)

    val whereColumns: Set[String] = wherePredicates.collect {
      case EqPredicate(c, _) if pk.contains(c) => c
      case InPredicate(c) if pk.contains(c) => c
      case InListPredicate(c, _) if pk.contains(c) => c
      case RangePredicate(c, _, _) if pk.contains(c) =>
        throw new UnsupportedOperationException(
          s"Range predicates on partition key columns (here: $c) are " +
            s"not supported in where. Use filter instead.")
    }.toSet

    val primaryKeyComplete = whereColumns.nonEmpty && whereColumns.size == pk.size
    val whereColumnsAllIndexed = whereColumns.forall(TableDef.isIndexed(_)(tableMetadata))

    if (!primaryKeyComplete && !whereColumnsAllIndexed) {
      val missing = pk -- whereColumns
      throw new UnsupportedOperationException(
        s"Partition key predicate must include all partition key columns or partition key columns need" +
          s" to be indexed. Missing columns: ${missing.mkString(",")}"
      )
    }
    primaryKeyComplete
  }

  def prepareScanStatement(session: CqlSession, cql: String, values: Any*): BoundStatement = {
    try {
      val stmt = session.prepare(cql)
      val converters = stmt.getVariableDefinitions.asScala
        .map(v => ColumnType.converterToCassandra(v.getType))
        .toArray
      val convertedValues =
        for ((value, converter) <- values zip converters)
          yield converter.convert(value)
      stmt.bind(convertedValues: _*)
        .setIdempotent(true)
    }
    catch {
      case t: Throwable =>
        throw new IOException(s"Exception during preparation of $cql: ${t.getMessage}", t)
    }
  }

  def getPartitionGenerator(connector: CassandraConnector,
                            tableMetadata: TableMetadata,
                            whereClause: CqlWhereClause,
                            minSplitCount: Int,
                            partitionCount: Option[Int],
                            splitSize: Long): CassandraPartitionGenerator[V, T] = {

    implicit val tokenFactory = TokenFactory.forSystemLocalPartitioner(connector)
    implicit val table = tableMetadata

    if (containsPartitionKey(tableMetadata, whereClause)) {
      CassandraPartitionGenerator(connector, tableMetadata, 1)
    } else {
      partitionCount match {
        case Some(splitCount) => {
          CassandraPartitionGenerator(connector, tableMetadata, splitCount)
        }
        case None => {
          val estimateDataSize = new DataSizeEstimates(connector, TableDef.keyspaceName, TableDef.tableName).dataSizeInBytes
          val splitCount = if (estimateDataSize == Long.MaxValue || estimateDataSize < 0) {
            logWarning(
              s"""Size Estimates has overflowed and calculated that the data size is Infinite.
                 |Falling back to $minSplitCount (2 * SparkCores + 1) Split Count.
                 |This is most likely occurring because you are reading size_estimates
                 |from a DataCenter which has very small primary ranges. Explicitly set
                 |the splitCount when reading to manually adjust this.""".stripMargin)
            minSplitCount
          } else {
            val splitCountEstimate = estimateDataSize / splitSize
            Math.max(splitCountEstimate.toInt, Math.max(minSplitCount, 1))
          }
          CassandraPartitionGenerator(connector, tableMetadata, splitCount)
        }
      }
    }
  }

  case class CqlQueryParts(
    selectedColumnRefs: IndexedSeq[ColumnRef],
    whereClause: CqlWhereClause = CqlWhereClause.empty,
    limitClause: Option[CassandraLimit] = None,
    clusteringOrder: Option[ClusteringOrder] = None)

}
