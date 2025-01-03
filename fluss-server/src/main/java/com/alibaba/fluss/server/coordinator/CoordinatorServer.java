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

package com.alibaba.fluss.server.coordinator;

import com.alibaba.fluss.annotation.VisibleForTesting;
import com.alibaba.fluss.config.ConfigOptions;
import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.exception.IllegalConfigurationException;
import com.alibaba.fluss.metrics.registry.MetricRegistry;
import com.alibaba.fluss.rpc.RpcClient;
import com.alibaba.fluss.rpc.RpcServer;
import com.alibaba.fluss.rpc.metrics.ClientMetricGroup;
import com.alibaba.fluss.rpc.netty.server.RequestsMetrics;
import com.alibaba.fluss.server.ServerBase;
import com.alibaba.fluss.server.coordinator.event.CoordinatorEventManager;
import com.alibaba.fluss.server.metadata.ServerMetadataCache;
import com.alibaba.fluss.server.metadata.ServerMetadataCacheImpl;
import com.alibaba.fluss.server.metrics.ServerMetricUtils;
import com.alibaba.fluss.server.metrics.group.CoordinatorMetricGroup;
import com.alibaba.fluss.server.zk.ZooKeeperClient;
import com.alibaba.fluss.server.zk.ZooKeeperUtils;
import com.alibaba.fluss.server.zk.data.CoordinatorAddress;
import com.alibaba.fluss.utils.ExceptionUtils;
import com.alibaba.fluss.utils.concurrent.FutureUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinator server implementation. The coordinator server is responsible to:
 *
 * <ul>
 *   <li>manage the tablet servers
 *   <li>manage the metadata
 *   <li>coordinate the whole cluster, e.g. data re-balance, recover data when tablet servers down
 * </ul>
 */

// CoordinatorServer是集群的中央控制和管理组件。它负责维护元数据、管理 tablet 分配、列出节点和处理权限。
// 此外，它还协调关键操作，例如：
// 在节点扩展（升级或降级）期间重新平衡数据。
// 在发生节点故障时管理数据迁移和服务节点切换。
// 监督表管理任务，包括创建或删除表以及更新存储桶计数。
public class CoordinatorServer extends ServerBase {

    public static final String DEFAULT_DATABASE = "fluss";
    private static final String SERVER_NAME = "CoordinatorServer";

    private static final Logger LOG = LoggerFactory.getLogger(CoordinatorServer.class);

    /** The lock to guard startup / shutdown / manipulation methods. */
    // 锁定保护启动/ 关闭/ 操作方法。
    private final Object lock = new Object();

    private final CompletableFuture<Result> terminationFuture;

    private final AtomicBoolean isShutDown = new AtomicBoolean(false);

    @GuardedBy("lock")
    private String serverId;

    @GuardedBy("lock")
    private MetricRegistry metricRegistry;

    @GuardedBy("lock")
    private CoordinatorMetricGroup serverMetricGroup;

    @GuardedBy("lock")
    private RpcServer rpcServer;

    @GuardedBy("lock")
    private RpcClient rpcClient;

    @GuardedBy("lock")
    private ClientMetricGroup clientMetricGroup;

    @GuardedBy("lock")
    private CoordinatorService coordinatorService;

    @GuardedBy("lock")
    private ServerMetadataCache metadataCache;

    @GuardedBy("lock")
    private CoordinatorChannelManager coordinatorChannelManager;

    @GuardedBy("lock")
    private CoordinatorEventProcessor coordinatorEventProcessor;

    @GuardedBy("lock")
    private ZooKeeperClient zkClient;

    @GuardedBy("lock")
    private AutoPartitionManager autoPartitionManager;

    public CoordinatorServer(Configuration conf) {
        super(conf);
        // 验证配置
        validateConfigs(conf);
        this.terminationFuture = new CompletableFuture<>();
    }

    public static void main(String[] args) {
        // 加载配置
        Configuration configuration =
                loadConfiguration(args, CoordinatorServer.class.getSimpleName());

        CoordinatorServer coordinatorServer = new CoordinatorServer(configuration);

        // 启动
        startServer(coordinatorServer);
    }

    @Override
    protected void startServices() throws Exception {
        synchronized (lock) {
            LOG.info("Initializing Coordinator services.");

            this.serverId = UUID.randomUUID().toString();

            // for metrics
            // 初始化指标
            this.metricRegistry = MetricRegistry.create(conf, pluginManager);
            this.serverMetricGroup =
                    ServerMetricUtils.createCoordinatorGroup(
                            metricRegistry,
                            ServerMetricUtils.validateAndGetClusterId(conf),
                            conf.getString(ConfigOptions.COORDINATOR_HOST),
                            serverId);

            // 启动zk客户端
            this.zkClient = ZooKeeperUtils.startZookeeperClient(conf, this);

            // 创建元数据缓存
            this.metadataCache = new ServerMetadataCacheImpl();

            // 创建RPC网关服务。
            this.coordinatorService =
                    new CoordinatorService(
                            conf,
                            remoteFileSystem,
                            zkClient,
                            this::getCoordinatorEventManager,
                            metadataCache);

            // 创建RpcServer 基于Netty实现
            this.rpcServer =
                    RpcServer.create(
                            conf,
                            conf.getString(ConfigOptions.COORDINATOR_HOST),
                            conf.getString(ConfigOptions.COORDINATOR_PORT),
                            coordinatorService,
                            RequestsMetrics.createCoordinatorServerRequestMetrics(
                                    serverMetricGroup));
            // 启动RpcServer
            rpcServer.start();

            // 向zk注册
            registerCoordinatorLeader();

            // 创建 RpcClient
            this.clientMetricGroup = new ClientMetricGroup(metricRegistry, SERVER_NAME);
            this.rpcClient = RpcClient.create(conf, clientMetricGroup);

            this.coordinatorChannelManager = new CoordinatorChannelManager(rpcClient);

            // 创建快照管理器
            CompletedSnapshotStoreManager bucketSnapshotManager =
                    new CompletedSnapshotStoreManager(
                            conf.getInt(ConfigOptions.KV_MAX_RETAINED_SNAPSHOTS),
                            conf.getInt(ConfigOptions.COORDINATOR_IO_POOL_SIZE),
                            zkClient);

            // 创建自动分区管理器
            this.autoPartitionManager = new AutoPartitionManager(metadataCache, zkClient, conf);
            // 启动
            autoPartitionManager.start();

            // start coordinator event processor after we register coordinator leader to zk
            // so that the event processor can get the coordinator leader node from zk during start
            // up.
            // in HA for coordinator server, the processor also need to know the leader node during
            // start up
            // 我们将协调器领导者注册到zk后启动协调器事件处理器，以便事件处理器在启动时可以从zk获取协调器领导者节点。
            // 在协调服务器的HA中，处理器在启动期间还需要知道领导节点
            this.coordinatorEventProcessor =
                    new CoordinatorEventProcessor(
                            zkClient,
                            metadataCache,
                            coordinatorChannelManager,
                            bucketSnapshotManager,
                            autoPartitionManager,
                            serverMetricGroup);
            // 启动
            coordinatorEventProcessor.startup();

            // 创建默认的数据库
            createDefaultDatabase();
        }
    }

    @Override
    protected CompletableFuture<Result> closeAsync(Result result) {
        if (isShutDown.compareAndSet(false, true)) {
            LOG.info("Shutting down Coordinator server ({}).", result);
            CompletableFuture<Void> serviceShutdownFuture = stopServices();

            serviceShutdownFuture.whenComplete(
                    ((Void ignored2, Throwable serviceThrowable) -> {
                        if (serviceThrowable != null) {
                            terminationFuture.completeExceptionally(serviceThrowable);
                        } else {
                            terminationFuture.complete(result);
                        }
                    }));
        }

        return terminationFuture;
    }

    private void registerCoordinatorLeader() throws Exception {
        // set server id
        String serverId = UUID.randomUUID().toString();
        CoordinatorAddress coordinatorAddress =
                new CoordinatorAddress(serverId, rpcServer.getHostname(), rpcServer.getPort());
        zkClient.registerCoordinatorLeader(coordinatorAddress);
    }

    private void createDefaultDatabase() {
        MetaDataManager metaDataManager = new MetaDataManager(zkClient);
        List<String> databases = metaDataManager.listDatabases();
        if (databases.isEmpty()) {
            metaDataManager.createDatabase(DEFAULT_DATABASE, true);
            LOG.info("Created default database '{}' because no database exists.", DEFAULT_DATABASE);
        }
    }

    private CoordinatorEventManager getCoordinatorEventManager() {
        if (coordinatorEventProcessor != null) {
            return coordinatorEventProcessor.getCoordinatorEventManager();
        } else {
            throw new IllegalStateException("CoordinatorEventProcessor is not initialized yet.");
        }
    }

    CompletableFuture<Void> stopServices() {
        synchronized (lock) {
            Throwable exception = null;

            try {
                if (serverMetricGroup != null) {
                    serverMetricGroup.close();
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            final Collection<CompletableFuture<Void>> terminationFutures = new ArrayList<>(2);
            try {
                if (metricRegistry != null) {
                    terminationFutures.add(metricRegistry.closeAsync());
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            try {
                if (autoPartitionManager != null) {
                    autoPartitionManager.close();
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            try {
                if (coordinatorEventProcessor != null) {
                    coordinatorEventProcessor.shutdown();
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            try {
                if (coordinatorChannelManager != null) {
                    coordinatorChannelManager.close();
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            try {
                if (rpcServer != null) {
                    terminationFutures.add(rpcServer.closeAsync());
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            try {
                if (coordinatorService != null) {
                    coordinatorService.shutdown();
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            try {
                if (zkClient != null) {
                    zkClient.close();
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            try {
                if (rpcClient != null) {
                    rpcClient.close();
                }

                if (clientMetricGroup != null) {
                    clientMetricGroup.close();
                }
            } catch (Throwable t) {
                exception = ExceptionUtils.firstOrSuppressed(t, exception);
            }

            if (exception != null) {
                terminationFutures.add(FutureUtils.completedExceptionally(exception));
            }
            return FutureUtils.completeAll(terminationFutures);
        }
    }

    @Override
    protected CompletableFuture<Result> getTerminationFuture() {
        return terminationFuture;
    }

    @VisibleForTesting
    public CoordinatorService getCoordinatorService() {
        return coordinatorService;
    }

    @Override
    protected String getServerName() {
        return SERVER_NAME;
    }

    @VisibleForTesting
    RpcServer getRpcServer() {
        return rpcServer;
    }

    // 验证配置
    private static void validateConfigs(Configuration conf) {
        // 默认复制因子  不能小于1
        if (conf.get(ConfigOptions.DEFAULT_REPLICATION_FACTOR) < 1) {
            throw new IllegalConfigurationException(
                    String.format(
                            "Invalid configuration for %s, it must be greater than or equal 1.",
                            ConfigOptions.DEFAULT_REPLICATION_FACTOR.key()));
        }

        // 要保留的已完成快照的最大数量。 不能小于1
        if (conf.get(ConfigOptions.KV_MAX_RETAINED_SNAPSHOTS) < 1) {
            throw new IllegalConfigurationException(
                    String.format(
                            "Invalid configuration for %s, it must be greater than or equal 1.",
                            ConfigOptions.KV_MAX_RETAINED_SNAPSHOTS.key()));
        }

        // 为coordinatorServer运行阻塞操作的IO线程池的大小 包含清理不必要的快照文件
        if (conf.get(ConfigOptions.COORDINATOR_IO_POOL_SIZE) < 1) {
            throw new IllegalConfigurationException(
                    String.format(
                            "Invalid configuration for %s, it must be greater than or equal 1.",
                            ConfigOptions.COORDINATOR_IO_POOL_SIZE.key()));
        }

        // 用于在Fluss支持的文件系统中存储kv快照数据文件和日志分层存储的远程日志的目录
        if (conf.get(ConfigOptions.REMOTE_DATA_DIR) == null) {
            throw new IllegalConfigurationException(
                    String.format("Configuration %s must be set.", ConfigOptions.REMOTE_DATA_DIR));
        }
    }
}
