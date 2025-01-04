/*
 * Copyright (c) 2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.client.admin;

import com.alibaba.fluss.annotation.PublicEvolving;
import com.alibaba.fluss.client.table.lake.LakeTableSnapshotInfo;
import com.alibaba.fluss.client.table.snapshot.KvSnapshotInfo;
import com.alibaba.fluss.client.table.snapshot.PartitionSnapshotInfo;
import com.alibaba.fluss.exception.DatabaseAlreadyExistException;
import com.alibaba.fluss.exception.DatabaseNotEmptyException;
import com.alibaba.fluss.exception.DatabaseNotExistException;
import com.alibaba.fluss.exception.InvalidDatabaseException;
import com.alibaba.fluss.exception.InvalidReplicationFactorException;
import com.alibaba.fluss.exception.InvalidTableException;
import com.alibaba.fluss.exception.PartitionNotExistException;
import com.alibaba.fluss.exception.SchemaNotExistException;
import com.alibaba.fluss.exception.TableAlreadyExistException;
import com.alibaba.fluss.exception.TableNotExistException;
import com.alibaba.fluss.exception.TableNotPartitionedException;
import com.alibaba.fluss.lakehouse.LakeStorageInfo;
import com.alibaba.fluss.metadata.PartitionInfo;
import com.alibaba.fluss.metadata.PhysicalTablePath;
import com.alibaba.fluss.metadata.SchemaInfo;
import com.alibaba.fluss.metadata.TableDescriptor;
import com.alibaba.fluss.metadata.TableInfo;
import com.alibaba.fluss.metadata.TablePath;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The administrative client for Fluss, which supports managing and inspecting tables, servers,
 * configurations and ACLs.
 *
 * @since 0.1
 */
@PublicEvolving
public interface Admin extends AutoCloseable {
    /**
     * Get the latest table schema of the given table asynchronously.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link TableNotExistException} if the table does not exist.
     * </ul>
     *
     * @param tablePath the table path of the table.
     */
    CompletableFuture<SchemaInfo> getTableSchema(TablePath tablePath);

    /**
     * Get the specific table schema of the given table by schema id asynchronously.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link TableNotExistException} if the table does not exist.
     *   <li>{@link SchemaNotExistException} if the schema does not exist.
     * </ul>
     *
     * @param tablePath the table path of the table.
     */
    CompletableFuture<SchemaInfo> getTableSchema(TablePath tablePath, int schemaId);

    /**
     * Create a new database asynchronously.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link DatabaseAlreadyExistException} if the database already exists and {@code
     *       ignoreIfExists} is false.
     * </ul>
     *
     * @param databaseName The name of the database to create.
     * @param ignoreIfExists Flag to specify behavior when a database with the given name already
     *     exists: if set to false, throw a DatabaseAlreadyExistException, if set to true, do
     *     nothing.
     * @throws InvalidDatabaseException if the database name is invalid, e.g., contains illegal
     *     characters, or exceeds the maximum length.
     */
    // 异步创建新数据库
    // 在对返回的 future 调用get()时，可能会出现以下异常。
    // DatabaseAlreadyExistException（如果数据库已存在，且ignoreIfExists为 false）。
    // 参数
    // databaseName- 要创建的数据库名称。
    // ignoreIfExists- 当给定名称的数据库已经存在时指定行为的标志：
    // 如果设置为 false，则抛出 DatabaseAlreadyExistException；
    // 如果设置为 true，则什么也不做。
    CompletableFuture<Void> createDatabase(String databaseName, boolean ignoreIfExists)
            throws InvalidDatabaseException;

    /**
     * Delete the database with the given name asynchronously.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link DatabaseNotExistException} if the database does not exist and {@code
     *       ignoreIfNotExists} is false.
     *   <li>{@link DatabaseNotEmptyException} if the database is not empty and {@code cascade} is
     *       false.
     * </ul>
     *
     * @param databaseName The name of the database to delete.
     * @param ignoreIfNotExists Flag to specify behavior when a database with the given name does
     *     not exist: if set to false, throw a DatabaseNotExistException, if set to true, do
     *     nothing.
     * @param cascade Flag to specify whether to delete all tables in the database.
     */
    // 异步删除指定名称的数据库。
    // 在对返回的 future 调用get()时，可能会出现以下异常。
    // 如果数据库不存在，且ignoreIfNotExists为 false，则会出现DatabaseNotExistException。
    // 如果数据库不是空的，且cascade为 false，则会出现DatabaseNotEmptyException。
    // 参数
    // databaseName- 要删除的数据库名称。
    // ignoreIfNotExists- 当不存在给定名称的数据库时，用于指定行为的标志：
    // 如果设置为 false，则抛出 DatabaseNotExistException；
    // 如果设置为 true，则什么也不做。
    // cascade- 用于指定是否删除数据库中所有表的标志。
    CompletableFuture<Void> deleteDatabase(
            String databaseName, boolean ignoreIfNotExists, boolean cascade);

    /**
     * Get whether database exists asynchronously.
     *
     * @param databaseName The name of the database to check.
     */
    // 异步获取数据库是否存在
    CompletableFuture<Boolean> databaseExists(String databaseName);

    /** List all databases in fluss cluster asynchronously. */
    // 异步列出 fluss 集群中的所有数据库。
    CompletableFuture<List<String>> listDatabases();

    /**
     * Create a new table asynchronously.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link DatabaseNotExistException} if the database in the table path does not exist.
     *   <li>{@link TableAlreadyExistException} if the table already exists and {@code
     *       ignoreIfExists} is false.
     *   <li>{@link InvalidReplicationFactorException} if the table's replication factor is larger
     *       than the number of available tablet servers.
     * </ul>
     *
     * @param tablePath The tablePath of the table.
     * @param tableDescriptor The table to create.
     * @throws InvalidTableException if the table name is invalid, e.g., contains illegal
     *     characters, or exceeds the maximum length.
     * @throws InvalidDatabaseException if the database name is invalid, e.g., contains illegal
     *     characters, or exceeds the maximum length.
     */
    // 异步创建新表
    // 在对返回的 future 调用get()时，可能会出现以下异常。
    // 如果表路径中的数据库不存在，则会出现DatabaseNotExistException 异常。
    // 如果表已经存在，且ignoreIfExists为 false，则会出现TableAlreadyExistException异常。
    // 如果表的复制因子大于可用平板服务器的数量，则会出现InvalidReplicationFactorException 异常。
    // 参数：
    // tablePath- 表的表路径。
    // tableDescriptor- 要创建的表。
    // 抛出
    // InvalidTableException- 如果表名无效，例如包含非法字符或超过最大长度。
    // InvalidDatabaseException- 如果数据库名称无效，例如包含非法字符或超过最大长度
    CompletableFuture<Void> createTable(
            TablePath tablePath, TableDescriptor tableDescriptor, boolean ignoreIfExists)
            throws InvalidTableException, InvalidDatabaseException;

    /**
     * Get the table with the given table path asynchronously.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link TableNotExistException} if the table does not exist.
     * </ul>
     *
     * @param tablePath The table path of the table.
     */
    // 使用给定的表格路径异步获取表格。
    // 在对返回的 future 调用get()时，可能会出现以下异常。
    // TableNotExistException（表不存在异常），如果表不存在。
    // 参数
    // tablePath- 表的路径。
    CompletableFuture<TableInfo> getTable(TablePath tablePath);

    /**
     * Delete the table with the given table path asynchronously.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link TableNotExistException} if the table does not exist and {@code
     *       ignoreIfNotExists} is false.
     * </ul>
     *
     * @param tablePath The table path of the table.
     * @param ignoreIfNotExists Flag to specify behavior when a table with the given name does not
     *     exist: if set to false, throw a TableNotExistException, if set to true, do nothing.
     */
    // 使用给定的表路径异步删除表。
    // 在对返回的 future 调用get()时，可能会出现以下异常。
    // 如果表不存在且ignoreIfNotExists为 false，则会出现TableNotExistException异常。
    // 参数
    // tablePath- 表的路径。
    // ignoreIfNotExists- 当指定名称的表不存在时，用于指定行为的标志：
    // 如果设置为 false，则抛出 TableNotExistException；
    // 如果设置为 true，则什么也不做。
    CompletableFuture<Void> deleteTable(TablePath tablePath, boolean ignoreIfNotExists);

    /**
     * Get whether table exists asynchronously.
     *
     * @param tablePath The table path of the table.
     */
    // 异步获取表格是否存在。
    // 参数：
    // tablePath- 表的路径。
    CompletableFuture<Boolean> tableExists(TablePath tablePath);

    /**
     * List all tables in the given database in fluss cluster asynchronously.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link DatabaseNotExistException} if the database does not exist.
     * </ul>
     *
     * @param databaseName The name of the database.
     */
    // 异步列出 fluss 集群中给定数据库的所有表。
    // 在对返回的 future 调用get()时，可能会出现以下异常。
    // 如果数据库不存在，则会出现DatabaseNotExistException 异常。
    // 参数
    // databaseName- 数据库名称。
    CompletableFuture<List<String>> listTables(String databaseName);

    /**
     * List all partitions in the given table in fluss cluster asynchronously.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link TableNotExistException} if the table does not exist.
     *   <li>{@link TableNotPartitionedException} if the table is not partitioned.
     * </ul>
     *
     * @param tablePath The path of the table.
     */
    CompletableFuture<List<PartitionInfo>> listPartitionInfos(TablePath tablePath);

    /**
     * Get table kv snapshot info of the given table asynchronously.
     *
     * <p>It'll get the latest snapshot for all the buckets of the table.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link TableNotExistException} if the table does not exist.
     * </ul>
     *
     * @param tablePath the table path of the table.
     */
    CompletableFuture<KvSnapshotInfo> getKvSnapshot(TablePath tablePath);

    /**
     * Get table lake snapshot info of the given table asynchronously.
     *
     * <p>It'll get the latest snapshot for all the buckets of the table.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link TableNotExistException} if the table does not exist.
     * </ul>
     *
     * @param tablePath the table path of the table.
     */
    CompletableFuture<LakeTableSnapshotInfo> getLakeTableSnapshot(TablePath tablePath);

    /**
     * List offset for the specified buckets. This operation enables to find the beginning offset,
     * end offset as well as the offset matching a timestamp in buckets.
     *
     * @param physicalTablePath the physical table path of the buckets.
     * @param buckets the buckets to fetch offset.
     * @param offsetSpec the offset spec to fetch.
     */
    ListOffsetsResult listOffsets(
            PhysicalTablePath physicalTablePath,
            Collection<Integer> buckets,
            OffsetSpec offsetSpec);

    /**
     * Get a partition's snapshot info of the given partition in the given table asynchronously.
     *
     * <p>It'll get the latest snapshot for the given partition of the table.
     *
     * <p>The following exceptions can be anticipated when calling {@code get()} on returned future.
     *
     * <ul>
     *   <li>{@link TableNotExistException} if the table does not exist.
     *   <li>{@link TableNotPartitionedException} if the table is not partitioned.
     *   <li>{@link PartitionNotExistException} if the given partition does not exist.
     * </ul>
     *
     * @param tablePath the table path of the table.
     */
    CompletableFuture<PartitionSnapshotInfo> getPartitionSnapshot(
            TablePath tablePath, String partitionName);

    /** Describe the lake used for lakehouse storage. */
    CompletableFuture<LakeStorageInfo> describeLakeStorage();
}
