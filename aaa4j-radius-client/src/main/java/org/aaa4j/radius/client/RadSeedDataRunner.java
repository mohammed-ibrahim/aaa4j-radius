package org.aaa4j.radius.client;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RadSeedDataRunner {

  private static final Logger log = LoggerFactory.getLogger(RadSeedDataRunner.class);
  public static void main(String[] args) {

    String configurationFilePath = ClientRequestFromProperties.loadRequiredSystemProperty("radmain.config.file");
    log.info("Trying to load configuration file: {}", configurationFilePath);
    Map<String, String> properties = ClientRequestFromProperties.loadPropertiesFileToMap(configurationFilePath);
    String serverHost = ClientRequestFromProperties.getProperty("radius.server.host", properties);
    int radiusServerPort = ClientRequestFromProperties.getPropertyAsInteger("radius.server.host.port", properties);
    int timeOutInSeconds = ClientRequestFromProperties.getPropertyAsInteger("radius.server.timeout.seconds", properties);
    String sharedSecret = ClientRequestFromProperties.getProperty("radius.server.shared.secret", properties);

    List<Pair<String, String>> data = getSeededData();
    int sizeLimit = getSizeLimit(data.size());

    for (int i=0; i< sizeLimit; i++) {
      Pair<String, String> row = data.get(i);
      String username = row.getKey();
      String password = row.getValue();
      String nasIdentifier = RandomStringUtils.randomAlphabetic(10);
      try {
        log.info("Sending seeded request iteration: {}/{} username: {} passwordhex: {} nasIdentifier: {}", (i+1), sizeLimit, username, Hex.encodeHexString(password.getBytes()), nasIdentifier);
        ClientRequestHelper.sendRadiusRequest(sharedSecret, serverHost, username, password, nasIdentifier, null, radiusServerPort, timeOutInSeconds);
      } catch (Exception e) {
        log.error("Failed radius request: ", e);
      }
    }

  }

  private static int getSizeLimit(int size) {
    String limitStrProperty = System.getProperty("seeddatarunner.user.limit");

    if (StringUtils.isNotBlank(limitStrProperty)) {
      try {
        int limitInArgs = Integer.parseInt(limitStrProperty);
        return limitInArgs;
      } catch (Exception e) {
        log.error("Unable to parse jvm args for limit", e);
        return size;
      }
    }

    return size;
  }

  private static List<Pair<String, String>> getSeededData() {
    String configurationFilePath = ClientRequestFromProperties.loadRequiredSystemProperty("radmain.seeded.file");
    log.info("Trying to load seeded configuration file: {}", configurationFilePath);
    List<Pair<String, String>> users = loadPropertiesFilePair(configurationFilePath);
    log.info("Size of seed data is: {}", users.size());
    return users;
  }

  public static List<Pair<String, String>> loadPropertiesFilePair(String configurationFilePath) {
    List<Pair<String, String>> users = new ArrayList<>();
    try {

      List<String> lines = FileUtils.readLines(new File(configurationFilePath), StandardCharsets.UTF_8);
      lines.forEach(line -> {

        if (StringUtils.isNotBlank(line)) {
          String[] parts = line.split("=");
          if (parts.length == 2) {
            users.add(Pair.of(parts[0], parts[1]));
          } else {
            log.error("Invalid line: {}", line);
          }
        }
      });

      return users;
    } catch (IOException ex) {
      ex.printStackTrace();
      throw new RuntimeException(ex);
    }
  }
}
