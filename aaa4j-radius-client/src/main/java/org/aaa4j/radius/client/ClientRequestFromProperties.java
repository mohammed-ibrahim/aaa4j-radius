package org.aaa4j.radius.client;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ClientRequestFromProperties {
  private static final Logger log = LoggerFactory.getLogger(ClientRequestFromProperties.class);
  public static void main(String[] args) {

    log.info("cwd = {}, java version: {}", System.getProperty("user.dir"), System.getProperty("java.version"));

    String configurationFilePath = loadRequiredSystemProperty("radmain.config.file");
    log.info("Trying to load configuration file: {}", configurationFilePath);
    Map<String, String> properties = loadPropertiesFileToMap(configurationFilePath);
    String username = getProperty("radius.username", properties);
    String password = getProperty("radius.password", properties);
    String serverHost = getProperty("radius.server.host", properties);
    int radiusServerPort = getPropertyAsInteger("radius.server.host.port", properties);
    int timeOutInSeconds = getPropertyAsInteger("radius.server.timeout.seconds", properties);
    String sharedSecret = getProperty("radius.server.shared.secret", properties);
    String nasIdentifier = RandomStringUtils.randomAlphabetic(10);
    ClientRequestHelper.sendRadiusRequest(sharedSecret, serverHost, username, password, nasIdentifier, null, radiusServerPort, timeOutInSeconds);
  }

  public static Map<String, String> loadPropertiesFileToMap(String configurationFilePath) {
    Map<String, String> properties = new HashMap<>();

    try (InputStream input = new FileInputStream(configurationFilePath)) {
      Properties prop = new Properties();
      prop.load(input);

      prop.forEach((k, v) -> {
        properties.put(k.toString(), v.toString());
      });

      return properties;
    } catch (IOException ex) {
      ex.printStackTrace();
      throw new RuntimeException(ex);
    }
  }

  public static String loadRequiredSystemProperty(String propertyName) {
    String propertyValue = System.getProperty(propertyName);
    if (StringUtils.isBlank(propertyValue)) {
      log.error("Invalid value for: {}", propertyName);
      System.exit(1);
    }
    return propertyValue;
  }

  public static String getProperty(String key, Map<String, String> properties) {

    String value = properties.get(key);
    if (StringUtils.isBlank(value)) {
      log.error("Property value not found for key: {} using empty value", key);
      throw new RuntimeException("Key not found: " + key);
    }

    if (value.startsWith(("${"))) {
      String envKey = value.substring(2, value.length()-1);
      log.info("Variable: {} needs to be picked from env: {}", key, envKey);

      String systemPropertyValue = System.getenv(envKey);
      if (StringUtils.isNotBlank(systemPropertyValue)) {
        log.info("Property: {} found in system.", envKey);
        return systemPropertyValue;
      } else {
        throw new RuntimeException("Env Key not found: " + envKey);
      }
    }

    return value;
  }

  public static Integer getPropertyAsInteger(String key, Map<String, String> properties) {
    String strValue = getProperty(key, properties);

    if (StringUtils.isBlank(strValue)) {
      throw new RuntimeException("Key not found: " + key);
    }

    try {
      Integer intValue = Integer.parseInt(strValue);
      return intValue;
    } catch (Exception e) {
      log.error("cannot convert {} to integer, key: {}", strValue, key);
      throw new RuntimeException("Cannot convert str to int");
    }
  }

}
