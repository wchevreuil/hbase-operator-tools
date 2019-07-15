/**
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
package org.apache.hbase.hbck2.meta;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.MetaTableAccessor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.regionserver.HRegionFileSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This class implements the inner works required for check and recover regions that wrongly
 * went missing in META. It assumes HDFS state as the source of truth, in other words,
 * methods provided here consider meta information found on HDFS region dirs as the valid ones.
 */
public class MetaFixer implements Closeable {
  private static final String TABLE_DESC_FILE = ".tabledesc";
  private static final Logger LOG = LogManager.getLogger(MetaFixer.class);
  private final FileSystem fs;
  private final Connection conn;
  private final Configuration config;

  public MetaFixer(Configuration configuration) throws IOException {
    this.config = configuration;
    this.fs = FileSystem.get(configuration);
    this.conn = ConnectionFactory.createConnection(configuration);
  }

  /*Initially defined for test only purposes */
  MetaFixer(Configuration configuration, Connection connection, FileSystem fileSystem){
    this.config = configuration;
    this.conn = connection;
    this.fs = fileSystem;
  }

  private FileStatus[] getTableRegionsDirs(String table) throws Exception {
    String hbaseRoot = this.config.get("hbase.rootdir");
    String tableRootDir = hbaseRoot + "/" + HConstants.BASE_NAMESPACE_DIR + "/" +
      TableName.valueOf(table).getNameWithNamespaceInclAsString()
        .replaceAll(":", "/");
    return fs.listStatus(new Path(tableRootDir));
  }

  public Map<TableName,List<Path>> reportTablesMissingRegions(final List<String> namespacesOrTables)
      throws IOException {
    final Map<TableName,List<Path>> result = new HashMap<>();
    List<TableName> tableNames = MetaTableAccessor.getTableStates(this.conn).keySet().stream()
      .filter(tableName -> {
        if(namespacesOrTables==null || namespacesOrTables.isEmpty()){
          return true;
        } else {
          Optional<String> findings = namespacesOrTables.stream().filter(
            name -> (name.indexOf(":") > 0) ?
              tableName.getNameWithNamespaceInclAsString().equals(name) :
              tableName.getNamespaceAsString().equals(name)).findFirst();
          return findings.isPresent();
        }
      }).collect(Collectors.toList());
    tableNames.stream().forEach(tableName -> {
      try {
        result.put(tableName,
          findMissingRegionsInMETA(tableName.getNameWithNamespaceInclAsString()));
      } catch (Exception e) {
        e.printStackTrace();
      }
    });
    return result;
  }

  List<Path> findMissingRegionsInMETA(String table) throws Exception {
    final List<Path> missingRegions = new ArrayList<>();
    final FileStatus[] regionsDirs = getTableRegionsDirs(table);
    TableName tableName = TableName.valueOf(table);
    List<RegionInfo> regionInfos = MetaTableAccessor.
      getTableRegions(this.conn, tableName, false);
    for(final FileStatus regionDir : regionsDirs){
      if(!regionDir.getPath().getName().equals(TABLE_DESC_FILE) &&
          !regionDir.getPath().getName().equals(HConstants.HBASE_TEMP_DIRECTORY)) {
        boolean foundInMeta = regionInfos.stream()
          .anyMatch(info -> info.getEncodedName().equals(regionDir.getPath().getName()));
        if (!foundInMeta) {
          LOG.debug(regionDir + "is not in META.");
          missingRegions.add(regionDir.getPath());
        }
      }
    }
    return missingRegions;
  }

  public void putRegionInfoFromHdfsInMeta(Path region) throws IOException {
    RegionInfo info = HRegionFileSystem.loadRegionInfoFileContent(fs, region);
    MetaTableAccessor.addRegionToMeta(conn, info);
  }

  @Override public void close() throws IOException {
    this.conn.close();
  }
}