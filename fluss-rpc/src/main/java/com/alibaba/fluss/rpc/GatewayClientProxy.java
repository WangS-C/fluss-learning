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

import com.alibaba.fluss.cluster.ServerNode;
import com.alibaba.fluss.rpc.messages.ApiMessage;
import com.alibaba.fluss.rpc.protocol.ApiManager;
import com.alibaba.fluss.rpc.protocol.ApiMethod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/** Proxy for a {@link RpcGateway} that forwards all method calls to the remote gateway service. */
public class GatewayClientProxy implements InvocationHandler {

    private final Supplier<ServerNode> nodeSupplier;
    private final RpcClient client;

    GatewayClientProxy(Supplier<ServerNode> nodeSupplier, RpcClient client) {
        this.nodeSupplier = nodeSupplier;
        this.client = client;
    }

    /**
     * Creates a proxy for the given gateway class. The proxy will forward all method calls to the
     * remote gateway service.
     */
    // 为给定的网关类创建代理。代理将所有方法调用转发到远程网关服务。
    public static <T extends RpcGateway> T createGatewayProxy(
            Supplier<ServerNode> nodeSupplier, RpcClient client, Class<T> gatewayClass) {
        // Rather than using the System ClassLoader directly, we derive the
        // ClassLoader from gateway class. That works better in cases where Fluss
        // runs embedded and all Fluss code is loaded dynamically (for example
        // from an OSGI bundle) through a custom ClassLoader
        // 我们不是直接使用系统类加载器，而是从网关类派生类加载器。
        // 在Fluss运行嵌入式并且所有Fluss代码通过自定义类加载器动态加载 (例如从OSGI包) 的情况下，效果更好
        ClassLoader classLoader = gatewayClass.getClassLoader();

        @SuppressWarnings("unchecked")
        T proxy =
                (T)
                        Proxy.newProxyInstance(
                                classLoader,
                                new Class<?>[] {gatewayClass},
                                new GatewayClientProxy(nodeSupplier, client));
        return proxy;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args.length == 1) {
            Object arg = args[0];
            if (arg instanceof ApiMessage) {
                return invokeRpc(method.getName(), (ApiMessage) arg);
            }
        }
        throw new IllegalArgumentException(
                "RpcGateway methods must have exactly one argument of type ApiMessage");
    }

    private Object invokeRpc(String methodName, ApiMessage request) {
        ApiMethod apiMethod = ApiManager.forMethodName(methodName);
        if (apiMethod == null) {
            throw new IllegalArgumentException("Unknown RPC method: " + methodName);
        }
        return client.sendRequest(nodeSupplier.get(), apiMethod.getApiKey(), request);
    }
}
