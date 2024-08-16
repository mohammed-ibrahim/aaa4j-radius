package org.aaa4j.radius.core.config;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ConfigurationManager {
  private static final Logger log = LoggerFactory.getLogger(ConfigurationManager.class);

  private static ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

  private static ConfigContainer INSTANCE = setupConfigContainer();

  private static ConfigContainer setupConfigContainer() {
    DiskFileManager diskFileManager = new DiskFileManager(loadRequiredSystemProperty("radius.server.config.file"));
    ConfigContainer container = new ConfigContainer(diskFileManager);

    executorService.scheduleWithFixedDelay(() -> {
      try {
        container.refresh();
      } catch (Exception e) {
        log.error("Error while refreshing config", e);
      }
    }, 15, 15, TimeUnit.SECONDS);
    return container;
  }

  private static String loadRequiredSystemProperty(String propertyName) {

    String propertyValue = System.getProperty(propertyName);

    if (StringUtils.isBlank(propertyValue)) {
      log.error("Invalid value for: {}", propertyName);
      System.exit(1);
    }

    return propertyValue;
  }
  public static String getProperty(String key) {
    return INSTANCE.getProperty(key);
  }

  public static Optional<Integer> getPropertyAsInteger(String key) {
    return INSTANCE.getPropertyAsInteger(key);
  }


  public static boolean isTrue(String key) {
    String value = getProperty(key);
    if (StringUtils.isBlank(value)) {
      return false;
    }

    if ("1".equalsIgnoreCase(value)) {
      return true;
    }

    return Boolean.valueOf(value);
  }
}
