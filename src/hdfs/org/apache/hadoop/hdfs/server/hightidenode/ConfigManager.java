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

package org.apache.hadoop.hdfs.server.hightidenode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.HashSet;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.PolicyInfo;
import org.apache.hadoop.hdfs.protocol.PolicyInfo.PathInfo;

/**
 * Maintains the configuration xml file that is read into memory.
 */
class ConfigManager {
  public static final Log LOG = LogFactory.getLog(
    "org.apache.hadoop.hdfs.server.hightidenode.ConfigManager");

  /** Time to wait between checks of the config file */
  public static final long RELOAD_INTERVAL = 10 * 1000;

  /** Time to wait between successive runs of all policies */
  public static final long RESCAN_INTERVAL = 3600 * 1000;
  
  /**
   * Time to wait after the config file has been modified before reloading it
   * (this is done to prevent loading a file that hasn't been fully written).
   */
  public static final long RELOAD_WAIT = 5 * 1000; 
  
  private Configuration conf;    // Hadoop configuration
  private String configFileName; // Path to config XML file
  
  private long lastReloadAttempt; // Last time we tried to reload the config file
  private long lastSuccessfulReload; // Last time we successfully reloaded config
  private boolean lastReloadAttemptFailed = false;
  private long reloadInterval = RELOAD_INTERVAL;
  private long periodicity; // time between runs of all policies

  // Reload the configuration
  private boolean doReload;
  private Thread reloadThread;
  private volatile boolean running = false;

  // Collection of all configured policies.
  Collection<PolicyInfo> allPolicies = new ArrayList<PolicyInfo>();

  public ConfigManager(Configuration conf) throws IOException, SAXException,
      HighTideConfigurationException, ClassNotFoundException, ParserConfigurationException {
    this.conf = conf;
    this.configFileName = conf.get("hightide.config.file");
    this.doReload = conf.getBoolean("hightide.config.reload", true);
    this.reloadInterval = conf.getLong("hightide.config.reload.interval", RELOAD_INTERVAL);
    if (configFileName == null) {
      String msg = "No hightide.config.file given in conf - " +
                   "the Hadoop HighTideNode cannot run. Aborting....";
      LOG.warn(msg);
      throw new IOException(msg);
    }
    reloadConfigs();
    lastSuccessfulReload = HighTideNode.now();
    lastReloadAttempt = HighTideNode.now();
    running = true;
  }
  
  /**
   * Reload config file if it hasn't been loaded in a while
   * Returns true if the file was reloaded.
   */
  public synchronized boolean reloadConfigsIfNecessary() {
    long time = HighTideNode.now();
    if (time > lastReloadAttempt + reloadInterval) {
      lastReloadAttempt = time;
      File file = null;
      try {
        file = new File(configFileName);
        long lastModified = file.lastModified();
        if (lastModified > lastSuccessfulReload &&
            time > lastModified + RELOAD_WAIT) {
          reloadConfigs();
          lastSuccessfulReload = time;
          lastReloadAttemptFailed = false;
          return true;
        }
      } catch (Exception e) {
        if (!lastReloadAttemptFailed) {
          LOG.error("Failed to reload config file - " + file +
              "will use existing configuration.", e);
        }
        lastReloadAttemptFailed = true;
      }
    }
    return false;
  }
  
  /**
   * Updates the in-memory data structures from the config file. This file is
   * expected to be in the following whitespace-separated format:
   * Blank lines and lines starting with # are ignored.
   *  
   * @throws IOException if the config file cannot be read.
   * @throws HighTideConfigurationException if configuration entries are invalid.
   * @throws ClassNotFoundException if user-defined policy classes cannot be loaded
   * @throws ParserConfigurationException if XML parser is misconfigured.
   * @throws SAXException if config file is malformed.
   * @returns A new set of policy categories.
   */
  void reloadConfigs() throws IOException, ParserConfigurationException, 
      SAXException, ClassNotFoundException, HighTideConfigurationException {

    if (configFileName == null) {
       return;
    }
    
    File file = new File(configFileName);
    if (!file.exists()) {
      throw new HighTideConfigurationException("Configuration file " + configFileName +
                                           " does not exist.");
    }

    // Read and parse the configuration file.
    // allow include files in configuration file
    DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
    docBuilderFactory.setIgnoringComments(true);
    docBuilderFactory.setNamespaceAware(true);
    try {
      docBuilderFactory.setXIncludeAware(true);
    } catch (UnsupportedOperationException e) {
        LOG.error("Failed to set setXIncludeAware(true) for raid parser "
                + docBuilderFactory + ":" + e, e);
    }
    LOG.error("Reloading config file " + file);

    DocumentBuilder builder = docBuilderFactory.newDocumentBuilder();
    Document doc = builder.parse(file);
    Element root = doc.getDocumentElement();
    if (!"configuration".equalsIgnoreCase(root.getTagName()))
      throw new HighTideConfigurationException("Bad configuration file: " + 
          "top-level element not <configuration>");
    NodeList elements = root.getChildNodes();

    Set<PolicyInfo> existingPolicies = new HashSet<PolicyInfo>();

    // loop through all the configured source paths.
    for (int i = 0; i < elements.getLength(); i++) {
      Node node = elements.item(i);
      if (!(node instanceof Element)) {
        continue;
      }
      Element element = (Element)node;
      String elementTagName = element.getTagName();
      String policyName = null;
      if ("srcPath".equalsIgnoreCase(elementTagName)) {
        String srcPathPrefix = element.getAttribute("name");

        if (srcPathPrefix == null || srcPathPrefix.length() == 0) {
          throw new HighTideConfigurationException("Bad configuration file: " + 
            "srcPath node does not have a path.");
        }
        PolicyInfo policyInfo = new PolicyInfo(srcPathPrefix, conf);
        policyName = srcPathPrefix;
        Properties policyProperties;

        // loop through all elements of this policy
        NodeList policies = element.getChildNodes();
        for (int j = 0; j < policies.getLength(); j++) {
          Node node1 = policies.item(j);
          if (!(node1 instanceof Element)) {
            continue;
          }
          Element policy = (Element)node1;
          if ((!"property".equalsIgnoreCase(policy.getTagName())) &&
              (!"destPath".equalsIgnoreCase(policy.getTagName()))) {
            throw new HighTideConfigurationException("Bad configuration file: " + 
              "Expecting <property> or <destPath> for srcPath " + srcPathPrefix +
              " but found " + policy.getTagName());
          }

          // parse the <destPath> items
          if ("destPath".equalsIgnoreCase(policy.getTagName())) {
            String destPath = policy.getAttribute("name");
            if (destPath == null) {
              throw new HighTideConfigurationException("Bad configuration file: " + 
                "<destPath> tag should have an attribute named 'name'.");
            }
            NodeList properties = policy.getChildNodes();
            Properties destProperties = new Properties();
            for (int k = 0; k < properties.getLength(); k++) {
              Node node2 = properties.item(k);
              if (!(node2 instanceof Element)) {
                continue;
              }
              Element property = (Element)node2;
              String propertyName = property.getTagName();
              if (!("property".equalsIgnoreCase(propertyName))) {
                throw new HighTideConfigurationException("Bad configuration file: " + 
                  "<destPath> can have only <property> children." +
                  " but found " + propertyName);
              }
              NodeList nl = property.getChildNodes();
              String pname=null,pvalue=null;
              for (int l = 0; l < nl.getLength(); l++){
                Node node3 = nl.item(l);
                if (!(node3 instanceof Element)) {
                  continue;
                }
                Element item = (Element) node3;
                String itemName = item.getTagName();
                if ("name".equalsIgnoreCase(itemName)){
                  pname = ((Text)item.getFirstChild()).getData().trim();
                } else if ("value".equalsIgnoreCase(itemName)){
                  pvalue = ((Text)item.getFirstChild()).getData().trim();
                }
              }
              if (pname == null || pvalue == null) {
                throw new HighTideConfigurationException("Bad configuration file: " + 
                  "All property for destPath " +  destPath + 
                  "  must have name and value ");
              }
              LOG.info(policyName + "." + pname + " = " + pvalue);
              destProperties.setProperty(pname, pvalue);
            }
            policyInfo.addDestPath(destPath, destProperties); 

          } else if ("property".equalsIgnoreCase(policy.getTagName())) {
            Element property = (Element)node1;
            NodeList nl = property.getChildNodes();
            String pname=null,pvalue=null;
            for (int l = 0; l < nl.getLength(); l++){
              Node node3 = nl.item(l);
              if (!(node3 instanceof Element)) {
               continue;
              }
              Element item = (Element) node3;
              String itemName = item.getTagName();
              if ("name".equalsIgnoreCase(itemName)){
                pname = ((Text)item.getFirstChild()).getData().trim();
              } else if ("value".equalsIgnoreCase(itemName)){
                pvalue = ((Text)item.getFirstChild()).getData().trim();
              }
            }
            if (pname == null || pvalue == null) {
              throw new HighTideConfigurationException("Bad configuration file: " + 
                "All property for srcPath " + srcPathPrefix +
                " must have name and value ");
            }
            LOG.info(policyName + "." + pname + " = " + pvalue);
            policyInfo.setProperty(pname,pvalue);
          }
        }
        existingPolicies.add(policyInfo);
      } else {
        throw new HighTideConfigurationException("Bad configuration file: " + 
          "The top level item must be srcPath but found " + elementTagName);
      }
    }
    validateAllPolicies(existingPolicies);
    setAllPolicies(existingPolicies);
    return;
  }

  /**
   * Get a collection of all policies
   */
  public synchronized Collection<PolicyInfo> getAllPolicies() {
    return allPolicies;
  }
  
  /**
   * Set a collection of all policies
   */
  protected synchronized void setAllPolicies(Collection<PolicyInfo> value) {
    this.allPolicies = value;
  }

  /**
   * Validate a collection of policies
   */
  private void validateAllPolicies(Collection<PolicyInfo> all) 
    throws IOException, NumberFormatException {
    for (PolicyInfo pinfo: all) {
      Path srcPath = pinfo.getSrcPath();
      if (srcPath == null) {
        throw new IOException("Unable to find srcPath in policy.");
      }
      if (pinfo.getProperty("replication") == null) {
        throw new IOException("Unable to find replication in policy." +  
                              srcPath);
      }
      int repl = Integer.parseInt(pinfo.getProperty("replication"));
      if (pinfo.getProperty("modTimePeriod") == null) {
        throw new IOException("Unable to find modTimePeriod in policy." + 
                              srcPath);
      }
      long value = Long.parseLong(pinfo.getProperty("modTimePeriod"));
      List<PathInfo> dpaths = pinfo.getDestPaths();
      if (dpaths == null || dpaths.size() == 0) {
        throw new IOException("Unable to find dest in policy." +  srcPath);
      }
      for (PathInfo pp: dpaths) {
        if (pp.getPath() == null)  {
          throw new IOException("Unable to find valid destPath in policy " +
                                srcPath);
        }
        if (pp.getProperty("replication") == null) {
          throw new IOException("Unable to find dest replication in policy." +  
                                srcPath);
        }
        repl = Integer.parseInt(pp.getProperty("replication"));
      }
    }
  }

  /**
   * Start a background thread to reload the config file
   */
  void startReload() {
    if (doReload) {
      reloadThread = new UpdateThread();
      reloadThread.start();
    }
  }

  /**
   * Stop the background thread that reload the config file
   */
  void stopReload() throws InterruptedException {
    if (reloadThread != null) {
      running = false;
      reloadThread.interrupt();
      reloadThread.join();
      reloadThread = null;
    }
  }

  /**
   * A thread which reloads the config file.
   */
  private class UpdateThread extends Thread {
    private UpdateThread() {
      super("HighTideNode config reload thread");
    }

    public void run() {
      while (running) {
        try {
          Thread.sleep(reloadInterval);
          reloadConfigsIfNecessary();
        } catch (InterruptedException e) {
          // do nothing
        } catch (Exception e) {
          LOG.error("Failed to reload config file ", e);
        }
      }
    }
  }

}
