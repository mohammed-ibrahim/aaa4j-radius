/*
 * Copyright 2020 The AAA4J-RADIUS Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.aaa4j.radius.server.servers;

import org.aaa4j.radius.core.attribute.Data;
import org.aaa4j.radius.core.attribute.ExtendedAttribute;
import org.aaa4j.radius.core.attribute.IntegerData;
import org.aaa4j.radius.core.attribute.StandardAttribute;
import org.aaa4j.radius.core.attribute.StringData;
import org.aaa4j.radius.core.attribute.TextData;
import org.aaa4j.radius.core.dictionary.Dictionary;
import org.aaa4j.radius.core.dictionary.dictionaries.StandardDictionary;
import org.aaa4j.radius.core.packet.Packet;
import org.aaa4j.radius.core.packet.PacketCodec;
import org.aaa4j.radius.core.util.RandomProvider;
import org.aaa4j.radius.core.util.SecureRandomProvider;
import org.aaa4j.radius.server.DuplicationStrategy;
import org.aaa4j.radius.server.DuplicationStrategy.Result;
import org.aaa4j.radius.server.RadiusServer;
import org.aaa4j.radius.server.TimedDuplicationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A RADIUS server using UDP as the underlying transport layer.
 *
 * <p>
 * Build an instance of {@link UdpRadiusServer} by using a {@link Builder} object retrieved from {@link #newBuilder()}.
 * </p>
 */
public final class UdpRadiusServer implements RadiusServer {
    private static final Logger log = LoggerFactory.getLogger(UdpRadiusServer.class);
    private static final int MAX_PACKET_SIZE = 4096;

//    private static ExecutorService executorService = Executors.newCachedThreadPool();
    private static ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(300);

    private static final DuplicationStrategy DEFAULT_DUPLICATION_STRATEGY =
            new TimedDuplicationStrategy(Duration.ofSeconds(30));

    private final InetSocketAddress bindAddress;

    private final Handler handler;

    private final DuplicationStrategy duplicationStrategy;

    private final Executor executor;

    private final PacketCodec packetCodec;

    private final CountDownLatch startCountDownLatch;

    private final CountDownLatch stopCountDownLatch;

    private volatile boolean isRunning = false;

    private boolean isStarted = false;

    private boolean isStopped = false;

    private SelectorManager selectorManager;

    private UdpRadiusServer(Builder builder) {
        this.bindAddress = Objects.requireNonNull(builder.bindAddress);
        this.handler = Objects.requireNonNull(builder.handler);

        this.duplicationStrategy = builder.duplicationStrategy == null
                ? DEFAULT_DUPLICATION_STRATEGY
                : builder.duplicationStrategy;

        this.executor = builder.executor == null
                ? ForkJoinPool.commonPool()
                : builder.executor;

        Dictionary dictionary = builder.dictionary == null
                ? new StandardDictionary()
                : builder.dictionary;

        RandomProvider randomProvider = builder.randomProvider == null
                ? new SecureRandomProvider()
                : builder.randomProvider;

        this.packetCodec = new PacketCodec(dictionary, randomProvider);

        this.startCountDownLatch = new CountDownLatch(1);
        this.stopCountDownLatch = new CountDownLatch(1);
    }

    /**
     * Creates a new builder object.
     *
     * @return a new builder object
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    @Override
    public synchronized void start() throws InterruptedException {
        if (!isStarted && !isStopped) {
            selectorManager = new SelectorManager(this);
            selectorManager.setDaemon(false);
            selectorManager.start();

            isStarted = true;
        }

        startCountDownLatch.await();
    }

    @Override
    public synchronized void stop() throws InterruptedException {
        if (isStarted && !isStopped) {
            selectorManager.interrupt();

            isStopped = true;
        }

        stopCountDownLatch.await();
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    private final static class SelectorManager extends Thread {

        private static final String NAME = "aaa4j-radius-server-udp-selector-manager";

        private final UdpRadiusServer udpRadiusServer;

        private DatagramChannel datagramChannel;

        SelectorManager(UdpRadiusServer udpRadiusServer) {
            super(NAME);

            this.udpRadiusServer = udpRadiusServer;
        }

        @Override
        public void run() {
            try {
                Selector selector = Selector.open();

                datagramChannel = DatagramChannel.open();
                datagramChannel.configureBlocking(false);
                datagramChannel.register(selector, SelectionKey.OP_READ);

                datagramChannel.socket().bind(udpRadiusServer.bindAddress);

                udpRadiusServer.isRunning = true;

                udpRadiusServer.startCountDownLatch.countDown();

                while (!isInterrupted()) {
                    int numKeysChanged = selector.select();

                    if (isInterrupted()) {
                        break;
                    }

                    if (numKeysChanged > 0) {
                        Set<SelectionKey> selectedKeys = selector.selectedKeys();
                        Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                        boolean isReadable = false;

                        while (keyIterator.hasNext()) {
                            SelectionKey key = keyIterator.next();

                            if (key.isReadable()) {
                                isReadable = true;
                            }

                            keyIterator.remove();
                        }

                        selectedKeys.clear();

                        if (isReadable) {
                            ByteBuffer inByteBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE);

                            InetSocketAddress clientAddress = (InetSocketAddress) datagramChannel.receive(inByteBuffer);

                            try {
                                udpRadiusServer.executor.execute(() -> {
                                    try {
                                        runHandler(clientAddress, inByteBuffer);
                                    } catch (Exception exception) {
                                        try {
                                            udpRadiusServer.handler.handleException(exception);
                                        }
                                        catch (Exception exceptionHandlerException) {
                                            // Not much we can do if the exception handler itself throws an exception
                                            exceptionHandlerException.printStackTrace();
                                        }
                                    }
                                });
                            }
                            catch (RejectedExecutionException rejectedExecutionException) {
                                try {
                                    udpRadiusServer.handler.handleException(rejectedExecutionException);
                                }
                                catch (Exception exceptionHandlerException) {
                                    // Not much we can do if the exception handler itself throws an exception
                                    exceptionHandlerException.printStackTrace();
                                }
                            }
                        }
                    }
                }

                datagramChannel.close();
                selector.close();
            }
            catch (Exception exception) {
                try {
                    udpRadiusServer.handler.handleException(exception);
                }
                catch (Exception exceptionHandlerException) {
                    // Not much we can do if the exception handler itself throws an exception
                    exceptionHandlerException.printStackTrace();
                }
            }
            finally {
                udpRadiusServer.isRunning = false;

                udpRadiusServer.startCountDownLatch.countDown();
                udpRadiusServer.stopCountDownLatch.countDown();
            }
        }

        private void runHandler(InetSocketAddress clientAddress, ByteBuffer inByteBuffer) throws Exception {
            log.debug("Starting with run handler");
            byte[] secret = udpRadiusServer.handler.handleClient(clientAddress.getAddress());

            if (secret != null) {
                log.debug("secret is not null");
                inByteBuffer.flip();
                byte[] inBytes = new byte[inByteBuffer.remaining()];
                inByteBuffer.get(inBytes);

                log.debug("Decoding request");
                Packet requestPacket = udpRadiusServer.packetCodec.decodeRequest(inBytes, secret);
                log.debug("Decoding request complete");

                Packet responsePacket = null;

                // Check the duplication cache for a cached response
                // System.out.println("Attempting handleRequest");
                log.debug("Starting to handle request");
                Result result = udpRadiusServer.duplicationStrategy.handleRequest(clientAddress, requestPacket);

                switch (result.getState()) {
                    case NEW_REQUEST:
                        // The response will be generated since it's a new request
                        log.debug("New request");
                        try {
                            responsePacket = udpRadiusServer.handler.handlePacket(clientAddress.getAddress(), requestPacket);

                            if (responsePacket != null) {
                                udpRadiusServer.duplicationStrategy.handleResponse(clientAddress, requestPacket,
                                        responsePacket);
                            }
                        }
                        catch (Exception e) {
                            udpRadiusServer.duplicationStrategy.unhandleRequest(clientAddress, requestPacket);

                            throw e;
                        }
                        break;
                    case IN_PROGRESS_REQUEST:
                        log.debug("Inprogress request");
                        // Ignore the request since it's a duplicate of one that's being handled
                        break;
                    case CACHED_RESPONSE:
                        log.debug("Cached response");
                        responsePacket = result.getResponsePacket();
                        break;
                }

                if (responsePacket != null) {
                    log.debug("Output bytes are available, sending response.");
                    byte[] outBytes = udpRadiusServer.packetCodec.encodeResponse(responsePacket, secret,
                            requestPacket.getReceivedFields().getIdentifier(),
                            requestPacket.getReceivedFields().getAuthenticator());

                    datagramChannel.send(ByteBuffer.wrap(outBytes), clientAddress);

                } else {
                    log.error("response packet is null");
                }
            } else {
                log.error("Secret is null!");
            }
        }

    }

    /**
     * Builder for {@link UdpRadiusServer}s.
     */
    public final static class Builder {

        InetSocketAddress bindAddress;

        DuplicationStrategy duplicationStrategy;

        Handler handler;

        Executor executor;

        Dictionary dictionary;

        RandomProvider randomProvider;

        /**
         * Sets the address to bind the server to. Required.
         *
         * @param bindAddress the address to bind to
         * 
         * @return this builder
         */
        public Builder bindAddress(InetSocketAddress bindAddress) {
            this.bindAddress = bindAddress;

            return this;
        }

        /**
         * Sets the server handler. Required.
         *
         * @param handler the handler to use
         * 
         * @return this builder
         */
        public Builder handler(Handler handler) {
            this.handler = handler;

            return this;
        }

        /**
         * Sets the duplication strategy. Optional. When not set, a default duplication strategy that caches responses
         * for 30 seconds will be used.
         *
         * @param duplicationStrategy the duplication strategy to use
         *
         * @return this builder
         */
        public Builder duplicationStrategy(DuplicationStrategy duplicationStrategy) {
            this.duplicationStrategy = duplicationStrategy;

            return this;
        }

        /**
         * Sets the executor used to run the handling of RADIUS clients and requests. Optional. When not set, the common
         * ForkJoinPool will be used.
         *
         * @param executor the executor to use
         * 
         * @return this builder
         */
        public Builder executor(Executor executor) {
            this.executor = executor;

            return this;
        }

        /**
         * Sets the {@link Dictionary} to use. Optional. When not set, the standard dictionary will be used.
         *
         * @param dictionary the dictionary to use
         * 
         * @return this builder
         */
        public Builder dictionary(Dictionary dictionary) {
            this.dictionary = dictionary;

            return this;
        }

        /**
         * Sets the {@link RandomProvider} to use for all randomness required by RADIUS. Optional. When not set, a
         * cryptographically-secure random source will be used.
         *
         * @param randomProvider the random provider to use
         * 
         * @return this builder
         */
        public Builder randomProvider(RandomProvider randomProvider) {
            this.randomProvider = randomProvider;

            return this;
        }

        /**
         * Returns a new {@link UdpRadiusServer} built using the builder's options.
         *
         * @return a new {@link UdpRadiusServer}
         */
        public UdpRadiusServer build() {
            return new UdpRadiusServer(this);
        }

    }

}
