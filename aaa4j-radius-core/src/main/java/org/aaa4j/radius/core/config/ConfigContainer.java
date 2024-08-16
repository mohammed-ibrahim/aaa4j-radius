package org.aaa4j.radius.core.config;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

public class ConfigContainer {

  private static final Logger log = LoggerFactory.getLogger(ConfigContainer.class);

  private Map<String, String> properties;

  private IDiskFileManager iDiskFileManager;

  public ConfigContainer(IDiskFileManager iDiskFileManager) {
    this.properties = new HashMap<>();
    this.iDiskFileManager = iDiskFileManager;
    loadFileToProperties();
  }

  private void loadFileToProperties() {
    synchronized (properties) {
      properties.clear();
      try {
        Properties prop = new Properties();
        String fileContents = this.iDiskFileManager.getNewContent();
        InputStream stream = new ByteArrayInputStream(fileContents.getBytes(StandardCharsets.UTF_8));
        prop.load(stream);
        prop.forEach((k, v) -> {
          properties.put(k.toString(), v.toString());
        });
        stream.close();
      } catch (IOException ex) {
        ex.printStackTrace();
        throw new RuntimeException(ex);
      }
    }
  }

  public void refresh() {
    if (this.iDiskFileManager.contentsChanged()) {
      loadFileToProperties();
      log.info("Configuration reloaded!");
    }
  }
  public String getProperty(String key) {
    String value = null;

    synchronized (properties) {
      value = properties.get(key);
    }

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
}
