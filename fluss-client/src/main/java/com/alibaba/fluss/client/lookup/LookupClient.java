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

package com.alibaba.fluss.client.lookup;

import com.alibaba.fluss.annotation.Internal;
import com.alibaba.fluss.client.metadata.MetadataUpdater;
import com.alibaba.fluss.config.ConfigOptions;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.metadata.TableBucket;
import com.alibaba.fluss.utils.concurrent.ExecutorThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A client that lookups value of keys from server.
 *
 * <p>The lookup client contains of a queue of pending lookup operations and background I/O threads
 * that is responsible for turning these lookup operations into network requests and transmitting
 * them to the cluster.
 *
 * <p>The {@link #lookup(TableBucket, byte[])} method is asynchronous, when called, it adds the
 * lookup operation to a queue of pending lookup operations and immediately returns. This allows the
 * lookup operations to batch together individual lookup operations for efficiency.
 */
// 从服务器查找键值的客户端。
// 查找客户端包含一个待处理查找操作队列和后台 I/ O 线程，后者负责将这些查找操作转化为网络请求并传输到群集。
// lookup(TableBucket, byte[])方法是异步的，调用时会将查找操作添加到待处理查找操作队列中，然后立即返回。
// 这样，查找操作就能批量处理单个查找操作，从而提高效率。
@ThreadSafe
@Internal
public class LookupClient {

    private static final Logger LOG = LoggerFactory.getLogger(LookupClient.class);

    public static final String LOOKUP_THREAD_PREFIX = "fluss-lookup-sender";

    private final LookupQueue lookupQueue;

    private final ExecutorService lookupSenderThreadPool;
    private final LookupSender lookupSender;

    public LookupClient(Configuration conf, MetadataUpdater metadataUpdater) {
        this.lookupQueue = new LookupQueue(conf);
        this.lookupSenderThreadPool = createThreadPool();
        this.lookupSender =
                new LookupSender(
                        metadataUpdater,
                        lookupQueue,
                        conf.getInt(ConfigOptions.CLIENT_LOOKUP_MAX_INFLIGHT_SIZE));
        lookupSenderThreadPool.submit(lookupSender);
    }

    private ExecutorService createThreadPool() {
        // according to benchmark, increase the thread pool size improve not so much
        // performance, so we always use 1 thread for simplicity.
        // 根据基准测试，增加线程池的大小对性能的提升不大，因此为了简单起见，我们始终使用 1 个线程。
        return Executors.newFixedThreadPool(1, new ExecutorThreadFactory(LOOKUP_THREAD_PREFIX));
    }

    public CompletableFuture<byte[]> lookup(TableBucket tableBucket, byte[] keyBytes) {
        Lookup lookup = new Lookup(tableBucket, keyBytes);
        lookupQueue.appendLookup(lookup);
        return lookup.future();
    }

    public void close(Duration timeout) {
        LOG.info("Closing lookup client and lookup sender.");

        if (lookupSender != null) {
            lookupSender.initiateClose();
        }

        if (lookupSenderThreadPool != null) {
            lookupSenderThreadPool.shutdown();
            try {
                if (lookupSenderThreadPool.awaitTermination(
                        timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    lookupSenderThreadPool.shutdownNow();

                    if (!lookupSenderThreadPool.awaitTermination(
                            timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        LOG.error("Failed to shutdown lookup client.");
                    }
                }
            } catch (InterruptedException e) {
                lookupSenderThreadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (lookupSender != null) {
            lookupSender.forceClose();
        }
        LOG.info("Lookup client closed.");
    }
}
