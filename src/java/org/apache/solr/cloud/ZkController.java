package org.apache.solr.cloud;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.solr.cloud.SolrZkClient.OnReconnect;
import org.apache.solr.common.SolrException;
import org.apache.solr.core.SolrConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.schema.IndexSchema;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * Handle ZooKeeper interactions.
 * 
 * notes: loads everything on init, creates what's not there - further updates
 * are prompted with Watches.
 * 
 */
public final class ZkController {

  private static Logger log = LoggerFactory.getLogger(ZkController.class);

  static final String NEWL = System.getProperty("line.separator");

  private final static Pattern URL_POST = Pattern.compile("https?://(.*)");
  private final static Pattern URL_PREFIX = Pattern.compile("(https?://).*");

  // package private for tests
  static final String CORE_ZKPREFIX = "/core";
  static final String SHARDS_ZKNODE = "/shards";
  // nocommit : ok to be public? for corecontainer access
  public static final String CONFIGS_ZKNODE = "/configs";
  static final String COLLECTIONS_ZKNODE = "/collections";

  static final String PROPS_DESC = "CoreDesc";

  final ShardsWatcher shardWatcher = new ShardsWatcher(this);

  private SolrZkClient zkClient;

  private volatile CloudInfo cloudInfo;

  private String zkServerAddress;

  private String localHostPort;
  private String localHostContext;
  private String localHostName;
  private String localHost;

  /**
   * 
   * @param zkServerAddress ZooKeeper server host address
   * @param zkClientTimeout
   * @param collection
   * @param localHost
   * @param locaHostPort
   * @param localHostContext
   * @throws IOException
   * @throws TimeoutException
   * @throws InterruptedException
   */
  public ZkController(String zkServerAddress, int zkClientTimeout, String localHost, String locaHostPort,
      String localHostContext) throws InterruptedException,
      TimeoutException, IOException {

    this.zkServerAddress = zkServerAddress;
    this.localHostPort = locaHostPort;
    this.localHostContext = localHostContext;
    this.localHost = localHost;
    zkClient = new SolrZkClient(zkServerAddress, zkClientTimeout,
        // on reconnect, reload cloud info
        new OnReconnect() {

          public void command() {
            try {
              loadCloudInfo();
            } catch (KeeperException e) {
              log.error("ZooKeeper Exception", e);
              throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                  "ZooKeeper Exception", e);
            } catch (InterruptedException e) {
              // Restore the interrupted status
              Thread.currentThread().interrupt();
            } catch (IOException e) {
              log.error("", e);
              throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "",
                  e);
            }

          }
        });
    
    init();
  }

  /**
   * nocommit: adds nodes if they don't exist, eg /shards/ node. consider race
   * conditions
   */
  private void addZkShardsNode(String collection) throws IOException {

    String shardsZkPath = COLLECTIONS_ZKNODE + "/" + collection + SHARDS_ZKNODE;
    try {
      // shards node
      if (!zkClient.exists(shardsZkPath)) {
        if (log.isInfoEnabled()) {
          log.info("creating zk shards node:" + shardsZkPath);
        }
        // makes shards zkNode if it doesn't exist
        zkClient.makePath(shardsZkPath, CreateMode.PERSISTENT, null);
        
        // ping that there is a new collection
        zkClient.setData(COLLECTIONS_ZKNODE, (byte[])null);
      }
    } catch (KeeperException e) {
      // its okay if another beats us creating the node
      if (e.code() != KeeperException.Code.NODEEXISTS) {
        log.error("ZooKeeper Exception", e);
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
            "ZooKeeper Exception", e);
      }
    } catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
    }

    // now watch the shards node
    try {
      zkClient.exists(shardsZkPath, shardWatcher);
    } catch (KeeperException e) {
      log.error("ZooKeeper Exception", e);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
          "ZooKeeper Exception", e);
    } catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Closes the underlying ZooKeeper client.
   */
  public void close() {
    try {
      zkClient.close();
    } catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
    }
  }

  /**
   * @param collection
   * @param fileName
   * @return
   * @throws KeeperException
   * @throws InterruptedException
   */
  public boolean configFileExists(String collection, String fileName)
      throws KeeperException, InterruptedException {
    Stat stat = zkClient.exists(CONFIGS_ZKNODE + "/" + fileName, null);
    return stat != null;
  }

  /**
   * @return information about the cluster from ZooKeeper
   */
  public CloudInfo getCloudInfo() {
    return cloudInfo;
  }

  public List<String> getCollectionNames() throws KeeperException,
      InterruptedException {
    // nocommit : watch for new collections?
    List<String> collectionNodes = zkClient.getChildren(COLLECTIONS_ZKNODE,
        null);

    return collectionNodes;
  }

  /**
   * Load SolrConfig from ZooKeeper.
   * 
   * TODO: consider *many* cores firing up at once and loading the same files
   * from ZooKeeper
   * 
   * @param resourceLoader
   * @param solrConfigFileName
   * @return
   * @throws IOException
   * @throws ParserConfigurationException
   * @throws SAXException
   * @throws InterruptedException
   * @throws KeeperException
   */
  public SolrConfig getConfig(String zkConfigName, String solrConfigFileName,
      SolrResourceLoader resourceLoader) throws IOException,
      ParserConfigurationException, SAXException, KeeperException,
      InterruptedException {
    byte[] config = zkClient.getData(CONFIGS_ZKNODE + "/" + zkConfigName + "/"
        + solrConfigFileName, null, null);
    InputStream is = new ByteArrayInputStream(config);
    SolrConfig cfg = solrConfigFileName == null ? new SolrConfig(
        resourceLoader, SolrConfig.DEFAULT_CONF_FILE, is) : new SolrConfig(
        resourceLoader, solrConfigFileName, is);

    return cfg;
  }

  /**
   * @param zkConfigName
   * @param fileName
   * @return
   * @throws KeeperException
   * @throws InterruptedException
   */
  public byte[] getConfigFileData(String zkConfigName, String fileName)
      throws KeeperException, InterruptedException {
    return zkClient.getData(CONFIGS_ZKNODE + "/" + zkConfigName, null, null);
  }

  // nocommit: fooling around
  private String getHostAddress() throws IOException {

    if (localHost == null) {
      localHost = "http://" + InetAddress.getLocalHost().getHostName();
    } else {
      Matcher m = URL_PREFIX.matcher(localHost);
      if (m.matches()) {
        String prefix = m.group(1);
        localHost = prefix + localHost;
      } else {
        localHost = "http://" + localHost;
      }
    }
    if (log.isInfoEnabled()) {
      log.info("Register host with ZooKeeper:" + localHost);
    }

    return localHost;
  }

  /**
   * Load IndexSchema from ZooKeeper.
   * 
   * TODO: consider *many* cores firing up at once and loading the same files
   * from ZooKeeper
   * 
   * @param resourceLoader
   * @param schemaName
   * @param config
   * @return
   * @throws InterruptedException
   * @throws KeeperException
   */
  public IndexSchema getSchema(String zkConfigName, String schemaName,
      SolrConfig config, SolrResourceLoader resourceLoader)
      throws KeeperException, InterruptedException {
    byte[] configBytes = zkClient.getData(CONFIGS_ZKNODE + "/" + zkConfigName
        + "/" + schemaName, null, null);
    InputStream is = new ByteArrayInputStream(configBytes);
    IndexSchema schema = new IndexSchema(config, schemaName, is);
    return schema;
  }

  // nocommit - testing
  public String getSearchNodes(String collection) {
    StringBuilder nodeString = new StringBuilder();
    boolean first = true;
    List<String> nodes;

    nodes = cloudInfo.getCollectionInfo(collection).getSearchShards();
    // nocommit
    System.out.println("there are " + nodes.size() + " node(s)");
    for (String node : nodes) {
      nodeString.append(node);
      if (first) {
        first = false;
      } else {
        nodeString.append(',');
      }
    }
    return nodeString.toString();
  }

  SolrZkClient getZkClient() {
    return zkClient;
  }

  /**
   * @return
   */
  public String getZkServerAddress() {
    return zkServerAddress;
  }

  private void init() {

    try {
      localHostName = getHostAddress();
      Matcher m = URL_POST.matcher(localHostName);
      if (m.matches()) {
        String hostName = m.group(1);
        // register host
        zkClient.makePath(hostName);
      } else {
        // nocommit
        throw new IllegalStateException("Unrecognized host:"
            + localHostName);
      }
      
      // nocommit
      setUpCollectionsNode();

    } catch (IOException e) {
      log.error("", e);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
          "Can't create ZooKeeperController", e);
    } catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
    } catch (KeeperException e) {
      log.error("KeeperException", e);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "", e);
    }

  }

  // load and publish a new CollectionInfo
  public void loadCloudInfo() throws KeeperException, InterruptedException,
      IOException {
    // nocommit : not thread safe anymore ?

    log.info("Updating cloud state from ZooKeeper... :" + zkClient.keeper);
    
    // build immutable CloudInfo
    CloudInfo cloudInfo = new CloudInfo();
    List<String> collections = getCollectionNames();
    // nocommit : load all collection info
    for (String collection : collections) {
      String shardsZkPath = COLLECTIONS_ZKNODE + "/" + collection
          + SHARDS_ZKNODE;
      CollectionInfo collectionInfo = new CollectionInfo(zkClient, shardsZkPath);
      cloudInfo.addCollectionInfo(collection, collectionInfo);
    }

    // update volatile
    this.cloudInfo = cloudInfo;
  }

  /**
   * @param path
   * @return
   * @throws KeeperException
   * @throws InterruptedException
   */
  public boolean pathExists(String path) throws KeeperException,
      InterruptedException {
    return zkClient.exists(path);
  }

  /**
   * @param collection
   * @return
   * @throws KeeperException
   * @throws InterruptedException
   */
  public String readConfigName(String collection) throws KeeperException,
      InterruptedException {
    // nocommit: load all config at once or organize differently (Properties?)
    String configName = null;

    String path = COLLECTIONS_ZKNODE + "/" + collection;
    if (log.isInfoEnabled()) {
      log.info("Load collection config from:" + path);
    }
    List<String> children;
    try {
      children = zkClient.getChildren(path, null);
    } catch (KeeperException.NoNodeException e) {
      // no config is set - check if there is only one config
      // and if there is, use that
      children = zkClient.getChildren(CONFIGS_ZKNODE, null);
      if(children.size() == 1) {
        String config = children.get(0);
        log.info("No config set for " + collection + ", using single config found:" + config);
        return config;
      }

      log.error(
          "Multiple configurations were found, but config name to use for collection:"
              + collection + " could not be located", e);
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
          "Multiple configurations were found, but config name to use for collection:"
              + collection + " could not be located", e);
    }
    for (String node : children) {
      // nocommit
      System.out.println("check child:" + node);
      // nocommit: do we actually want to handle settings in the node name?
      if (node.startsWith("config=")) {
        configName = node.substring(node.indexOf("=") + 1);
        if (log.isInfoEnabled()) {
          log.info("Using collection config:" + configName);
        }
        // nocommmit : bail or read more?
      }
    }

    if (configName == null) {
      children = zkClient.getChildren(CONFIGS_ZKNODE, null);
      if(children.size() == 1) {
        String config = children.get(0);
        log.info("No config set for " + collection + ", using single config found:" + config);
        return config;
      }
      throw new IllegalStateException("no config specified for collection:"
          + collection + " " + children.size() + " configurations exist");
    }

    return configName;
  }

  /**
   * Read info on the available Shards and Nodes.
   * 
   * @param path to the shards zkNode
   * @return Map from shard name to a {@link ShardInfoList}
   * @throws InterruptedException
   * @throws KeeperException
   * @throws IOException
   */
  public Map<String,ShardInfoList> readShardsNode(String path)
      throws KeeperException, InterruptedException, IOException {

    HashMap<String,ShardInfoList> shardNameToShardList = new HashMap<String,ShardInfoList>();

    if (!zkClient.exists(path)) {
      throw new IllegalStateException("Cannot find zk node that should exist:"
          + path);
    }
    List<String> nodes = zkClient.getChildren(path, null);

    for (String zkNodeName : nodes) {
      byte[] data = zkClient.getData(path + "/" + zkNodeName, null, null);

      Properties props = new Properties();
      props.load(new ByteArrayInputStream(data));

      String url = (String) props.get(CollectionInfo.URL_PROP);
      String shardNameList = (String) props.get(CollectionInfo.SHARD_LIST_PROP);
      String[] shardsNames = shardNameList.split(",");
      for (String shardName : shardsNames) {
        ShardInfoList sList = shardNameToShardList.get(shardName);
        List<ShardInfo> shardList;
        if (sList == null) {
          shardList = new ArrayList<ShardInfo>(1);
        } else {
          List<ShardInfo> oldShards = sList.getShards();
          shardList = new ArrayList<ShardInfo>(oldShards.size() + 1);
          shardList.addAll(oldShards);
        }

        ShardInfo shard = new ShardInfo(url);
        shardList.add(shard);
        ShardInfoList list = new ShardInfoList(shardList);

        shardNameToShardList.put(shardName, list);
      }

    }

    return Collections.unmodifiableMap(shardNameToShardList);
  }

  /**
   * Register shard. A SolrCore calls this on startup to register with
   * ZooKeeper.
   * 
   * @param core
   * @return
   * @throws IOException
   * @throws InterruptedException
   * @throws KeeperException
   */
  public String register(SolrCore core) throws IOException,
      KeeperException, InterruptedException {
    String coreName = core.getCoreDescriptor().getName();
    String shardUrl = localHostName + ":" + localHostPort + "/" + localHostContext
        + "/" + coreName;

    // nocommit:
    if (log.isInfoEnabled()) {
      log.info("Register shard - core:" + core.getName() + " address:"
          + shardUrl);
    }

    CloudDescriptor cloudDesc = core.getCoreDescriptor().getCloudDescriptor();

    String collection = cloudDesc.getCollectionName();
    String nodePath = null;
    String shardsZkPath = COLLECTIONS_ZKNODE + "/" + collection + SHARDS_ZKNODE;

    // build layout if not exists
    // nocommit : consider how we watch shards on all collections
    addZkShardsNode(collection);

    // create node
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    // nocommit: could do xml
    Properties props = new Properties();
    props.put(CollectionInfo.URL_PROP, shardUrl);

    String shardList = cloudDesc.getShardList();

    props.put(CollectionInfo.SHARD_LIST_PROP, shardList == null ? ""
        : shardList);

    props.put(CollectionInfo.ROLE_PROP, cloudDesc.getRole());

    props.store(baos, PROPS_DESC);

    nodePath = zkClient.create(shardsZkPath + CORE_ZKPREFIX,
        baos.toByteArray(), CreateMode.EPHEMERAL_SEQUENTIAL);

    return nodePath;
  }

  /**
   * @param core
   * @param zkNodePath
   */
  public void unregister(SolrCore core, String zkNodePath) {
    // nocommit : version?
    try {
      zkClient.delete(zkNodePath, -1);
    } catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
    } catch (KeeperException.NoNodeException e) {
      // nocommit - this is okay - for some reason the node is already gone
      log.warn("Unregistering core: " + core.getName()
          + " but core's ZooKeeper node has already been removed");
    } catch (KeeperException e) {
      log.error("ZooKeeper Exception", e);
      // we can't get through to ZooKeeper, so log error
      // and allow close process to continue -
      // if ZooKeeper is down, our ephemeral node
      // should be removed anyway
    }
  }

  /**
   * @param dir
   * @param zkPath
   * @throws IOException
   * @throws KeeperException
   * @throws InterruptedException
   */
  public void uploadDirToCloud(File dir, String zkPath) throws IOException, KeeperException, InterruptedException {
    File[] files = dir.listFiles();
    for(File file : files) {
      if (!file.getName().startsWith(".")) {
        if (!file.isDirectory()) {
          zkClient.setData(zkPath + "/" + file.getName(), file);
        } else {
          uploadDirToCloud(file, zkPath + "/" + file.getName());
        }
      }
    }
    
  }

  // convenience for testing
  void printLayoutToStdOut() throws KeeperException, InterruptedException {
    zkClient.printLayoutToStdOut();
  }

  // nocommit
  public void watchShards() throws KeeperException, InterruptedException {
    List<String> collections = zkClient.getChildren(COLLECTIONS_ZKNODE, new Watcher() {

      public void process(WatchedEvent event) {
        System.out.println("Collections node event:" + event);
        
      }});
  }

  private void setUpCollectionsNode() throws KeeperException, InterruptedException {
    try {
      if (!zkClient.exists(COLLECTIONS_ZKNODE)) {
        if (log.isInfoEnabled()) {
          log.info("creating zk collections node:" + COLLECTIONS_ZKNODE);
        }
        // makes collections zkNode if it doesn't exist
        zkClient.makePath(COLLECTIONS_ZKNODE, CreateMode.PERSISTENT, null);
      }
    } catch (KeeperException e) {
      // its okay if another beats us creating the node
      if (e.code() != KeeperException.Code.NODEEXISTS) {
        log.error("ZooKeeper Exception", e);
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
            "ZooKeeper Exception", e);
      }
    } catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
    }
    
    log.info("Start watching collections node for changes");
    zkClient.exists(COLLECTIONS_ZKNODE, new Watcher(){

      public void process(WatchedEvent event) {
        // nocommit
        System.out.println("collections node changed: "+ event);
        if(event.getType() == EventType.NodeDataChanged) {
          // no commit - we may have a new collection, watch the shards node for them
          
          // re-watch
          try {
            zkClient.exists(COLLECTIONS_ZKNODE, this);
          } catch (KeeperException e) {
            log.error("ZooKeeper Exception", e);
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                "ZooKeeper Exception", e);
          } catch (InterruptedException e) {
            // Restore the interrupted status
            Thread.currentThread().interrupt();
          }
        }

      }});
  }

}
