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

package com.alibaba.fluss.client.table.writer;

import com.alibaba.fluss.annotation.PublicEvolving;
import com.alibaba.fluss.client.metadata.MetadataUpdater;
import com.alibaba.fluss.client.table.getter.BucketKeyGetter;
import com.alibaba.fluss.client.write.WriteKind;
import com.alibaba.fluss.client.write.WriteRecord;
import com.alibaba.fluss.client.write.WriterClient;
import com.alibaba.fluss.metadata.TableDescriptor;
import com.alibaba.fluss.metadata.TablePath;
import com.alibaba.fluss.row.InternalRow;

import javax.annotation.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The writer to write data to the log table.
 *
 * @since 0.2
 */
// 向日志表写入数据的写入器。
@PublicEvolving
public class AppendWriter extends TableWriter {
    private final @Nullable BucketKeyGetter bucketKeyGetter;

    public AppendWriter(
            TablePath tablePath,
            TableDescriptor tableDescriptor,
            MetadataUpdater metadataUpdater,
            WriterClient writerClient) {
        super(tablePath, tableDescriptor, metadataUpdater, writerClient);
        List<String> bucketKeys = tableDescriptor.getBucketKey();
        this.bucketKeyGetter =
                bucketKeys.isEmpty()
                        ? null
                        : new BucketKeyGetter(tableDescriptor.getSchema().toRowType(), bucketKeys);
    }

    /**
     * Append row into Fluss non-pk table.
     *
     * @param row the row to append.
     * @return A {@link CompletableFuture} that always returns null when complete normally.
     */
    // 将记录添加到 Fluss non-pk 表中。
    // 参数
    // row- 要追加的行
    public CompletableFuture<Void> append(InternalRow row) {
        byte[] bucketKey = bucketKeyGetter != null ? bucketKeyGetter.getBucketKey(row) : null;
        return send(new WriteRecord(getPhysicalPath(row), WriteKind.APPEND, row, bucketKey));
    }
}
