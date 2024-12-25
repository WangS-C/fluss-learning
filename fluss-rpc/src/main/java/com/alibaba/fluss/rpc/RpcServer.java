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

package com.alibaba.fluss.rpc;

import com.alibaba.fluss.config.Configuration;
import com.alibaba.fluss.rpc.netty.server.NettyServer;
import com.alibaba.fluss.rpc.netty.server.RequestsMetrics;
import com.alibaba.fluss.utils.AutoCloseableAsync;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/** Handles new connections, requests and responses to and from coordinator/tablet server. */
// 处理来自协调器/ tablet服务器的新连接、请求和响应。
public interface RpcServer extends AutoCloseableAsync {

    /**
     * Creates a new RPC server that can bind to the given address and port and uses the given
     * {@link RpcGatewayService} to handle incoming requests.
     *
     * @param conf The configuration for the RPC server.
     * @param externalAddress The external address to bind to.
     * @param externalPortRange The external port range to bind to.
     * @param service The service to handle incoming requests.
     * @param requestsMetrics the requests metrics to report.
     * @return The new RPC server.
     */
    // 创建可绑定到给定地址和端口的新RPC服务器，并使用给定的RpcGatewayService处理传入请求。
    static RpcServer create(
            Configuration conf,
            String externalAddress,
            String externalPortRange,
            RpcGatewayService service,
            RequestsMetrics requestsMetrics)
            throws IOException {
        return new NettyServer(conf, externalAddress, externalPortRange, service, requestsMetrics);
    }

    /** Starts the RPC server by binding to the configured bind address and port (blocking). */
    // 通过绑定到配置的绑定地址和端口 (阻止) 来启动RPC服务器。
    void start() throws IOException;

    /**
     * Return the hostname or host address under which the rpc server can be reached. If the rpc
     * server is not started yet or can't determine the address yet, then it will return an empty
     * string.
     */
    // 返回可访问rpc服务器的主机名或主机地址。如果rpc服务器尚未启动或无法确定地址，则它将返回一个空字符串。
    String getHostname();

    /**
     * Return the port under which the rpc server is reachable. If the rpc server is not started yet
     * or can't determine the port yet, then it will return -1.
     */
    // 返回可访问rpc服务器的端口。如果rpc服务器尚未启动或无法确定端口，则将返回-1。
    int getPort();

    CompletableFuture<Void> closeAsync();

    /**
     * Gets a scheduled executor from the RPC server. This executor can be used to schedule tasks to
     * be executed in the future.
     *
     * <p><b>IMPORTANT:</b> This executor does not isolate the method invocations against any
     * concurrent invocations and is therefore not suitable to run completion methods of futures
     * that modify state of an {@link RpcGatewayService}. For such operations, one needs to use the
     * {@code RpcGatewayService#getMainThreadExecutor() MainThreadExecutionContext} of that {@code
     * RpcGatewayService}.
     *
     * @return The RPC server provided scheduled executor
     */
    // 从RPC服务器获取计划的执行器。此执行器可用于调度将来要执行的任务。
    //重要提示: 此执行器不会将方法调用与任何并发调用隔离开来，因此不适合运行修改RpcGatewayService状态的期货的完成方法。
    //对于此类操作，需要使用该RpcGatewayService的RpcGatewayService # getMainThreadExecutor() MainThreadExecutionContext。
    ScheduledExecutorService getScheduledExecutor();
}
