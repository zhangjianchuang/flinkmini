/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.api.bridge.scala

import org.apache.flink.annotation.PublicEvolving
import org.apache.flink.api.common.JobExecutionResult
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.table.api.bridge.scala.internal.StreamTableEnvironmentImpl
import org.apache.flink.table.api.{TableEnvironment, _}
import org.apache.flink.table.descriptors.{ConnectorDescriptor, StreamTableDescriptor}
import org.apache.flink.table.expressions.Expression
import org.apache.flink.table.functions.{AggregateFunction, TableAggregateFunction, TableFunction}

/**
  * This table environment is the entry point and central context for creating Table and SQL
  * API programs that integrate with the Scala-specific [[DataStream]] API.
  *
  * It is unified for bounded and unbounded data processing.
  *
  * A stream table environment is responsible for:
  *
  * - Convert a [[DataStream]] into [[Table]] and vice-versa.
  * - Connecting to external systems.
  * - Registering and retrieving [[Table]]s and other meta objects from a catalog.
  * - Executing SQL statements.
  * - Offering further configuration options.
  *
  * Note: If you don't intend to use the [[DataStream]] API, [[TableEnvironment]] is meant for pure
  * table programs.
  */
@PublicEvolving
trait StreamTableEnvironment extends TableEnvironment {

  /**
    * Registers a [[TableFunction]] under a unique name in the TableEnvironment's catalog.
    * Registered functions can be referenced in SQL queries.
    *
    * @param name The name under which the function is registered.
    * @param tf The TableFunction to register
    *
    * @deprecated Use [[createTemporarySystemFunction(String, UserDefinedFunction)]] instead. Please
    *             note that the new method also uses the new type system and reflective extraction
    *             logic. It might be necessary to update the function implementation as well. See
    *             the documentation of [[TableFunction]] for more information on the new function
    *             design.
    */
  @deprecated
  def registerFunction[T: TypeInformation](name: String, tf: TableFunction[T]): Unit

  /**
    * Registers an [[AggregateFunction]] under a unique name in the TableEnvironment's catalog.
    * Registered functions can be referenced in Table API and SQL queries.
    *
    * @param name The name under which the function is registered.
    * @param f The AggregateFunction to register.
    * @tparam T The type of the output value.
    * @tparam ACC The type of aggregate accumulator.
    */
  def registerFunction[T: TypeInformation, ACC: TypeInformation](
    name: String,
    f: AggregateFunction[T, ACC]): Unit

  /**
    * Registers an [[TableAggregateFunction]] under a unique name in the TableEnvironment's catalog.
    * Registered functions can only be referenced in Table API.
    *
    * @param name The name under which the function is registered.
    * @param f The TableAggregateFunction to register.
    * @tparam T The type of the output value.
    * @tparam ACC The type of aggregate accumulator.
    */
  def registerFunction[T: TypeInformation, ACC: TypeInformation](
    name: String,
    f: TableAggregateFunction[T, ACC]): Unit

  /**
    * Converts the given [[DataStream]] into a [[Table]].
    *
    * The field names of the [[Table]] are automatically derived from the type of the
    * [[DataStream]].
    *
    * @param dataStream The [[DataStream]] to be converted.
    * @tparam T The type of the [[DataStream]].
    * @return The converted [[Table]].
    */
  def fromDataStream[T](dataStream: DataStream[T]): Table

  /**
    * Converts the given [[DataStream]] into a [[Table]] with specified field names.
    *
    * There are two modes for mapping original fields to the fields of the [[Table]]:
    *
    * 1. Reference input fields by name:
    * All fields in the schema definition are referenced by name
    * (and possibly renamed using an alias (as). Moreover, we can define proctime and rowtime
    * attributes at arbitrary positions using arbitrary names (except those that exist in the
    * result schema). In this mode, fields can be reordered and projected out. This mode can
    * be used for any input type, including POJOs.
    *
    * Example:
    *
    * {{{
    *   val stream: DataStream[(String, Long)] = ...
    *   val table: Table = tableEnv.fromDataStream(
    *      stream,
    *      $"_2", // reorder and use the original field
    *      $"rowtime".rowtime, // extract the internally attached timestamp into an event-time
    *                          // attribute named 'rowtime'
    *      $"_1" as "name" // reorder and give the original field a better name
    *   )
    * }}}
    *
    * <p>2. Reference input fields by position:
    * In this mode, fields are simply renamed. Event-time attributes can
    * replace the field on their position in the input data (if it is of correct type) or be
    * appended at the end. Proctime attributes must be appended at the end. This mode can only be
    * used if the input type has a defined field order (tuple, case class, Row) and none of
    * the `fields` references a field of the input type.
    *
    * Example:
    *
    * {{{
    *   val stream: DataStream[(String, Long)] = ...
    *   val table: Table = tableEnv.fromDataStream(
    *      stream,
    *      $"a", // rename the first field to 'a'
    *      $"b" // rename the second field to 'b'
    *      $"rowtime".rowtime // extract the internally attached timestamp
    *                         // into an event-time attribute named 'rowtime'
    *   )
    * }}}
    *
    * @param dataStream The [[DataStream]] to be converted.
    * @param fields The fields expressions to map original fields of the DataStream to the fields of
    *               the [[Table]].
    * @tparam T The type of the [[DataStream]].
    * @return The converted [[Table]].
    */
  def fromDataStream[T](dataStream: DataStream[T], fields: Expression*): Table

  /**
    * Creates a view from the given [[DataStream]].
    * Registered views can be referenced in SQL queries.
    *
    * The field names of the [[Table]] are automatically derived
    * from the type of the [[DataStream]].
    *
    * The view is registered in the namespace of the current catalog and database. To register the
    * view in a different catalog use [[createTemporaryView]].
    *
    * Temporary objects can shadow permanent ones. If a permanent object in a given path exists,
    * it will be inaccessible in the current session. To make the permanent object available again
    * you can drop the corresponding temporary object.
    *
    * @param name The name under which the [[DataStream]] is registered in the catalog.
    * @param dataStream The [[DataStream]] to register.
    * @tparam T The type of the [[DataStream]] to register.
    * @deprecated use [[createTemporaryView]]
    */
  @deprecated
  def registerDataStream[T](name: String, dataStream: DataStream[T]): Unit

  /**
    * Creates a view from the given [[DataStream]] in a given path.
    * Registered tables can be referenced in SQL queries.
    *
    * The field names of the [[Table]] are automatically derived
    * from the type of the [[DataStream]].
    *
    * Temporary objects can shadow permanent ones. If a permanent object in a given path exists,
    * it will be inaccessible in the current session. To make the permanent object available again
    * you can drop the corresponding temporary object.
    *
    * @param path The path under which the [[DataStream]] is created.
    *             See also the [[TableEnvironment]] class description for the format of the path.
    * @param dataStream The [[DataStream]] out of which to create the view.
    * @tparam T The type of the [[DataStream]].
    */
  def createTemporaryView[T](path: String, dataStream: DataStream[T]): Unit

  /**
    * Creates a view from the given [[DataStream]] in a given path with specified field names.
    * Registered views can be referenced in SQL queries.
    *
    * There are two modes for mapping original fields to the fields of the View:
    *
    * 1. Reference input fields by name:
    * All fields in the schema definition are referenced by name
    * (and possibly renamed using an alias (as). Moreover, we can define proctime and rowtime
    * attributes at arbitrary positions using arbitrary names (except those that exist in the
    * result schema). In this mode, fields can be reordered and projected out. This mode can
    * be used for any input type, including POJOs.
    *
    * Example:
    *
    * {{{
    *   val stream: DataStream[(String, Long)] = ...
    *   tableEnv.registerDataStream(
    *      "myTable",
    *      stream,
    *      $"_2", // reorder and use the original field
    *      $"rowtime".rowtime, // extract the internally attached timestamp into an event-time
    *                          // attribute named 'rowtime'
    *      $"_1" as "name" // reorder and give the original field a better name
    *   )
    * }}}
    *
    * 2. Reference input fields by position:
    * In this mode, fields are simply renamed. Event-time attributes can
    * replace the field on their position in the input data (if it is of correct type) or be
    * appended at the end. Proctime attributes must be appended at the end. This mode can only be
    * used if the input type has a defined field order (tuple, case class, Row) and none of
    * the `fields` references a field of the input type.
    *
    * Example:
    *
    * {{{
    *   val stream: DataStream[(String, Long)] = ...
    *   tableEnv.registerDataStream(
    *      "myTable",
    *      stream,
    *      $"a", // rename the first field to 'a'
    *      $"b" // rename the second field to 'b'
    *      $"rowtime".rowtime // adds an event-time attribute named 'rowtime'
    *   )
    * }}}
    *
    * The view is registered in the namespace of the current catalog and database. To register the
    * view in a different catalog use [[createTemporaryView]].
    *
    * Temporary objects can shadow permanent ones. If a permanent object in a given path exists,
    * it will be inaccessible in the current session. To make the permanent object available again
    * you can drop the corresponding temporary object.
    *
    * @param name The name under which the [[DataStream]] is registered in the catalog.
    * @param dataStream The [[DataStream]] to register.
    * @param fields The fields expressions to map original fields of the DataStream to the fields of
    *               the View.
    * @tparam T The type of the [[DataStream]] to register.
    * @deprecated use [[createTemporaryView]]
    */
  @deprecated
  def registerDataStream[T](name: String, dataStream: DataStream[T], fields: Expression*): Unit

  /**
    * Creates a view from the given [[DataStream]] in a given path with specified field names.
    * Registered views can be referenced in SQL queries.
    *
    * There are two modes for mapping original fields to the fields of the View:
    *
    * 1. Reference input fields by name:
    * All fields in the schema definition are referenced by name
    * (and possibly renamed using an alias (as). Moreover, we can define proctime and rowtime
    * attributes at arbitrary positions using arbitrary names (except those that exist in the
    * result schema). In this mode, fields can be reordered and projected out. This mode can
    * be used for any input type, including POJOs.
    *
    * Example:
    *
    * {{{
    *   val stream: DataStream[(String, Long)] = ...
    *   tableEnv.createTemporaryView(
    *      "cat.db.myTable",
    *      stream,
    *      $"_2", // reorder and use the original field
    *      $"rowtime".rowtime, // extract the internally attached timestamp into an event-time
    *                          // attribute named 'rowtime'
    *      $"_1" as "name" // reorder and give the original field a better name
    *   )
    * }}}
    *
    * 2. Reference input fields by position:
    * In this mode, fields are simply renamed. Event-time attributes can
    * replace the field on their position in the input data (if it is of correct type) or be
    * appended at the end. Proctime attributes must be appended at the end. This mode can only be
    * used if the input type has a defined field order (tuple, case class, Row) and none of
    * the `fields` references a field of the input type.
    *
    * Example:
    *
    * {{{
    *   val stream: DataStream[(String, Long)] = ...
    *   tableEnv.createTemporaryView(
    *      "cat.db.myTable",
    *      stream,
    *      $"a", // rename the first field to 'a'
    *      $"b" // rename the second field to 'b'
    *      $"rowtime".rowtime // adds an event-time attribute named 'rowtime'
    *   )
    * }}}
    *
    * Temporary objects can shadow permanent ones. If a permanent object in a given path exists,
    * it will be inaccessible in the current session. To make the permanent object available again
    * you can drop the corresponding temporary object.
    *
    * @param path The path under which the [[DataStream]] is created.
    *             See also the [[TableEnvironment]] class description for the format of the path.
    * @param dataStream The [[DataStream]] out of which to create the view.
    * @param fields The fields expressions to map original fields of the DataStream to the fields of
    *               the View.
    * @tparam T The type of the [[DataStream]].
    */
  def createTemporaryView[T](path: String, dataStream: DataStream[T], fields: Expression*): Unit

  /**
    * Converts the given [[Table]] into an append [[DataStream]] of a specified type.
    *
    * The [[Table]] must only have insert (append) changes. If the [[Table]] is also modified
    * by update or delete changes, the conversion will fail.
    *
    * The fields of the [[Table]] are mapped to [[DataStream]] fields as follows:
    * - [[org.apache.flink.types.Row]] and Scala Tuple types: Fields are mapped by position, field
    * types must match.
    * - POJO [[DataStream]] types: Fields are mapped by field name, field types must match.
    *
    * @param table The [[Table]] to convert.
    * @tparam T The type of the resulting [[DataStream]].
    * @return The converted [[DataStream]].
    */
  def toAppendStream[T: TypeInformation](table: Table): DataStream[T]

  /**
    * Converts the given [[Table]] into a [[DataStream]] of add and retract messages.
    * The message will be encoded as [[Tuple2]]. The first field is a [[Boolean]] flag,
    * the second field holds the record of the specified type [[T]].
    *
    * A true [[Boolean]] flag indicates an add message, a false flag indicates a retract message.
    *
    * @param table The [[Table]] to convert.
    * @tparam T The type of the requested data type.
    * @return The converted [[DataStream]].
    */
  def toRetractStream[T: TypeInformation](table: Table): DataStream[(Boolean, T)]

  /**
    * Triggers the program execution. The environment will execute all parts of
    * the program.
    *
    * The program execution will be logged and displayed with the provided name
    *
    * It calls the StreamExecutionEnvironment#execute on the underlying
    * [[StreamExecutionEnvironment]]. In contrast to the [[TableEnvironment]] this
    * environment translates queries eagerly.
    *
    * @param jobName Desired name of the job
    * @return The result of the job execution, containing elapsed time and accumulators.
    * @throws Exception which occurs during job execution.
    */
  @throws[Exception]
  override def execute(jobName: String): JobExecutionResult

  /**
   * Creates a table source and/or table sink from a descriptor.
   *
   * Descriptors allow for declaring the communication to external systems in an
   * implementation-agnostic way. The classpath is scanned for suitable table factories that match
   * the desired configuration.
   *
   * The following example shows how to read from a Kafka connector using a JSON format and
   * registering a table source "MyTable" in append mode:
   *
   * {{{
   *
   * tableEnv
   *   .connect(
   *     new Kafka()
   *       .version("0.11")
   *       .topic("clicks")
   *       .property("group.id", "click-group")
   *       .startFromEarliest())
   *   .withFormat(
   *     new Json()
   *       .jsonSchema("{...}")
   *       .failOnMissingField(false))
   *   .withSchema(
   *     new Schema()
   *       .field("user-name", "VARCHAR").from("u_name")
   *       .field("count", "DECIMAL")
   *       .field("proc-time", "TIMESTAMP").proctime())
   *   .inAppendMode()
   *   .createTemporaryTable("MyTable")
   * }}}
   *
   * @param connectorDescriptor connector descriptor describing the external system
   * @deprecated The SQL `CREATE TABLE` DDL is richer than this part of the API.
   *             This method might be refactored in the next versions.
   *             Please use [[executeSql]] to register a table instead.
   */
  @deprecated
  override def connect(connectorDescriptor: ConnectorDescriptor): StreamTableDescriptor
}

object StreamTableEnvironment {

  /**
    * Creates a table environment that is the entry point and central context for creating Table and
    * SQL API programs that integrate with the Scala-specific [[DataStream]] API.
    *
    * It is unified for bounded and unbounded data processing.
    *
    * A stream table environment is responsible for:
    *
    * - Convert a [[DataStream]] into [[Table]] and vice-versa.
    * - Connecting to external systems.
    * - Registering and retrieving [[Table]]s and other meta objects from a catalog.
    * - Executing SQL statements.
    * - Offering further configuration options.
    *
    * Note: If you don't intend to use the [[DataStream]] API, [[TableEnvironment]] is meant for
    * pure table programs.
    *
    * @param executionEnvironment The Scala [[StreamExecutionEnvironment]] of the
    *                             [[TableEnvironment]].
    */
  def create(executionEnvironment: StreamExecutionEnvironment): StreamTableEnvironment = {
    create(
      executionEnvironment,
      EnvironmentSettings.newInstance().build())
  }

  /**
    * Creates a table environment that is the entry point and central context for creating Table and
    * SQL API programs that integrate with the Scala-specific [[DataStream]] API.
    *
    * It is unified for bounded and unbounded data processing.
    *
    * A stream table environment is responsible for:
    *
    * - Convert a [[DataStream]] into [[Table]] and vice-versa.
    * - Connecting to external systems.
    * - Registering and retrieving [[Table]]s and other meta objects from a catalog.
    * - Executing SQL statements.
    * - Offering further configuration options.
    *
    * Note: If you don't intend to use the [[DataStream]] API, [[TableEnvironment]] is meant for
    * pure table programs.
    *
    * @param executionEnvironment The Scala [[StreamExecutionEnvironment]] of the
    *                             [[TableEnvironment]].
    * @param settings The environment settings used to instantiate the [[TableEnvironment]].
    */
  def create(
      executionEnvironment: StreamExecutionEnvironment,
      settings: EnvironmentSettings)
    : StreamTableEnvironment = {
    StreamTableEnvironmentImpl.create(executionEnvironment, settings, new TableConfig)
  }

  /**
    * Creates a table environment that is the entry point and central context for creating Table and
    * SQL API programs that integrate with the Scala-specific [[DataStream]] API.
    *
    * It is unified for bounded and unbounded data processing.
    *
    * A stream table environment is responsible for:
    *
    * - Convert a [[DataStream]] into [[Table]] and vice-versa.
    * - Connecting to external systems.
    * - Registering and retrieving [[Table]]s and other meta objects from a catalog.
    * - Executing SQL statements.
    * - Offering further configuration options.
    *
    * Note: If you don't intend to use the [[DataStream]] API, [[TableEnvironment]] is meant for
    * pure table programs.
    *
    * @param executionEnvironment The Scala [[StreamExecutionEnvironment]] of the
    *                             [[TableEnvironment]].
    * @param tableConfig The configuration of the [[TableEnvironment]].
    * @deprecated Use [[create(StreamExecutionEnvironment)]] and
    *             [[StreamTableEnvironment#getConfig()]] for manipulating the [[TableConfig]].
    */
  @deprecated
  def create(executionEnvironment: StreamExecutionEnvironment, tableConfig: TableConfig)
    : StreamTableEnvironment = {

    StreamTableEnvironmentImpl
      .create(
        executionEnvironment,
        EnvironmentSettings.newInstance().build(),
        tableConfig)
  }
}
