package org.apache.solr.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;

/**
 * Util for uploading and updating files in ZooKeeper.
 * 
 */
public class ZooPut implements Watcher {

  private ZooKeeper keeper;

  private boolean closeKeeper = true;
  
  private boolean connected = false;

  public ZooPut(String host) throws IOException {
    keeper = new ZooKeeper(host, 10000, this);
    // TODO: nocommit: this is asynchronous - think about how to deal with connection
    // lost, and other failures
    synchronized (this) {
      while (!connected) {
        try {
          this.wait();
        } catch (InterruptedException e) {
          // nocommit
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }
    }
  }

  public ZooPut(ZooKeeper keeper) throws IOException {
    this.closeKeeper = false;
    this.keeper = keeper;
  }

  public void close() throws InterruptedException {
    if (closeKeeper) {
      keeper.close();
    }
  }

  public void makePath(String path) throws KeeperException,
      InterruptedException {
    makePath(path, CreateMode.PERSISTENT);
  }

  public void makePath(String path, CreateMode createMode)
      throws KeeperException, InterruptedException {
    // nocommit
    System.out.println("make:" + path);

    if (path.startsWith("/")) {
      path = path.substring(1, path.length());
    }
    String[] paths = path.split("/");
    StringBuilder sbPath = new StringBuilder();
    for (int i = 0; i < paths.length; i++) {
      String pathPiece = paths[i];
      sbPath.append("/" + pathPiece);
      String currentPath = sbPath.toString();
      Object exists = keeper.exists(currentPath, null);
      if (exists == null) {
        CreateMode mode = CreateMode.PERSISTENT;
        if (i == paths.length - 1) {
          mode = createMode;
        }
        keeper.create(currentPath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, mode);
      }
    }
  }

  /**
   * Upload file to ZooKeeper.
   * 
   * @param path path to upload file to e.g. /solr/conf/solrconfig.xml
   * @param file path to file to be uploaded
   * @throws IOException
   * @throws KeeperException
   * @throws InterruptedException
   */
  public void putFile(String path, File file) throws IOException,
      KeeperException, InterruptedException {
    // nocommit:
    System.out.println("put:" + path + " " + file);

    makePath(path);

    String fdata = readFileAsString(file);

    Object exists = keeper.exists(path, null);
    if (exists != null) {
      keeper.setData(path, fdata.getBytes(), -1);
    } else {
      keeper.create(path, fdata.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
          CreateMode.PERSISTENT);
    }

  }

  private static String readFileAsString(File file) throws java.io.IOException {
    byte[] buffer = new byte[(int) file.length()];
    FileInputStream f = new FileInputStream(file);
    try {
      f.read(buffer);
    } finally {
      f.close();
    }
    return new String(buffer);
  }

  /**
   * 
   * TODO: ACL's ??
   * 
   * @param args
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3) {
      System.out.println("usage: zoopath solrconfig.xml {host}");
      return;
    }

    String path = args[0];
    String file = args[1];

    String host;

    if (args.length > 2) {
      host = args[2];
    } else {
      // nocommit:
      host = "localhost:2181";
    }

    ZooPut zooPut = new ZooPut(host);
    zooPut.putFile(path, new File(file));
    zooPut.close();
  }

  @Override
  public void process(WatchedEvent event) {
    // nocommit: consider how we want to accomplish this
    if (event.getState() == KeeperState.SyncConnected) {
      synchronized (this) {
        connected = true;
        this.notify();
      }
    }

  }

}
