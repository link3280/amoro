/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.flink;

import static org.apache.flink.table.api.Expressions.$;

import com.netease.arctic.BasicTableTestHelper;
import com.netease.arctic.ams.api.TableFormat;
import com.netease.arctic.catalog.BasicCatalogTestHelper;
import com.netease.arctic.flink.catalog.ArcticCatalog;
import com.netease.arctic.flink.util.DataUtil;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.table.api.ApiExpression;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.catalog.CatalogPartitionSpec;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.TableNotPartitionedException;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.types.RowKind;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedList;
import java.util.List;

public class TestArcticCatalogTablePartitions extends FlinkTestBase {
  private final String tableName = "test_partition_table";
  private final String db = "test_partition_db";

  public TestArcticCatalogTablePartitions() {
    super(
        new BasicCatalogTestHelper(TableFormat.MIXED_ICEBERG),
        new BasicTableTestHelper(true, true));
  }

  public void before() throws Exception {
    super.before();
    super.config();
  }

  @Test
  public void testListPartitionsUnKeyedTable() throws TableNotPartitionedException {
    List<Object[]> data = new LinkedList<>();
    data.add(new Object[] {1, "mark", "2023-10-01"});
    data.add(new Object[] {2, "Gerry", "2023-10-02"});

    List<ApiExpression> rows = DataUtil.toRows(data);
    Table input =
        getTableEnv()
            .fromValues(
                DataTypes.ROW(
                    DataTypes.FIELD("id", DataTypes.INT()),
                    DataTypes.FIELD("name", DataTypes.STRING()),
                    DataTypes.FIELD("dt", DataTypes.STRING())),
                rows);
    getTableEnv().createTemporaryView("input", input);

    sql("CREATE CATALOG arcticCatalog WITH %s", toWithClause(props));

    sql(
        "CREATE TABLE IF NOT EXISTS arcticCatalog."
            + db
            + "."
            + tableName
            + "("
            + " id INT, name STRING, dt STRING) PARTITIONED BY (dt)");

    sql("INSERT INTO %s select * from input", "arcticCatalog." + db + "." + tableName);
    ObjectPath objectPath = new ObjectPath(db, tableName);
    ArcticCatalog arcticCatalog = (ArcticCatalog) getTableEnv().getCatalog("arcticCatalog").get();
    List<CatalogPartitionSpec> list = arcticCatalog.listPartitions(objectPath);

    List<CatalogPartitionSpec> expected = Lists.newArrayList();
    CatalogPartitionSpec partitionSpec1 =
        new CatalogPartitionSpec(ImmutableMap.of("dt", "2023-10-01"));
    CatalogPartitionSpec partitionSpec2 =
        new CatalogPartitionSpec(ImmutableMap.of("dt", "2023-10-02"));
    expected.add(partitionSpec1);
    expected.add(partitionSpec2);
    Assert.assertEquals("Should produce the expected catalog partition specs.", list, expected);
  }

  @Test
  public void testListPartitionsKeyedTable() throws TableNotPartitionedException {
    List<Object[]> data = new LinkedList<>();
    data.add(new Object[] {1, "mark", "2023-10-01"});
    data.add(new Object[] {2, "Gerry", "2023-10-02"});
    data.add(new Object[] {RowKind.DELETE, 2, "Gerry", "2023-10-02"});

    DataStreamSource<RowData> rowData =
        getEnv()
            .fromCollection(
                DataUtil.toRowData(data),
                InternalTypeInfo.ofFields(
                    DataTypes.INT().getLogicalType(),
                    DataTypes.VARCHAR(100).getLogicalType(),
                    DataTypes.VARCHAR(100).getLogicalType()));
    Table input = getTableEnv().fromDataStream(rowData, $("id"), $("name"), $("dt"));

    getTableEnv().createTemporaryView("input", input);

    sql("CREATE CATALOG arcticCatalog WITH %s", toWithClause(props));

    sql(
        "CREATE TABLE IF NOT EXISTS arcticCatalog."
            + db
            + "."
            + tableName
            + "("
            + " id INT, name STRING, dt STRING, PRIMARY KEY (id) NOT ENFORCED) PARTITIONED BY (dt)");

    sql("INSERT INTO %s select * from input", "arcticCatalog." + db + "." + tableName);
    ObjectPath objectPath = new ObjectPath(db, tableName);
    ArcticCatalog arcticCatalog = (ArcticCatalog) getTableEnv().getCatalog("arcticCatalog").get();
    List<CatalogPartitionSpec> partitionList = arcticCatalog.listPartitions(objectPath);

    List<CatalogPartitionSpec> expected = Lists.newArrayList();
    CatalogPartitionSpec partitionSpec1 =
        new CatalogPartitionSpec(ImmutableMap.of("dt", "2023-10-01"));
    CatalogPartitionSpec partitionSpec2 =
        new CatalogPartitionSpec(ImmutableMap.of("dt", "2023-10-02"));
    expected.add(partitionSpec1);
    expected.add(partitionSpec2);
    Assert.assertEquals(
        "Should produce the expected catalog partition specs.", partitionList, expected);
  }
}
