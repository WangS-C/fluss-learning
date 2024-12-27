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

import com.alibaba.fluss.config.ConfigOptions;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.exception.LogSegmentOffsetOverflowException;
import com.alibaba.fluss.exception.LogStorageException;
import com.alibaba.fluss.metadata.LogFormat;
import com.alibaba.fluss.utils.FlussPaths;
import com.alibaba.fluss.utils.types.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

/* This file is based on source code of Apache Kafka Project (https://kafka.apache.org/), licensed by the Apache
 * Software Foundation (ASF) under the Apache License, Version 2.0. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. */

/**
 * Loader to load log segments.
 */
final class LogLoader {
    private static final Logger LOG = LoggerFactory.getLogger(LogLoader.class);

    private final File logTabletDir;
    private final Configuration conf;
    private final LogSegments logSegments;
    private final long recoveryPointCheckpoint;
    private final LogFormat logFormat;
    private final WriterStateManager writerStateManager;

    public LogLoader(
            File logTabletDir,
            Configuration conf,
            LogSegments logSegments,
            long recoveryPointCheckpoint,
            LogFormat logFormat,
            WriterStateManager writerStateManager) {
        this.logTabletDir = logTabletDir;
        this.conf = conf;
        this.logSegments = logSegments;
        this.recoveryPointCheckpoint = recoveryPointCheckpoint;
        this.logFormat = logFormat;
        this.writerStateManager = writerStateManager;
    }

    /**
     * Load the log segments from the log files on disk, and returns the components of the loaded
     * log.
     *
     * <p>In the context of the calling thread, this function does not need to convert IOException
     * to {@link LogStorageException} because it is only called before all logs are loaded.
     *
     * @return the offsets of the Log successfully loaded from disk
     */
    // 从磁盘上的日志文件加载日志段，并返回已加载日志的组件。
    //在调用线程的上下文中，此函数不需要将IOException转换为LogStorageException ，因为它仅在加载所有日志之前被调用。
    public LoadedLogOffsets load() throws IOException {
        // load all the log and index files.
        // 加载所有日志和索引文件。
        logSegments.close();
        logSegments.clear();
        // 将磁盘中的段加载到logSegments中
        loadSegmentFiles();
        long newRecoveryPoint;
        long nextOffset;
        // 恢复日志段
        Tuple2<Long, Long> result = recoverLog();
        newRecoveryPoint = result.f0;
        nextOffset = result.f1;

        // Any segment loading or recovery code must not use writerStateManager, so that we can
        // build the full state here from scratch.
        // 任何段加载或恢复代码都不能使用writerStateManager，这样我们就可以从头开始构建完整的状态。
        // 在日志初始化期间，写入器状态必须为空
        if (!writerStateManager.isEmpty()) {
            throw new IllegalStateException("Writer state must be empty during log initialization");
        }

        // Reload all snapshots into the WriterStateManager cache, the intermediate
        // WriterStateManager used during log recovery may have deleted some files without the
        // LogLoader.writerStateManager instance witnessing the deletion.
        // 将所有快照重新加载到WriterStateManager缓存中，日志恢复期间使用的中间WriterStateManager可能已经删除了一些文件，
        // 而LogLoader.writerStateManager实例没有见证删除。
        writerStateManager.removeStraySnapshots(logSegments.baseOffsets());
        // TODO get the clean shutdown info from LogManager.
        // 从LogManager获取干净的关机信息。
        // 重建写入器状态
        LogTablet.rebuildWriterState(writerStateManager, logSegments, 0, nextOffset, true);

        LogSegment activeSegment = logSegments.lastSegment().get();
        activeSegment.resizeIndexes((int) conf.get(ConfigOptions.LOG_INDEX_FILE_SIZE).getBytes());
        return new LoadedLogOffsets(
                newRecoveryPoint,
                new LogOffsetMetadata(
                        nextOffset, activeSegment.getBaseOffset(), activeSegment.getSizeInBytes()));
    }

    /**
     * Recover the log segments (if there was an unclean shutdown). Ensures there is at least one
     * active segment, and returns the updated recovery point and next offset after recovery.
     *
     * <p>This method does not need to convert IOException to {@link LogStorageException} because it
     * is only called before all logs are loaded.
     *
     * @return a tuple containing (newRecoveryPoint, nextOffset).
     * @throws LogSegmentOffsetOverflowException if we encountered a legacy segment with offset
     *                                           overflow
     */
    // 恢复日志段 (如果有不干净的关机)。确保至少有一个活动段，并在恢复后返回更新的恢复点和下一个偏移。
    //此方法不需要将IOException转换为LogStorageException ，因为它仅在加载所有日志之前调用。
    private Tuple2<Long, Long> recoverLog() throws IOException {
        // TODO truncate log to recover maybe unflush segments.
        // 截断日志以恢复可能取消刷新的段。
        if (logSegments.isEmpty()) {
            logSegments.add(
                    //创建LogSegment
                    LogSegment.open(logTabletDir, 0L, conf, logFormat));
        }
        //获取最后Segment的下一条消息的偏移量
        long logEndOffset = logSegments.lastSegment().get().readNextOffset();
        return Tuple2.of(recoveryPointCheckpoint, logEndOffset);
    }

    /**
     * Loads segments from disk into the provided segments.
     */
    // 将磁盘中的段加载到提供的段中。
    private void loadSegmentFiles() throws IOException {
        File[] sortedFiles = logTabletDir.listFiles();
        if (sortedFiles != null) {
            Arrays.sort(sortedFiles, Comparator.comparing(File::getName));
            for (File file : sortedFiles) {
                if (file.isFile()) {
                    // 是索引文件
                    if (LocalLog.isIndexFile(file)) {
                        long offset = FlussPaths.offsetFromFile(file);
                        File logFile = FlussPaths.logFile(logTabletDir, offset);
                        if (!logFile.exists()) {
                            LOG.warn(
                                    "Found an orphaned index file {}, with no corresponding log file.",
                                    file.getAbsolutePath());
                            Files.deleteIfExists(file.toPath());
                        }
                        // 是日志文件
                    } else if (LocalLog.isLogFile(file)) {
                        long baseOffset = FlussPaths.offsetFromFile(file);
                        LogSegment segment =
                                LogSegment.open(logTabletDir, baseOffset, conf, true, 0, logFormat);
                        logSegments.add(segment);
                    }
                }
            }
        }
    }
}
