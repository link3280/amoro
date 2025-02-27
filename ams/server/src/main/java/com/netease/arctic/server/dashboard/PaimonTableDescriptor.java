/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.server.dashboard;

import com.netease.arctic.AmoroTable;
import com.netease.arctic.ams.api.TableFormat;
import com.netease.arctic.server.dashboard.component.reverser.DDLReverser;
import com.netease.arctic.server.dashboard.component.reverser.PaimonTableMetaExtract;
import com.netease.arctic.server.dashboard.model.AMSColumnInfo;
import com.netease.arctic.server.dashboard.model.AMSPartitionField;
import com.netease.arctic.server.dashboard.model.DDLInfo;
import com.netease.arctic.server.dashboard.model.PartitionBaseInfo;
import com.netease.arctic.server.dashboard.model.PartitionFileBaseInfo;
import com.netease.arctic.server.dashboard.model.ServerTableMeta;
import com.netease.arctic.server.dashboard.model.TransactionsOfTable;
import com.netease.arctic.server.dashboard.utils.AmsUtil;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.paimon.AbstractFileStore;
import org.apache.paimon.Snapshot;
import org.apache.paimon.manifest.ManifestEntry;
import org.apache.paimon.manifest.ManifestFile;
import org.apache.paimon.manifest.ManifestFileMeta;
import org.apache.paimon.manifest.ManifestList;
import org.apache.paimon.table.DataTable;
import org.apache.paimon.table.FileStoreTable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Descriptor for Paimon format tables.
 */
public class PaimonTableDescriptor implements FormatTableDescriptor {
  @Override
  public List<TableFormat> supportFormat() {
    return Lists.newArrayList(TableFormat.PAIMON);
  }

  @Override
  public ServerTableMeta getTableDetail(AmoroTable<?> amoroTable) {
    FileStoreTable table = getTable(amoroTable);
    AbstractFileStore<?> store = (AbstractFileStore<?>) table.store();

    ServerTableMeta serverTableMeta = new ServerTableMeta();
    serverTableMeta.setTableIdentifier(amoroTable.id());
    serverTableMeta.setTableType(amoroTable.format().name());

    //schema
    serverTableMeta.setSchema(
        table.rowType().getFields().stream()
            .map(s -> new AMSColumnInfo(s.name(), s.type().asSQLString(), !s.type().isNullable(), s.description()))
            .collect(Collectors.toList())
    );

    //primary key
    Set<String> primaryKeyNames = new HashSet<>(table.primaryKeys());
    List<AMSColumnInfo> primaryKeys = serverTableMeta.getSchema()
        .stream()
        .filter(s -> primaryKeyNames.contains(s.getField()))
        .collect(Collectors.toList());
    serverTableMeta.setPkList(primaryKeys);

    //partition
    List<AMSPartitionField> partitionFields = store.partitionType()
        .getFields().stream()
        .map(f -> new AMSPartitionField(f.name(), null, null, f.id(), null))
        .collect(Collectors.toList());
    serverTableMeta.setPartitionColumnList(partitionFields);

    //properties
    serverTableMeta.setProperties(table.options());

    Map<String, Object> tableSummary = new HashMap<>();
    Map<String, Object> baseMetric = new HashMap<>();
    //table summary
    tableSummary.put("tableFormat", AmsUtil.formatString(amoroTable.format().name()));
    Snapshot snapshot = store.snapshotManager().latestSnapshot();
    if (snapshot != null) {
      TransactionsOfTable transactionsOfTable =
          manifestListInfo(store, snapshot, (m, s) -> s.dataManifests(m));
      long fileSize = transactionsOfTable.getFileSize();
      String totalSize = AmsUtil.byteToXB(fileSize);
      int fileCount = transactionsOfTable.getFileCount();

      String averageFileSize = AmsUtil.byteToXB(
          fileCount == 0 ?
              0 : fileSize / fileCount);

      tableSummary.put("averageFile", averageFileSize);
      tableSummary.put("file", fileCount);
      tableSummary.put("size", totalSize);

      baseMetric.put("totalSize", totalSize);
      baseMetric.put("fileCount", fileCount);
      baseMetric.put("averageFileSize", averageFileSize);
      baseMetric.put("lastCommitTime", snapshot.timeMillis());
      Long watermark = snapshot.watermark();
      if (watermark != null && watermark > 0) {
        baseMetric.put("baseWatermark", watermark);
      }
    } else {
      tableSummary.put("size", 0);
      tableSummary.put("file", 0);
      tableSummary.put("averageFile", 0);

      baseMetric.put("totalSize", 0);
      baseMetric.put("fileCount", 0);
      baseMetric.put("averageFileSize", 0);
    }
    serverTableMeta.setTableSummary(tableSummary);
    serverTableMeta.setBaseMetrics(baseMetric);

    return serverTableMeta;
  }

  @Override
  public List<TransactionsOfTable> getTransactions(AmoroTable<?> amoroTable) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<PartitionFileBaseInfo> getTransactionDetail(AmoroTable<?> amoroTable, long transactionId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<DDLInfo> getTableOperations(AmoroTable<?> amoroTable) {
    DataTable table = getTable(amoroTable);
    PaimonTableMetaExtract extract = new PaimonTableMetaExtract();
    DDLReverser<DataTable> ddlReverser = new DDLReverser<>(extract);
    return ddlReverser.reverse(table, amoroTable.id());
  }

  @Override
  public List<PartitionBaseInfo> getTablePartition(AmoroTable<?> amoroTable) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<PartitionFileBaseInfo> getTableFile(AmoroTable<?> amoroTable, String partition) {
    throw new UnsupportedOperationException();
  }

  private TransactionsOfTable manifestListInfo(
      AbstractFileStore<?> store,
      Snapshot snapshot,
      BiFunction<ManifestList, Snapshot, List<ManifestFileMeta>> biFunction) {
    ManifestList manifestList = store.manifestListFactory().create();
    ManifestFile manifestFile = store.manifestFileFactory().create();
    List<ManifestFileMeta> manifestFileMetas = biFunction.apply(manifestList, snapshot);
    int fileCount = 0;
    int fileSize = 0;
    for (ManifestFileMeta manifestFileMeta : manifestFileMetas) {
      List<ManifestEntry> manifestEntries = manifestFile.read(manifestFileMeta.fileName());
      for (ManifestEntry entry : manifestEntries) {
        fileCount++;
        fileSize += entry.file().fileSize();
      }
    }
    return new TransactionsOfTable(
        snapshot.id(),
        fileCount,
        fileSize,
        snapshot.timeMillis(),
        snapshot.commitKind().toString(),
        null);
  }

  private FileStoreTable getTable(AmoroTable<?> amoroTable) {
    return (FileStoreTable) amoroTable.originalTable();
  }
}
