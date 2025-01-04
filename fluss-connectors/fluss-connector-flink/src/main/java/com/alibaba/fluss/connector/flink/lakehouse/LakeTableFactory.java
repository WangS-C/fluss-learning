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

package com.alibaba.fluss.connector.flink.lakehouse;

import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.factories.DynamicTableFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.paimon.flink.FlinkTableFactory;

/**
 * A factory to create {@link DynamicTableSource} for lake table.
 */
// 为数据湖表创建DynamicTableSource的工厂。
public class LakeTableFactory {

    // now, always assume is paimon, todo need to describe lake storage from
    // to know which lake storage used
    // 现在，总是假设是paimon，需要从湖描述来了解使用的是哪个湖的存储
    private final org.apache.paimon.flink.FlinkTableFactory paimonFlinkTableFactory;

    public LakeTableFactory() {
        paimonFlinkTableFactory = new FlinkTableFactory();
    }

    public DynamicTableSource createDynamicTableSource(
            DynamicTableFactory.Context context, String tableName) {
        ObjectIdentifier originIdentifier = context.getObjectIdentifier();
        ObjectIdentifier paimonIdentifier =
                ObjectIdentifier.of(
                        originIdentifier.getCatalogName(),
                        originIdentifier.getDatabaseName(),
                        tableName);
        DynamicTableFactory.Context newContext =
                new FactoryUtil.DefaultDynamicTableContext(
                        paimonIdentifier,
                        context.getCatalogTable(),
                        context.getEnrichmentOptions(),
                        context.getConfiguration(),
                        context.getClassLoader(),
                        context.isTemporary());

        return paimonFlinkTableFactory.createDynamicTableSource(newContext);
    }
}
