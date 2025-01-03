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

package com.alibaba.fluss.client;

import com.alibaba.fluss.client.admin.Admin;
import com.alibaba.fluss.client.admin.FlussAdmin;
import com.alibaba.fluss.client.lookup.LookupClient;
import com.alibaba.fluss.client.metadata.MetadataUpdater;
import com.alibaba.fluss.client.table.FlussTable;
import com.alibaba.fluss.client.table.Table;
import com.alibaba.fluss.client.write.WriterClient;
import com.alibaba.fluss.config.ConfigOptions;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.metadata.TablePath;
import com.alibaba.fluss.metrics.registry.MetricRegistry;
import com.alibaba.fluss.rpc.RpcClient;
import com.alibaba.fluss.rpc.metrics.ClientMetricGroup;

import java.time.Duration;
import java.util.List;

final class FlussConnection implements Connection {
    private final Configuration conf;
    private final RpcClient rpcClient;
    private final MetadataUpdater metadataUpdater;

    private volatile WriterClient writerClient;
    private volatile LookupClient lookupClient;
    private final MetricRegistry metricRegistry;
    private final ClientMetricGroup clientMetricGroup;

    FlussConnection(Configuration conf) {
        this(conf, MetricRegistry.create(conf, null));
    }

    FlussConnection(Configuration conf, MetricRegistry metricRegistry) {
        this.conf = conf;
        // for client metrics.
        setupClientMetricsConfiguration();
        String clientId = conf.getString(ConfigOptions.CLIENT_ID);
        this.metricRegistry = metricRegistry;
        this.clientMetricGroup = new ClientMetricGroup(metricRegistry, clientId);
        this.rpcClient = RpcClient.create(conf, clientMetricGroup);

        // TODO this maybe remove after we introduce client metadata.
        // 在我们引入客户元数据后，这个问题可能会被移除。
        // 构造函数中调用了initializeCluster方法
        this.metadataUpdater = new MetadataUpdater(conf, rpcClient);
        this.writerClient = null;
    }

    @Override
    public Configuration getConfiguration() {
        return conf;
    }

    @Override
    public Admin getAdmin() {
        return new FlussAdmin(rpcClient, metadataUpdater);
    }

    @Override
    public Table getTable(TablePath tablePath) {
        return new FlussTable(
                conf,
                tablePath,
                rpcClient,
                metadataUpdater,
                this::maybeCreateWriter,
                this::maybeCreateLookupClient,
                clientMetricGroup);
    }

    private WriterClient maybeCreateWriter() {
        if (writerClient == null) {
            synchronized (this) {
                if (writerClient == null) {
                    writerClient = new WriterClient(conf, metadataUpdater, clientMetricGroup);
                }
            }
        }
        return writerClient;
    }

    private LookupClient maybeCreateLookupClient() {
        if (lookupClient == null) {
            synchronized (this) {
                if (lookupClient == null) {
                    lookupClient = new LookupClient(conf, metadataUpdater);
                }
            }
        }
        return lookupClient;
    }

    private void setupClientMetricsConfiguration() {
        boolean enableClientMetrics = conf.getBoolean(ConfigOptions.CLIENT_METRICS_ENABLED);
        List<String> reporters = conf.get(ConfigOptions.METRICS_REPORTERS);
        if (enableClientMetrics && (reporters == null || reporters.isEmpty())) {
            // Client will use JMX reporter by default if not set.
            // 如果未设置，客户端将默认使用 JMX 报告器。
            conf.setString(ConfigOptions.METRICS_REPORTERS.key(), "jmx");
        }
    }

    @Override
    public void close() throws Exception {
        if (writerClient != null) {
            writerClient.close(Duration.ofMillis(Long.MAX_VALUE));
        }

        if (lookupClient != null) {
            // timeout is Long.MAX_VALUE to make the pending get request
            // to be processed
            // 超时时间为 Long.MAX_VALUE，以便处理待处理的获取请求
            lookupClient.close(Duration.ofMillis(Long.MAX_VALUE));
        }

        clientMetricGroup.close();
        rpcClient.close();
        metricRegistry.closeAsync().get();
    }
}
