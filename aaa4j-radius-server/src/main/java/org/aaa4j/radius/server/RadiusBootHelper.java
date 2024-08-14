package org.aaa4j.radius.server;


import org.aaa4j.radius.core.attribute.Attribute;
import org.aaa4j.radius.core.attribute.Data;
import org.aaa4j.radius.core.attribute.StandardAttribute;
import org.aaa4j.radius.core.attribute.StringData;
import org.aaa4j.radius.core.attribute.TextData;
import org.aaa4j.radius.core.attribute.attributes.UserName;
import org.aaa4j.radius.core.attribute.attributes.UserPassword;
import org.aaa4j.radius.core.packet.Packet;
import org.aaa4j.radius.core.packet.packets.AccessAccept;
import org.aaa4j.radius.core.packet.packets.AccessChallenge;
import org.aaa4j.radius.core.packet.packets.AccessReject;
import org.aaa4j.radius.core.packet.packets.AccessRequest;
import org.aaa4j.radius.server.servers.UdpRadiusServer;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class RadiusBootHelper {
  private static final Logger log = LoggerFactory.getLogger(RadiusBootHelper.class);

  private static int globalDelayInSec = 0;

  private static ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);

  private static Map<String, String> usernameAndPasswordStaticStore = null;

  private static ConfigurationManager configurationManager;

  public static AtomicInteger numRequestsReceived = new AtomicInteger(0);
  public static AtomicInteger numOfResponded = new AtomicInteger(0);

  public static AtomicInteger numRequestsInLastEpoch = new AtomicInteger(0);

  private static final LastNAverageCounter lastNAverageCounter = new LastNAverageCounter(100);

  public static void main(String[] args) throws Exception {
    log.debug("Starting Radius Server");

    configurationManager = new ConfigurationManager(loadRequiredSystemProperty("radius.server.config.file"));

    String staticFileName = System.getProperty("load.static.seeded.list");
    if (StringUtils.isNotBlank(staticFileName)) {
      log.debug("Running in static mode");

      usernameAndPasswordStaticStore = new HashMap<>();
      try (InputStream input = new FileInputStream(staticFileName)) {
        Properties prop = new Properties();
        prop.load(input);

        prop.forEach((k, v) -> {
          usernameAndPasswordStaticStore.put(k.toString(), v.toString());
        });

      } catch (IOException ex) {
        ex.printStackTrace();
        throw new RuntimeException(ex);
      }

    } else {
      log.debug("Static mode is disabled");
    }

    Optional<Integer> propertyAsInteger = configurationManager.getPropertyAsInteger("radius.server.port");
    if (!propertyAsInteger.isPresent()) {
      log.error("radius.server.port not configured");
      throw new RuntimeException("radius.server.port not configured");
    }

    String localhost = configurationManager.getProperty("radius.server.localhost");
    InetSocketAddress inetSocketAddress = null;
    if (StringUtils.isNotBlank(localhost) && Boolean.valueOf(localhost)) {
      inetSocketAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), propertyAsInteger.get());
      log.info("Binding at localhost port: {}", inetSocketAddress.getAddress().getHostAddress());
    } else {
      inetSocketAddress = new InetSocketAddress(propertyAsInteger.get());
      log.info("Binding at public port: {}", inetSocketAddress.getAddress().getHostAddress());
    }

    Optional<Integer> nServerThreads = configurationManager.getPropertyAsInteger("radius.server.num.threads");
    if (!nServerThreads.isPresent()) {
      throw new RuntimeException("Property radius.server.num.threads not configured");
    }

    RadiusServer radiusServer = UdpRadiusServer.newBuilder()
        .bindAddress(inetSocketAddress)
        .executor(new ForkJoinPool(nServerThreads.get()))
        .handler(new RadiusHandler())
        .build();

    scheduledExecutorService.scheduleAtFixedRate(() -> {
      printStatus();
        }, 0, 10000, TimeUnit.MILLISECONDS);

    log.info("Started server on port: {}", propertyAsInteger.get());
    radiusServer.start();
  }

  private static void printStatus() {
    try {
      log.info("Received: {} Responded: {} l100RespTime: {} rps: {}",
          numRequestsReceived.get(), numOfResponded.get(),
          lastNAverageCounter.average(), numRequestsInLastEpoch.getAndIncrement());

      numRequestsInLastEpoch.set(0);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static String loadRequiredSystemProperty(String propertyName) {

    String propertyValue = System.getProperty(propertyName);

    if (StringUtils.isBlank(propertyValue)) {
      log.error("Invalid value for: {}", propertyName);
      System.exit(1);
    }

    return propertyValue;
  }

  private static final Optional<Integer> getPropertyFromConfigurationFileAsInteger(String key) {
    String strValue = configurationManager.getProperty(key);

    if (StringUtils.isBlank(strValue)) {
      return Optional.empty();
    }

    Integer intValue = Integer.parseInt(strValue);
    return Optional.of(intValue);
  }

  private static class RadiusHandler implements RadiusServer.Handler {

    @Override
    public byte[] handleClient(InetAddress clientAddress) {
      String sharedSecret = configurationManager.getProperty("radius.server.sharedsecret");
      if (StringUtils.isBlank(sharedSecret)) {
        log.error("Shared secret is blank");
        throw new RuntimeException("Shared secret is blank");
      }

      log.debug("Shared secret is: {}", Hex.encodeHexString(sharedSecret.getBytes()));
      return sharedSecret.getBytes(UTF_8);
    }

    @Override
    public Packet handlePacket(InetAddress clientAddress, Packet requestPacket) {
      numRequestsReceived.incrementAndGet();
      numRequestsInLastEpoch.incrementAndGet();

      StopWatch stopWatch = StopWatch.createStarted();
      Pair<String, Packet> response = preparePacketForUsernameAndPassword(requestPacket);
      stopWatch.stop();

      numOfResponded.incrementAndGet();
      lastNAverageCounter.add((int)stopWatch.getTime(TimeUnit.MILLISECONDS));
      log.info("RESPONSE: user: {} and Response: {}", response.getLeft(), getDetailedResponseType(response.getRight()));
      return response.getRight();
    }

    private Pair<String, Packet> preparePacketForUsernameAndPassword(Packet requestPacket) {
      String uuid = UUID.randomUUID().toString();
      log.debug("Starting: uuid: {} class: {}", uuid, requestPacket.getClass());

      if (requestPacket instanceof AccessRequest) {
        return processAccessRequest(requestPacket, uuid);
      }

      log.error("AccessRequest is supported: Class not supported: {}", requestPacket.getClass().getName());
      return Pair.of("null", new AccessReject());
    }

    private Pair<String, Packet> processAccessRequest(Packet requestPacket, String uuid) {
      Optional<UserName> userNameAttribute = requestPacket.getAttribute(UserName.class);
      Optional<UserPassword> userPasswordAttribute = requestPacket.getAttribute(UserPassword.class);
      List<Attribute<?>> stateIdAttributes = requestPacket.getAttributes().stream().filter(a -> a.getType().head() == 24).collect(Collectors.toList());

      if (stateIdAttributes.size() > 0) {
        return processStateRequest(uuid, stateIdAttributes);
      } else if (userNameAttribute.isPresent() && userPasswordAttribute.isPresent()) {
        return processUsernameAndPasscodeRequest(uuid, userNameAttribute, userPasswordAttribute);
      }

      log.error("Incoming request is not a Username/Passcode request or State request, Sending AccessReject");
      return Pair.of("null", new AccessReject());
    }

    private Pair<String, Packet> processUsernameAndPasscodeRequest(String uuid, Optional<UserName> userNameAttribute, Optional<UserPassword> userPasswordAttribute) {
      String username = userNameAttribute.get().getData().getValue();

      log.info("RECEIVED: USERNAME: {}", username);
      String password = new String(userPasswordAttribute.get().getData().getValue());
      log.debug("password is: {}, hex-password: {}", password, Hex.encodeHexString(password.getBytes()));

      String passwordUtf8 = new String(userPasswordAttribute.get().getData().getValue(), UTF_8);
      log.trace("UTF8 password is: {}, hex-password: {}", passwordUtf8, Hex.encodeHexString(passwordUtf8.getBytes()));

      if (usernameAndPasswordStaticStore != null) {
        Packet packet = processStaticSeededUserRequest(username, password);
        return Pair.of(username, packet);
      } else {
        Packet packet = preparePacketForUsernameAndPassword(uuid, username, passwordUtf8);
        log.debug("Got packet of class: {}", packet.getClass());
        return Pair.of(username, packet);
      }
    }

    public static String getDetailedResponseType(Packet packet) {
      if (packet instanceof AccessAccept) {
        return "AccessAccept";
      }

      if (packet instanceof AccessReject) {
        return "AccessReject";
      }

      if (packet instanceof AccessChallenge) {
        return "AccessChallenge";
      }

      return "CannotBeDetermined";
    }

    private Packet processStaticSeededUserRequest(String username, String userSuppliedPassword) {

      if (!usernameAndPasswordStaticStore.containsKey(username)) {
        log.error("User not mentioned in static user list");
        return new AccessReject();
      }

      String passwordStoredInStaticList = usernameAndPasswordStaticStore.get(username);
      if (StringUtils.equals(userSuppliedPassword, passwordStoredInStaticList)) {
        log.debug("Creds for user: {} matched in static list", username);
        return new AccessAccept();
      }

      log.error("Password doesn't match in static user list for user: {}", username);
      return new AccessReject();
    }

    private Pair<String, Packet> processStateRequest(String uuid, List<Attribute<?>> stateIdAttributes) {
      log.debug("Processing state request for uuid: {}", uuid);
      Data data = stateIdAttributes.get(0).getData();

      String value = null;
      if (data instanceof StringData) {
        log.debug("data instanceof StringData true");
        StringData stringData = (StringData) data;
        value = new String(stringData.getValue());
      } else {
        log.debug("converting unparsable string data to direct string");
        value = data.toString();
      }

      log.info("RECEIVED: STATE: {}", value);
      return Pair.of("StateIdRequest", preparePacketForUsernameAndPassword(uuid, value, null));
    }


    private Optional<String> attemptToFetchGroupForUser(String username) {
      String prefixGroupsCsv = configurationManager.getProperty("prefix.patterns.csv");
      String [] parts = prefixGroupsCsv.split(",");

      for (String part : parts) {
        if (username.startsWith(part)) {
          return Optional.of(part);
        }
      }

      return Optional.empty();
    }

    private Packet preparePacketForUsernameAndPassword(String uuid, String username, String password) {
      log.debug("User: {}, pass: {}, uuid: {}", username, password, uuid);
      Optional<String> groupOptional = attemptToFetchGroupForUser(username);
      Pair<String, Integer> responseTypeAndDelayInMsPair = null;
      if (groupOptional.isPresent()) {
        responseTypeAndDelayInMsPair = preparePacketForGroupUser(groupOptional.get(), username);
      } else {
        responseTypeAndDelayInMsPair = preparePacketForIndividualUser(username);
      }

      String responseType = "r";
      if (StringUtils.isNotBlank(responseTypeAndDelayInMsPair.getLeft())) {
        responseType = responseTypeAndDelayInMsPair.getLeft();
      }
      int delayInMs = responseTypeAndDelayInMsPair.getRight();
      String responseCode = computeResponseCodeFromSeededResponseType(responseType);
      sleepIfNeeded(delayInMs);

      return generatePacketFromResponseCode(uuid, username, responseCode);
    }

    private static Packet generatePacketFromResponseCode(String uuid, String username, String responseCode) {
      switch (responseCode) {
        case "a":
          log.debug("Seeded accept response, uuid: {} username: {}", uuid, username);
          StandardAttribute classAttribute = new StandardAttribute(25, new StringData("ClassData-12345".getBytes()));
          StandardAttribute classAttribute1 = new StandardAttribute(25, new StringData(RandomStringUtils.randomAlphabetic(10).getBytes()));
          StandardAttribute classAttribute2 = new StandardAttribute(25, new StringData("".getBytes()));
          return new AccessAccept(Arrays.asList(classAttribute, classAttribute1, classAttribute2));

        case "c":
          log.debug("Seeded challenge response, uuid: {} username: {}", uuid, username);
          String jumpToUserCode = String.format("rad.user.%s.after.challenge.jump.user", username).toLowerCase();
          String jumpToUserValue = configurationManager.getProperty(jumpToUserCode);
          log.debug("Got jump user value: {}", jumpToUserValue);
          if (StringUtils.isBlank(jumpToUserValue)) {
            log.debug("Jump to user value is empty!! not a good situation.");
            jumpToUserValue = "None";
          }

          StandardAttribute standardAttribute = new StandardAttribute(24, new StringData(jumpToUserValue.getBytes()));

          String challengeHintMessage = "Default blank hint message";
          String challengeHintPropertyMessageFromConfiguration = configurationManager.getProperty("challenege.hint.message");
          if (StringUtils.isNotBlank(challengeHintPropertyMessageFromConfiguration)) {
            log.debug("Challenge hint is: {}", challengeHintPropertyMessageFromConfiguration);
            challengeHintMessage = challengeHintPropertyMessageFromConfiguration;
          }
          StandardAttribute stringAttribute = new StandardAttribute(18, new TextData(challengeHintMessage));
          return new AccessChallenge(Arrays.asList(standardAttribute, stringAttribute));

        default:
          log.error("Sending reject response to id: {} username: {}", uuid, username);
          return new AccessReject();
      }
    }

    private static void sleepIfNeeded(int delayInMs) {
      if (delayInMs > 0) {
        try {
          Thread.sleep(delayInMs);
        } catch (InterruptedException e) {
          log.error("Error while sleeping.");
          throw new RuntimeException(e);
        }
      }
    }

    private static String computeResponseCodeFromSeededResponseType(String responseType) {
      if (StringUtils.isNotBlank(responseType)) {
        if (responseType.toLowerCase().startsWith("a")) {
          return "a";
        }

        if (responseType.toLowerCase().startsWith("c")) {
          return "c";
        }
      }
      return "r";
    }

    private Pair<String, Integer> preparePacketForIndividualUser(String username) {
      String delayKeyInSeconds = String.format("rad.user.%s.delay.seconds", username).toLowerCase();
      String responseKey = String.format("rad.user.%s.response", username).toLowerCase();
      Optional<Integer> optionalDelayInSeconds = getPropertyFromConfigurationFileAsInteger(delayKeyInSeconds);
      Integer delayInMs = -1;

      if (optionalDelayInSeconds.isPresent()) {
        int delayInSec = optionalDelayInSeconds.get();

        if (delayInSec > 0) {
          delayInMs = delayInSec * 1000;
        }
      }

      String responseType = configurationManager.getProperty(responseKey);
      log.debug("User: {} not part of any group, response code: {}, delay in ms: {}", username, responseType, delayInMs);
      return Pair.of(responseType, delayInMs);
    }

    private Pair<String, Integer> preparePacketForGroupUser(String groupNameStr, String username) {
      String minDelayKey = String.format("%s.delay.min.ms", groupNameStr).toLowerCase();
      String maxDelayKey = String.format("%s.delay.max.ms", groupNameStr).toLowerCase();

      Optional<Integer> minDelayOptional = getPropertyFromConfigurationFileAsInteger(minDelayKey);
      Optional<Integer> maxDelayOptional = getPropertyFromConfigurationFileAsInteger(maxDelayKey);

      if (!minDelayOptional.isPresent()) {
        throw new RuntimeException(minDelayKey + " not configured.");
      }

      if (!maxDelayOptional.isPresent()) {
        throw new RuntimeException(maxDelayKey + " not configured.");
      }

      Random r = new Random();
      int low = minDelayOptional.get();
      int high = maxDelayOptional.get();
      int result = r.nextInt(high-low) + low;

      Integer delayInMs = result;
      log.debug("User: {} belongs to group: {} and sleep is: {}", username, groupNameStr, delayInMs);

      String responseKey = String.format("%s.resp.code", groupNameStr).toLowerCase();
      String responseType = configurationManager.getProperty(responseKey);

      return Pair.of(responseType, delayInMs);
    }

    private void iterativeSleep(long numSecondsToSleep) {

      if (numSecondsToSleep < 1) {
        log.debug("Sleep not required");
        return;
      }

      try {

        for (int i=0; i < numSecondsToSleep; i++) {
          Date startSleep = new Date();
          Thread.sleep(1000);
          Date endSleep = new Date();
          log.debug("Sleeping: Start: {} End: {}, currentIteration: {} numSecondsToSleep: {}", startSleep, endSleep, (i+1), numSecondsToSleep);
        }
      } catch (Exception e) {
        log.error("Error while sleeping ", e);
      }
    }

  }



}