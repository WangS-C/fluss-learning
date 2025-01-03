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

package com.alibaba.fluss.server.log;

import com.alibaba.fluss.annotation.VisibleForTesting;
import com.alibaba.fluss.config.ConfigOptions;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.exception.FlussRuntimeException;
import com.alibaba.fluss.exception.LogStorageException;
import com.alibaba.fluss.metadata.LogFormat;
import com.alibaba.fluss.metadata.PhysicalTablePath;
import com.alibaba.fluss.metadata.TableBucket;
import com.alibaba.fluss.metadata.TableDescriptor;
import com.alibaba.fluss.metadata.TablePath;
import com.alibaba.fluss.server.TabletManagerBase;
import com.alibaba.fluss.server.log.checkpoint.OffsetCheckpointFile;
import com.alibaba.fluss.server.zk.ZooKeeperClient;
import com.alibaba.fluss.utils.FileUtils;
import com.alibaba.fluss.utils.FlussPaths;
import com.alibaba.fluss.utils.clock.Clock;
import com.alibaba.fluss.utils.concurrent.Scheduler;
import com.alibaba.fluss.utils.types.Tuple2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import static com.alibaba.fluss.utils.concurrent.LockUtils.inLock;

/* This file is based on source code of Apache Kafka Project (https://kafka.apache.org/), licensed by the Apache
 * Software Foundation (ASF) under the Apache License, Version 2.0. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. */

/**
 * The entry point to the fluss log management subsystem. The log manager is responsible for log
 * creation, retrieval, and cleaning. All read and write operations are delegated to the individual
 * log instances.
 */
// LogStore旨在存储日志数据，功能类似于数据库 binlog。消息只能追加，不能修改，从而确保数据完整性。
// 其主要目的是实现低延迟流式读取，并用作恢复 KvStore 的预写日志 (WAL )。
@ThreadSafe
public final class LogManager extends TabletManagerBase {
    private static final Logger LOG = LoggerFactory.getLogger(LogManager.class);

    @VisibleForTesting
    static final String RECOVERY_POINT_CHECKPOINT_FILE = "recovery-point-offset-checkpoint";

    private final ZooKeeperClient zkClient;
    private final Scheduler scheduler;
    private final Clock clock;
    private final ReentrantLock logCreationOrDeletionLock = new ReentrantLock();

    private final Map<TableBucket, LogTablet> currentLogs = new ConcurrentHashMap<>();

    private volatile OffsetCheckpointFile recoveryPointCheckpoint;

    private LogManager(
            File dataDir,
            Configuration conf,
            ZooKeeperClient zkClient,
            int recoveryThreadsPerDataDir,
            Scheduler scheduler,
            Clock clock)
            throws Exception {
        super(TabletType.LOG, dataDir, conf, recoveryThreadsPerDataDir);
        this.zkClient = zkClient;
        this.scheduler = scheduler;
        this.clock = clock;
        // 创建并验证数据目录
        createAndValidateDataDir(dataDir);

        // 初始化检查点地图
        initializeCheckpointMaps();
    }

    public static LogManager create(
            Configuration conf, ZooKeeperClient zkClient, Scheduler scheduler, Clock clock)
            throws Exception {
        String dataDirString = conf.getString(ConfigOptions.DATA_DIR);
        File dataDir = new File(dataDirString).getAbsoluteFile();
        return new LogManager(
                dataDir,
                conf,
                zkClient,
                // 服务器用于处理请求的线程数
                conf.getInt(ConfigOptions.NETTY_SERVER_NUM_WORKER_THREADS),
                scheduler,
                clock);
    }

    public void startup() {
        // 恢复并加载给定数据目录中的所有日志。
        loadLogs();

        // TODO add more scheduler, like log-flusher etc.
        // 添加更多调度程序，例如日志刷新器等。
    }

    public File getDataDir() {
        return dataDir;
    }

    private void initializeCheckpointMaps() throws IOException {
        recoveryPointCheckpoint =
                new OffsetCheckpointFile(new File(dataDir, RECOVERY_POINT_CHECKPOINT_FILE));
    }

    /** Recover and load all logs in the given data directories. */
    // 恢复并加载给定数据目录中的所有日志。
    private void loadLogs() {
        LOG.info("Loading logs from dir {}", dataDir);

        String dataDirAbsolutePath = dataDir.getAbsolutePath();
        try {
            // 恢复点
            Map<TableBucket, Long> recoveryPoints = new HashMap<>();
            try {
                recoveryPoints = recoveryPointCheckpoint.read();
            } catch (Exception e) {
                LOG.warn(
                        "Error occurred while reading recovery-point-offset-checkpoint file of directory {}, resetting the recovery checkpoint to 0",
                        dataDirAbsolutePath,
                        e);
            }

            // 返回要加载的tablets的目录
            List<File> tabletsToLoad = listTabletsToLoad();
            if (tabletsToLoad.isEmpty()) {
                LOG.info("No logs found to be loaded in {}", dataDirAbsolutePath);
            }

            final Map<TableBucket, Long> finalRecoveryPoints = recoveryPoints;
            // set runnable job.
            // 设置可运行的作业。
            Runnable[] jobsForDir =
                    tabletsToLoad.stream()
                            .map(
                                    tabletDir ->
                                            (Runnable)
                                                    () -> {
                                                        LOG.debug("Loading log {}", tabletDir);
                                                        try {
                                                            // 加载日志
                                                            loadLog(
                                                                    tabletDir,
                                                                    finalRecoveryPoints,
                                                                    conf,
                                                                    clock);
                                                        } catch (Exception e) {
                                                            throw new FlussRuntimeException(e);
                                                        }
                                                    })
                            .toArray(Runnable[]::new);

            long startTime = System.currentTimeMillis();

            // 在线程池中运行loadLog方法，并返回成功作业的计数。
            int successLoadCount =
                    runInThreadPool(jobsForDir, "log-recovery-" + dataDirAbsolutePath);

            LOG.info(
                    "log loader complete. Total success loaded log count is {}, Take {} ms",
                    successLoadCount,
                    System.currentTimeMillis() - startTime);
        } catch (Throwable e) {
            throw new FlussRuntimeException("Failed to recovery log", e);
        }
    }

    /**
     * Get or create log tablet for a given bucket of a table. If the log already exists, just
     * return a copy of the existing log. Otherwise, create a log for the given table and the given
     * bucket.
     *
     * @param tablePath the table path of the bucket belongs to
     * @param tableBucket the table bucket
     * @param logFormat the log format
     * @param tieredLogLocalSegments the number of segments to retain in local for tiered log
     * @param isChangelog whether the log is a changelog of primary key table
     */
    // 获取或创建表的给定存储桶的日志表。
    // 如果日志已经存在，则仅返回现有日志的副本。否则，为给定表和给定存储桶创建日志
    public LogTablet getOrCreateLog(
            PhysicalTablePath tablePath,
            TableBucket tableBucket,
            LogFormat logFormat,
            int tieredLogLocalSegments,
            boolean isChangelog)
            throws Exception {
        return inLock(
                logCreationOrDeletionLock,
                () -> {
                    if (currentLogs.containsKey(tableBucket)) {
                        return currentLogs.get(tableBucket);
                    }

                    File tabletDir = getOrCreateTabletDir(tablePath, tableBucket);

                    LogTablet logTablet =
                            LogTablet.create(
                                    tablePath,
                                    tabletDir,
                                    conf,
                                    0L,
                                    scheduler,
                                    logFormat,
                                    tieredLogLocalSegments,
                                    isChangelog,
                                    clock);
                    currentLogs.put(tableBucket, logTablet);

                    LOG.info(
                            "Loaded log for bucket {} in dir {}",
                            tableBucket,
                            tabletDir.getAbsolutePath());

                    return logTablet;
                });
    }

    public Optional<LogTablet> getLog(TableBucket tableBucket) {
        return Optional.ofNullable(currentLogs.get(tableBucket));
    }

    public void dropLog(TableBucket tableBucket) {
        LogTablet dropLogTablet =
                inLock(logCreationOrDeletionLock, () -> currentLogs.remove(tableBucket));

        if (dropLogTablet != null) {
            TablePath tablePath = dropLogTablet.getTablePath();
            try {
                dropLogTablet.drop();
                if (dropLogTablet.getPartitionName() == null) {
                    LOG.info(
                            "Deleted log bucket {} for table {} in file path {}.",
                            tableBucket.getBucket(),
                            tablePath,
                            dropLogTablet.getLogDir().getAbsolutePath());
                } else {
                    LOG.info(
                            "Deleted log bucket {} for the partition {} of table {} in file path {}.",
                            tableBucket.getBucket(),
                            dropLogTablet.getPartitionName(),
                            tablePath,
                            dropLogTablet.getLogDir().getAbsolutePath());
                }
            } catch (Exception e) {
                throw new LogStorageException(
                        String.format(
                                "Error while deleting log for table %s, bucket %s in dir %s: %s",
                                tablePath,
                                tableBucket.getBucket(),
                                dropLogTablet.getLogDir().getAbsolutePath(),
                                e.getMessage()),
                        e);
            }
        } else {
            throw new LogStorageException(
                    String.format(
                            "Failed to delete log bucket %s as it does not exist.",
                            tableBucket.getBucket()));
        }
    }

    /**
     * Truncate the bucket's logs to the specified offsets and checkpoint the recovery point to this
     * offset.
     */
    // 将存储桶的日志截断到指定的偏移量，并将恢复点检查点到该偏移量
    public void truncateTo(TableBucket tableBucket, long offset) throws LogStorageException {
        LogTablet logTablet = currentLogs.get(tableBucket);
        // If the log tablet does not exist, skip it.
        // 如果日志片不存在，则跳过。
        if (logTablet != null && logTablet.truncateTo(offset)) {
            checkpointRecoveryOffsets();
        }
    }

    public void truncateFullyAndStartAt(TableBucket tableBucket, long newOffset) {
        LogTablet logTablet = currentLogs.get(tableBucket);
        // If the log tablet does not exist, skip it.
        // 如果日志片不存在，则跳过。
        if (logTablet != null) {
            logTablet.truncateFullyAndStartAt(newOffset);
            checkpointRecoveryOffsets();
        }
    }

    private LogTablet loadLog(
            File tabletDir, Map<TableBucket, Long> recoveryPoints, Configuration conf, Clock clock)
            throws Exception {
        // 从给定的 (log/ kv) tablet目录解析表路径、可选分区名称和存储桶id。
        Tuple2<PhysicalTablePath, TableBucket> pathAndBucket = FlussPaths.parseTabletDir(tabletDir);
        TableBucket tableBucket = pathAndBucket.f1;
        // 获取日志恢复点
        long logRecoveryPoint = recoveryPoints.getOrDefault(tableBucket, 0L);

        PhysicalTablePath physicalTablePath = pathAndBucket.f0;
        TablePath tablePath = physicalTablePath.getTablePath();
        TableDescriptor tableDescriptor =
                // 获取表描述符和schema 目前是从zk获取 后续会存储至磁盘 【FLUSS-58283612】
                getTableDescriptor(zkClient, tablePath, tableBucket, tabletDir);
        LogTablet logTablet =
                LogTablet.create(
                        physicalTablePath,
                        tabletDir,
                        conf,
                        logRecoveryPoint,
                        scheduler,
                        tableDescriptor.getLogFormat(),
                        tableDescriptor.getTieredLogLocalSegments(),
                        tableDescriptor.hasPrimaryKey(),
                        clock);

        if (currentLogs.containsKey(tableBucket)) {
            throw new IllegalStateException(
                    String.format(
                            "Duplicate log tablet directories for bucket %s are found in both %s and %s. "
                                    + "It is likely because tablet directory failure happened while server was "
                                    + "replacing current replica with future replica. Recover server from this "
                                    + "failure by manually deleting one of the two log directories for this bucket. "
                                    + "It is recommended to delete the bucket in the log tablet directory that is "
                                    + "known to have failed recently.",
                            tableBucket,
                            tabletDir.getAbsolutePath(),
                            currentLogs.get(tableBucket).getLogDir().getAbsolutePath()));
        }
        currentLogs.put(tableBucket, logTablet);

        return logTablet;
    }

    private void createAndValidateDataDir(File dataDir) {
        try {
            inLock(
                    logCreationOrDeletionLock,
                    () -> {
                        if (!dataDir.exists()) {
                            LOG.info(
                                    "Data directory {} not found, creating it.",
                                    dataDir.getAbsolutePath());
                            boolean created = dataDir.mkdirs();
                            if (!created) {
                                throw new IOException(
                                        "Failed to create data directory "
                                                + dataDir.getAbsolutePath());
                            }
                            Path parentPath =
                                    dataDir.toPath().toAbsolutePath().normalize().getParent();
                            FileUtils.flushDir(parentPath);
                        }
                        if (!dataDir.isDirectory() || !dataDir.canRead()) {
                            throw new IOException(
                                    dataDir.getAbsolutePath()
                                            + " is not a readable data directory.");
                        }
                    });
        } catch (IOException e) {
            throw new FlussRuntimeException(
                    "Failed to create or validate data directory " + dataDir.getAbsolutePath(), e);
        }
    }

    /** Close all the logs. */
    // 关闭所有日志。
    public void shutdown() {
        LOG.info("Shutting down LogManager.");

        ExecutorService pool = createThreadPool("log-tablet-closing-" + dataDir.getAbsolutePath());

        List<LogTablet> logs = new ArrayList<>(currentLogs.values());
        List<Future<?>> jobsForTabletDir = new ArrayList<>();
        for (LogTablet logTablet : logs) {
            Runnable runnable =
                    () -> {
                        try {
                            logTablet.flush(true);
                            logTablet.close();
                        } catch (IOException e) {
                            throw new FlussRuntimeException(e);
                        }
                    };
            jobsForTabletDir.add(pool.submit(runnable));
        }

        try {
            for (Future<?> future : jobsForTabletDir) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    LOG.warn("Interrupted while shutting down LogManager.");
                } catch (ExecutionException e) {
                    LOG.warn(
                            "There was an error in one of the threads during LogManager shutdown",
                            e);
                }
            }

            // update the last flush point.
            // 更新最后的刷新点。
            checkpointRecoveryOffsets();

            // TODO add clean shutdown logic.
        } finally {
            pool.shutdown();
        }

        LOG.info("Shut down LogManager complete.");
    }

    @VisibleForTesting
    void checkpointRecoveryOffsets() {
        // Assuming TableBucket and LogTablet are actual types used in your application
        // 假设 TableBucket 和 LogTablet 是您的应用程序中使用的实际类型
        if (recoveryPointCheckpoint != null) {
            try {
                Map<TableBucket, Long> recoveryOffsets = new HashMap<>();
                for (Map.Entry<TableBucket, LogTablet> entry : currentLogs.entrySet()) {
                    recoveryOffsets.put(entry.getKey(), entry.getValue().getRecoveryPoint());
                }
                recoveryPointCheckpoint.write(recoveryOffsets);
            } catch (Exception e) {
                throw new LogStorageException(
                        "Disk error while writing recovery offsets checkpoint in directory "
                                + dataDir
                                + ": "
                                + e.getMessage(),
                        e);
            }
        }
    }
}
