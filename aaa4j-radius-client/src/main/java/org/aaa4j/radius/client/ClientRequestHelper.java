package org.aaa4j.radius.client;

import org.aaa4j.radius.client.clients.UdpRadiusClient;
import org.aaa4j.radius.core.attribute.Attribute;
import org.aaa4j.radius.core.attribute.Data;
import org.aaa4j.radius.core.attribute.StandardAttribute;
import org.aaa4j.radius.core.attribute.StringData;
import org.aaa4j.radius.core.attribute.TextData;
import org.aaa4j.radius.core.attribute.attributes.NasIdentifier;
import org.aaa4j.radius.core.attribute.attributes.UserName;
import org.aaa4j.radius.core.attribute.attributes.UserPassword;
import org.aaa4j.radius.core.packet.Packet;
import org.aaa4j.radius.core.packet.packets.AccessAccept;
import org.aaa4j.radius.core.packet.packets.AccessChallenge;
import org.aaa4j.radius.core.packet.packets.AccessRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ClientRequestHelper {

  private static final Logger log = LoggerFactory.getLogger(ClientRequestHelper.class);

  public static void main(String[] args) {

    log.info("Loading properties");
    String sharedSecret = loadRequiredProperty("rad.shared.secret");
    String serverAddr = loadRequiredProperty("rad.server.addr");
    String serverPortStr = loadRequiredProperty("rad.server.port");
    String username = loadRequiredProperty("rad.username");
    String password = loadRequiredProperty("rad.password");
    String textData = loadRequiredProperty("rad.nasIdentifier");
    String timeOutStr = loadRequiredProperty("rad.client.request.timeout.seconds");

    int serverPort = Integer.parseInt(serverPortStr);
    int timeout = Integer.parseInt(timeOutStr);

    log.info("Building request..");
    sendRadiusRequest(sharedSecret, serverAddr, username, password, textData, null, serverPort, timeout);
  }

  public static void sendRadiusRequest(String sharedSecret, String serverAddr, String username,
                                        String password, String nasIdentifier, String stateData, int serverPort, int timeout) {

    StopWatch stopWatch = StopWatch.createStarted();
    log.info("Starting for user: {} nasIdentifier: {}", username, nasIdentifier);
    RadiusClient radiusClient = UdpRadiusClient.newBuilder()
        .secret(sharedSecret.getBytes(UTF_8))
        .retransmissionStrategy(new IntervalRetransmissionStrategy(1, Duration.ofSeconds(timeout)))
        .address(new InetSocketAddress(serverAddr, serverPort))
        .build();

    List standardAttributes = new ArrayList<>(Arrays.asList(new UserName(new TextData(username)),
        new UserPassword(new StringData(password.getBytes(UTF_8))),
        new NasIdentifier(new TextData(nasIdentifier))));

    if (StringUtils.isNotBlank(stateData)) {
      StandardAttribute stateIdAttribute = new StandardAttribute(24, new StringData(stateData.getBytes()));
      standardAttributes.add(stateIdAttribute);
    }

    AccessRequest accessRequest = new AccessRequest(standardAttributes);

    try {
      log.info("Sending request for user: {}", username);
      Packet responsePacket = radiusClient.send(accessRequest);
      String clazz = responsePacket == null ? "null" : responsePacket.getClass().getName();
      log.info("Received response for user: {} class: {}", username, clazz);

      if (responsePacket instanceof AccessAccept) {
        log.info("Accepted for user: {}", username);
      } else if (responsePacket instanceof AccessChallenge) {
        String stateId = evaluateChallenge(responsePacket);
        if (StringUtils.isNotBlank(stateId)) {
          System.out.println(String.format("Received stateId from challenge: %s", stateId));

          String inputFromUser = null;
          if (checkIfPropertyConfigured("rad.use.pre.seeded.password") && Boolean.valueOf(loadRequiredProperty("rad.use.pre.seeded.password"))) {
            System.out.println(String.format("Using pre seeded password: " + password));
            inputFromUser = password;
          } else {
            System.out.print("Please enter challenge authentication: ");
            inputFromUser = System.console().readLine();
            System.out.println("Input challenge from user: " + inputFromUser);
          }
          sendRadiusRequest(sharedSecret, serverAddr, username, inputFromUser, nasIdentifier, stateId, serverPort, timeout);
        }
      } else {
        log.info("Rejected or other state for user: {}", username);
      }
    }
    catch (RadiusClientException e) {
      e.printStackTrace();
    }

    stopWatch.stop();
    log.info("Time taken: {} ms", stopWatch.getTime(TimeUnit.MILLISECONDS));
  }

  private static String evaluateChallenge(Packet responsePacket) {
    System.out.println("Challenge");
    List<Attribute<?>> stateIdAttributes = responsePacket.getAttributes().stream().filter(a -> a.getType().head() == 24).collect(Collectors.toList());
    if (stateIdAttributes.size() > 0) {
      Data data = stateIdAttributes.get(0).getData();

      String value = null;
      if (data instanceof StringData) {
        StringData stringData = (StringData) data;
        value = new String(stringData.getValue());
      } else {
        value = data.toString();
      }

      System.out.println(String.format("StateId: %s", value));
      return value;
    } else {
      System.out.println(String.format("No state id found."));
    }

    return null;
  }

  private static String loadRequiredProperty(String propertyName) {

    String propertyValue = System.getProperty(propertyName);

    if (StringUtils.isBlank(propertyValue)) {
      System.out.println("Invalid value for: " + propertyName);
      System.exit(1);
    }

    return propertyValue;
  }

  private static boolean checkIfPropertyConfigured(String propertyName) {
    String propertyValue = System.getProperty(propertyName);
    return StringUtils.isNotBlank(propertyValue);
  }
}
