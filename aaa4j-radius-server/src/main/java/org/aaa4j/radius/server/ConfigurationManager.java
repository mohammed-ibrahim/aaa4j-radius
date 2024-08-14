package org.aaa4j.radius.server;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ConfigurationManager implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(ConfigurationManager.class);
  private ScheduledExecutorService executorService;
  private String previouslyKnownMd5;

  private String configFilePath;

  private Map<String, String> properties;

  public ConfigurationManager(String filename) {
    this.configFilePath = filename;
    this.properties = new ConcurrentHashMap<>();
    this.executorService = Executors.newSingleThreadScheduledExecutor();
    this.forceLoadConfigurationAndUpdateFileMd5();
    log.debug("Scheduling md5 verifier.");
    executorService.scheduleWithFixedDelay(this, 10, 10, TimeUnit.SECONDS);
  }

  private void forceLoadConfigurationAndUpdateFileMd5() {
    try (InputStream input = new FileInputStream(this.configFilePath)) {
      Properties prop = new Properties();
      prop.load(input);

      prop.forEach((k, v) -> {
        properties.put(k.toString(), v.toString());
      });

      this.previouslyKnownMd5 = getMd5HexOfConfigFile();
    } catch (IOException ex) {
      ex.printStackTrace();
      throw new RuntimeException(ex);
    }
  }

  private void reloadConfigIfRequired() {
    String recentlyCalculatedMd5 = getMd5HexOfConfigFile();

    boolean updateDone = false;
    String lastRecordedMd5 = new String(this.previouslyKnownMd5);
    if (!StringUtils.equalsIgnoreCase(this.previouslyKnownMd5, recentlyCalculatedMd5)) {
      forceLoadConfigurationAndUpdateFileMd5();
      updateDone = true;
    }

    if (updateDone) {
      log.debug("last-recorded-md5: {} current-md5: {} updated-newConfig: [{}] timestamp: {}", lastRecordedMd5, this.previouslyKnownMd5, updateDone, new Date());
    }
  }

  private String getMd5HexOfConfigFile() {
    try (InputStream is = Files.newInputStream(Paths.get(this.configFilePath))) {
      String md5 = DigestUtils.md5Hex(is);
      return md5;
    } catch (Exception e) {
      log.error("Failed to calculate md5", e);
      throw new RuntimeException(e);
    }
  }

  public String getProperty(String key) {
    String value = properties.get(key);
    if (StringUtils.isBlank(value)) {
      log.debug("Property value not found for key: {} using empty value", key);
    }

    return value;
  }

  public Optional<Integer> getPropertyAsInteger(String key) {
    String strValue = getProperty(key);

    if (StringUtils.isBlank(strValue)) {
      return Optional.empty();
    }

    try {
      Integer intValue = Integer.parseInt(strValue);
      return Optional.of(intValue);
    } catch (Exception e) {
      log.error("Property value not found for key: {} using empty value", key);
    }

    return Optional.empty();
  }

  @Override
  public void run() {
    this.reloadConfigIfRequired();
  }
}
