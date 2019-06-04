// Copyright (c) 2018-Present Pivotal Software, Inc.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 1.1 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.perf;

import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.*;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.rabbitmq.perf.MockUtils.*;
import static com.rabbitmq.perf.TestUtils.threadFactory;
import static com.rabbitmq.perf.TestUtils.waitAtMost;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MessageCountTimeLimitAndPublishingIntervalRateTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageCountTimeLimitAndPublishingIntervalRateTest.class);
    static Set<String> THREADS = new LinkedHashSet<>();
    MulticastSet.CompletionHandler completionHandler;
    Stats stats = new NoOpStats();
    MulticastParams params;
    ExecutorService executorService;
    MulticastSet.ThreadingHandler th;
    volatile AtomicBoolean testIsDone;
    volatile long testDurationInMs;
    CountDownLatch runStartedLatch;

    static Stream<Arguments> producerCountArguments() {
        return Stream.of(
                Arguments.of(1, 1),
                Arguments.of(3, 1),
                Arguments.of(1, 3),
                Arguments.of(2, 3)
        );
    }

    static Stream<Arguments> consumerCountArguments() {
        return Stream.of(
                Arguments.of(1, 1),
                Arguments.of(3, 1),
                Arguments.of(1, 3),
                Arguments.of(2, 3)
        );
    }

    static Stream<Arguments> pollingWithBasicGetArguments() {
        return Stream.of(
                Arguments.of(1, 1),
                Arguments.of(3, 1),
                Arguments.of(1, 3),
                Arguments.of(2, 3)
        );
    }

    @BeforeEach
    public void init(TestInfo info) {
        LOGGER.info("Starting {} {}", info.getTestMethod().get().getName(), info.getDisplayName());
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        if (THREADS.isEmpty()) {
            for (Thread thread : threads) {
                THREADS.add(thread.getId() + " " + thread.getName());
            }
        } else {
            for (Thread thread : threads) {
                String key = thread.getId() + " " + thread.getName();
                if (THREADS.add(key)) {
                    LOGGER.warn("Thread not present in the previous test: {}", key);
                }

            }

        }
        LOGGER.info("Existing threads: {}", THREADS);

        testIsDone = new AtomicBoolean(false);
        executorService = Executors.newCachedThreadPool(threadFactory(info));
        th = new MulticastSet.DefaultThreadingHandler();
        testDurationInMs = -1;
        params = new MulticastParams();
        params.setPredeclared(true);
        runStartedLatch = new CountDownLatch(1);
        LOGGER.info("Done initializing {} {}", info.getTestMethod().get().getName(), info.getDisplayName());
    }

    @AfterEach
    public void tearDown(TestInfo info) throws InterruptedException {
        LOGGER.info("Tearing down {} {}", info.getTestMethod().get().getName(), info.getDisplayName());
        th.shutdown();
        List<Runnable> runnables = executorService.shutdownNow();
        if (runnables.size() > 0) {
            LOGGER.warn("Some tasks are still waiting execution: {}", runnables);
        }
        boolean allTerminated = executorService.awaitTermination(5, TimeUnit.SECONDS);
        if (!allTerminated) {
            LOGGER.warn("All tasks couldn't finish in time");
        }
        if (!testIsDone.get()) {
            LOGGER.warn("PerfTest run not done");
        }
        LOGGER.info("Done tearing down {} {}", info.getTestMethod().get().getName(), info.getDisplayName());
    }

    @Test
    public void noLimit() throws Exception {
        countsAndTimeLimit(0, 0, 0);

        int nbMessages = 10;
        CountDownLatch publishedLatch = new CountDownLatch(nbMessages);
        Channel channel = proxy(Channel.class,
                callback("basicPublish", (proxy, method, args) -> {
                    publishedLatch.countDown();
                    return null;
                }),
                callback("getNextPublishSeqNo", (proxy, method, args) -> 0L)
        );

        AtomicInteger connectionCloseCalls = new AtomicInteger(0);
        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("close", (proxy, method, args) -> connectionCloseCalls.incrementAndGet())
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitForRunToStart();

        assertTrue(
                publishedLatch.await(10, TimeUnit.SECONDS),
                () -> format("Only %d / %d messages have been published", publishedLatch.getCount(), nbMessages)
        );

        assertThat(testIsDone.get(), is(false));
        // only the configuration connection has been closed
        // so the test is still running in the background
        assertThat(connectionCloseCalls.get(), is(1));
        completionHandler.countDown();
    }

    // --time 5
    @Test
    public void timeLimit() throws InterruptedException {
        countsAndTimeLimit(1, 1, 3);

        Channel channel = proxy(Channel.class,
                callback("basicPublish", (proxy, method, args) -> null),
                callback("getNextPublishSeqNo", (proxy, method, args) -> 0L)
        );

        AtomicInteger closeCount = new AtomicInteger(0);
        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("close", (proxy, method, args) -> closeCount.incrementAndGet())
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitForRunToStart();

        waitAtMost(15, () -> testIsDone.get());

        assertThat(testDurationInMs, greaterThanOrEqualTo(3000L));
        assertThat(closeCount.get(), is(4)); // the configuration connection is actually closed twice
    }

    // -y 1 --pmessages 10 -x n -X m
    @ParameterizedTest
    @MethodSource("producerCountArguments")
    public void producerCount(int producersCount, int channelsCount) throws Exception {
        int messagesCount = 100;
        countsAndTimeLimit(messagesCount, 0, 0);
        params.setProducerCount(producersCount);
        params.setProducerChannelCount(channelsCount);

        int messagesTotal = producersCount * channelsCount * messagesCount;
        CountDownLatch publishedLatch = new CountDownLatch(messagesTotal);
        Channel channel = proxy(Channel.class,
                callback("basicPublish", (proxy, method, args) -> {
                    publishedLatch.countDown();
                    return null;
                }),
                callback("getNextPublishSeqNo", (proxy, method, args) -> 0L)
        );

        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel)
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitForRunToStart();

        assertTrue(
                publishedLatch.await(60, TimeUnit.SECONDS),
                () -> format("Only %d / %d messages have been published", publishedLatch.getCount(), messagesTotal)
        );
        waitAtMost(20, () -> testIsDone.get());
    }

    // --cmessages 10 -y n -Y m
    @ParameterizedTest
    @MethodSource("consumerCountArguments")
    public void consumerCount(int consumersCount, int channelsCount) throws Exception {
        int messagesCount = consumersCount * channelsCount;
        countsAndTimeLimit(0, messagesCount, 0);
        params.setConsumerCount(consumersCount);
        params.setConsumerChannelCount(channelsCount);
        params.setQueueNames(asList("queue"));

        CountDownLatch consumersLatch = new CountDownLatch(consumersCount * channelsCount);
        AtomicInteger consumerTagCounter = new AtomicInteger(0);
        List<Consumer> consumers = new CopyOnWriteArrayList<>();
        Channel channel = proxy(Channel.class,
                callback("basicConsume", (proxy, method, args) -> {
                    consumers.add((Consumer) args[2]);
                    consumersLatch.countDown();
                    return consumerTagCounter.getAndIncrement() + "";
                })
        );

        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel)
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
        run(multicastSet);

        waitForRunToStart();

        assertThat(consumersCount * channelsCount + " consumer(s) should have been registered by now",
                consumersLatch.await(5, TimeUnit.SECONDS), is(true));

        waitAtMost(20, () -> consumers.size() == (consumersCount * channelsCount));

        Collection<Future<?>> sendTasks = new ArrayList<>(consumers.size());
        for (Consumer consumer : consumers) {
            Collection<Future<?>> tasks = sendMessagesToConsumer(messagesCount, consumer);
            sendTasks.addAll(tasks);
        }

        for (Future<?> latch : sendTasks) {
            latch.get(10, TimeUnit.SECONDS);
        }

        waitAtMost(20, () -> testIsDone.get());
    }

    // --time 5 -x 1 --pmessages 10 -y 1 --cmessages 10
    @Test
    public void timeLimitTakesPrecedenceOverCounts() throws Exception {
        int nbMessages = 10;
        countsAndTimeLimit(nbMessages, nbMessages, 5);
        params.setQueueNames(asList("queue"));

        CountDownLatch publishedLatch = new CountDownLatch(nbMessages);

        CountDownLatch consumersLatch = new CountDownLatch(1);
        AtomicInteger consumerTagCounter = new AtomicInteger(0);
        AtomicReference<Consumer> consumer = new AtomicReference<>();

        Channel channel = proxy(Channel.class,
                callback("basicPublish", (proxy, method, args) -> {
                    publishedLatch.countDown();
                    return null;
                }),
                callback("getNextPublishSeqNo", (proxy, method, args) -> 0L),
                callback("basicConsume", (proxy, method, args) -> {
                    consumer.set((Consumer) args[2]);
                    String ctag = consumerTagCounter.getAndIncrement() + "";
                    consumersLatch.countDown();
                    return ctag;
                })
        );

        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("isOpen", (proxy, method, args) -> true));

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitForRunToStart();

        assertThat("1 consumer should have been registered by now",
                consumersLatch.await(10, TimeUnit.SECONDS), is(true));
        assertThat(consumer.get(), notNullValue());
        sendMessagesToConsumer(nbMessages / 2, consumer.get());

        assertTrue(
                publishedLatch.await(10, TimeUnit.SECONDS),
                () -> format("Only %d / %d messages have been published", publishedLatch.getCount(), nbMessages)
        );

        assertThat(testIsDone.get(), is(false));

        waitAtMost(20, () -> testIsDone.get());
        assertThat(testDurationInMs, greaterThanOrEqualTo(5000L));
    }

    // -x 0 -y 1
    @Test
    public void consumerOnlyDoesNotStop() throws Exception {
        countsAndTimeLimit(0, 0, 0);
        params.setQueueNames(asList("queue"));
        params.setProducerCount(0);
        params.setConsumerCount(1);

        CountDownLatch consumersLatch = new CountDownLatch(1);
        AtomicInteger consumerTagCounter = new AtomicInteger(0);

        List<Consumer> consumers = new CopyOnWriteArrayList<>();
        Channel channel = proxy(Channel.class,
                callback("basicConsume", (proxy, method, args) -> {
                    consumers.add((Consumer) args[2]);
                    consumersLatch.countDown();
                    return consumerTagCounter.getAndIncrement() + "";
                })
        );

        AtomicInteger closeCount = new AtomicInteger(0);
        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("close", (proxy, method, args) -> closeCount.incrementAndGet())
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
        run(multicastSet);

        waitForRunToStart();

        assertThat("1 consumer should have been registered by now",
                consumersLatch.await(20, TimeUnit.SECONDS), is(true));
        assertThat(consumers, hasSize(1));

        assertThat(testIsDone.get(), is(false));
        // only the configuration connection has been closed
        // so the test is still running in the background
        assertThat(closeCount.get(), is(1));
        completionHandler.countDown();
        waitAtMost(20, () -> testIsDone.get());
    }

    // -x 0 -y 1
    @Test
    public void producerOnlyDoesNotStop() throws Exception {
        countsAndTimeLimit(0, 0, 0);
        params.setProducerCount(1);
        params.setConsumerCount(0);
        params.setProducerRateLimit(100);

        int nbMessages = 100;
        CountDownLatch publishedLatch = new CountDownLatch(nbMessages);

        Channel channel = proxy(Channel.class,
                callback("basicPublish", (proxy, method, args) -> {
                    publishedLatch.countDown();
                    return null;
                }),
                callback("getNextPublishSeqNo", (proxy, method, args) -> 0L)
        );

        AtomicInteger closeCount = new AtomicInteger(0);
        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("close", (proxy, method, args) -> closeCount.incrementAndGet())
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitForRunToStart();

        assertTrue(
                publishedLatch.await(20, TimeUnit.SECONDS),
                () -> format("Only %d / %d messages have been published", publishedLatch.getCount(), nbMessages)
        );
        assertThat(testIsDone.get(), is(false));
        // only the configuration connection has been closed
        // so the test is still running in the background
        assertThat(closeCount.get(), is(1));
        completionHandler.countDown();
        waitAtMost(20, () -> testIsDone.get());
    }

    @Test
    public void publishingRateLimit() throws InterruptedException {
        countsAndTimeLimit(0, 0, 8);
        params.setProducerRateLimit(100);
        params.setProducerCount(3);

        AtomicInteger publishedMessageCount = new AtomicInteger();
        Channel channel = proxy(Channel.class,
                callback("basicPublish", (proxy, method, args) -> {
                    publishedMessageCount.incrementAndGet();
                    return null;
                }),
                callback("getNextPublishSeqNo", (proxy, method, args) -> 0L)
        );

        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel)
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitForRunToStart();

        waitAtMost(30, () -> testIsDone.get());
        assertThat(publishedMessageCount.get(), allOf(
                greaterThan(0),
                lessThan(3 * 100 * 8 * 2) // not too many messages
        ));
        assertThat(testDurationInMs, greaterThan(5000L));
    }

    @Test
    public void publishingInterval() throws InterruptedException {
        countsAndTimeLimit(0, 0, 6);
        params.setPublishingInterval(1);
        params.setProducerCount(3);

        AtomicInteger publishedMessageCount = new AtomicInteger();
        Channel channel = proxy(Channel.class,
                callback("basicPublish", (proxy, method, args) -> {
                    publishedMessageCount.incrementAndGet();
                    return null;
                }),
                callback("getNextPublishSeqNo", (proxy, method, args) -> 0L)
        );

        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel)
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitForRunToStart();

        waitAtMost(10, () -> testIsDone.get());
        assertThat(publishedMessageCount.get(), allOf(
                greaterThanOrEqualTo(3 * 2),  // 3 publishers should publish at least a couple of times
                lessThan(3 * 2 * 8) //  but they don't publish too much
        ));
        assertThat(testDurationInMs, greaterThan(5000L));
    }

    @Test
    public void shutdownCalledIfShutdownTimeoutIsGreatherThanZero() throws Exception {
        countsAndTimeLimit(0, 0, 0);

        Channel channel = proxy(Channel.class,
                callback("basicPublish", (proxy, method, args) -> null),
                callback("getNextPublishSeqNo", (proxy, method, args) -> 0L)
        );

        AtomicInteger connectionCloseCalls = new AtomicInteger(0);
        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("close", (proxy, method, args) -> connectionCloseCalls.incrementAndGet())
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitForRunToStart();

        completionHandler.countDown();
        waitAtMost(10, () -> testIsDone.get());
        assertThat(connectionCloseCalls.get(), is(4)); // configuration connection is closed twice
    }

    @Test
    public void shutdownNotCalledIfShutdownTimeoutIsZeroOrLess() throws Exception {
        countsAndTimeLimit(0, 0, 0);
        params.setShutdownTimeout(-1);

        Channel channel = proxy(Channel.class,
                callback("basicPublish", (proxy, method, args) -> null),
                callback("getNextPublishSeqNo", (proxy, method, args) -> 0L)
        );

        AtomicInteger connectionCloseCalls = new AtomicInteger(0);
        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("close", (proxy, method, args) -> connectionCloseCalls.incrementAndGet())
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitForRunToStart();

        completionHandler.countDown();
        waitAtMost(10, () -> testIsDone.get());
        assertThat(connectionCloseCalls.get(), is(1)); // configuration connection is closed after configuration is done
    }

    @Test
    public void shutdownNotCompletedIfTimeoutIsReached() throws Exception {
        countsAndTimeLimit(0, 0, 0);
        params.setShutdownTimeout(1);

        Channel channel = proxy(Channel.class,
                callback("basicPublish", (proxy, method, args) -> null),
                callback("getNextPublishSeqNo", (proxy, method, args) -> 0L)
        );

        AtomicInteger connectionCloseCalls = new AtomicInteger(0);
        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("close", (proxy, method, args) -> {
                    connectionCloseCalls.incrementAndGet();
                    // the first call is to close the configuration connection at the beginning of the run,
                    // so we simulate a timeout when closing a connection during the final shutdown
                    if (connectionCloseCalls.get() == 2) {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            // the interrupt flag is cleared after an InterruptedException
                            Thread.currentThread().interrupt();
                        }
                    }
                    return null;
                })
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));

        run(multicastSet);

        waitForRunToStart();

        completionHandler.countDown();
        waitAtMost(10, () -> testIsDone.get());
        assertThat(connectionCloseCalls.get(), is(2));
    }

    @ParameterizedTest
    @MethodSource("pollingWithBasicGetArguments")
    public void pollingWithBasicGet(int consumersCount, int channelsCount) throws Exception {
        int messagesCount = consumersCount * channelsCount * 10;
        countsAndTimeLimit(0, messagesCount, 0);
        params.setConsumerCount(consumersCount);
        params.setConsumerChannelCount(channelsCount);
        params.setQueueNames(asList("queue"));
        params.setPolling(true);
        params.setPollingInterval(10);

        AtomicInteger countBasicGottenMessages = new AtomicInteger(0);
        Channel channel = proxy(Channel.class,
                callback("basicGet", (proxy, method, args) -> {
                    GetResponse response = new GetResponse(
                            new Envelope(1, false, "", ""),
                            null,
                            new byte[20],
                            1
                    );
                    countBasicGottenMessages.incrementAndGet();
                    return response;
                })
        );

        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel)
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
        run(multicastSet);

        waitForRunToStart();

        waitAtMost(20, () -> testIsDone.get());
        Assertions.assertThat(countBasicGottenMessages).hasValueGreaterThanOrEqualTo(messagesCount);
    }

    // -x 0 -y 1 --polling --polling-interval 10
    @Test
    public void pollingOnlyDoesNotStop() throws Exception {
        countsAndTimeLimit(0, 0, 0);
        params.setQueueNames(asList("queue"));
        params.setProducerCount(0);
        params.setConsumerCount(1);
        params.setPolling(true);
        params.setPollingInterval(10);

        AtomicInteger countBasicGottenMessages = new AtomicInteger(0);
        Channel channel = proxy(Channel.class,
                callback("basicGet", (proxy, method, args) -> {
                    GetResponse response = new GetResponse(
                            new Envelope(1, false, "", ""),
                            null,
                            new byte[20],
                            1
                    );
                    countBasicGottenMessages.incrementAndGet();
                    return response;
                })
        );

        AtomicInteger closeCount = new AtomicInteger(0);
        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel),
                callback("close", (proxy, method, args) -> closeCount.incrementAndGet())
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
        run(multicastSet);

        waitForRunToStart();

        waitAtMost(20, () -> countBasicGottenMessages.get() > 10);

        assertThat(testIsDone.get(), is(false));
        // only the configuration connection has been closed
        // so the test is still running in the background
        assertThat(closeCount.get(), is(1));
        completionHandler.countDown();
        waitAtMost(20, () -> testIsDone.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "true"})
    public void ackNack(String nackParameter) throws Exception {
        boolean nack = Boolean.valueOf(nackParameter);
        int messagesCount = 100;
        countsAndTimeLimit(0, messagesCount, 0);
        params.setConsumerCount(1);
        params.setProducerCount(0);
        params.setQueueNames(asList("queue"));
        params.setNack(nack);

        CountDownLatch consumersLatch = new CountDownLatch(1);
        AtomicInteger consumerTagCounter = new AtomicInteger(0);
        List<Consumer> consumers = new CopyOnWriteArrayList<>();
        AtomicInteger acks = new AtomicInteger(0);
        AtomicInteger nacks = new AtomicInteger(0);
        Channel channel = proxy(Channel.class,
                callback("basicConsume", (proxy, method, args) -> {
                    consumers.add((Consumer) args[2]);
                    consumersLatch.countDown();
                    return consumerTagCounter.getAndIncrement() + "";
                }),
                callback("basicAck", (proxy, method, args) -> {
                    acks.incrementAndGet();
                    return null;
                }),
                callback("basicNack", (proxy, method, args) -> {
                    nacks.incrementAndGet();
                    return null;
                })
        );

        Connection connection = proxy(Connection.class,
                callback("createChannel", (proxy, method, args) -> channel)
        );

        MulticastSet multicastSet = getMulticastSet(connectionFactoryThatReturns(connection));
        run(multicastSet);

        waitForRunToStart();

        assertThat("consumer should have been registered by now",
                consumersLatch.await(5, TimeUnit.SECONDS), is(true));

        waitAtMost(20, () -> consumers.size() == 1);

        Collection<Future<?>> sendTasks = new ArrayList<>(consumers.size());
        for (Consumer consumer : consumers) {
            Collection<Future<?>> tasks = sendMessagesToConsumer(messagesCount, consumer);
            sendTasks.addAll(tasks);
        }

        for (Future<?> latch : sendTasks) {
            latch.get(10, TimeUnit.SECONDS);
        }

        waitAtMost(20, () -> testIsDone.get());

        if (nack) {
            Assertions.assertThat(acks).hasValue(0);
            Assertions.assertThat(nacks).hasValue(messagesCount);
        } else {
            Assertions.assertThat(acks).hasValue(messagesCount);
            Assertions.assertThat(nacks).hasValue(0);
        }


    }

    private Collection<Future<?>> sendMessagesToConsumer(int messagesCount, Consumer consumer) {
        final Collection<Future<?>> tasks = new ArrayList<>(messagesCount);
        IntStream.range(0, messagesCount).forEach(i -> {
            Future<?> task = executorService.submit(() -> {
                try {
                    consumer.handleDelivery(
                            "",
                            new Envelope(1, false, "", ""),
                            null,
                            new byte[20]
                    );
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            tasks.add(task);
        });
        return tasks;
    }

    private void countsAndTimeLimit(int pmc, int cmc, int time) {
        params.setProducerMsgCount(pmc);
        params.setConsumerMsgCount(cmc);
        params.setTimeLimit(time);
    }

    private MulticastSet getMulticastSet(ConnectionFactory connectionFactory) {
        return getMulticastSet(connectionFactory, PerfTest.getCompletionHandler(params));
    }

    void waitForRunToStart() throws InterruptedException {
        assertTrue(runStartedLatch.await(20, TimeUnit.SECONDS), "Run should have started by now");
    }

    private MulticastSet getMulticastSet(ConnectionFactory connectionFactory, MulticastSet.CompletionHandler completionHandler) {
        MulticastSet.CompletionHandler completionHandlerWrapper = new MulticastSet.CompletionHandler() {

            @Override
            public void waitForCompletion() throws InterruptedException {
                runStartedLatch.countDown();
                completionHandler.waitForCompletion();
            }

            @Override
            public void countDown() {
                completionHandler.countDown();
            }
        };
        MulticastSet set = new MulticastSet(
                stats, connectionFactory, params, singletonList("amqp://localhost"),
                completionHandlerWrapper
        );
        set.setThreadingHandler(th);
        this.completionHandler = completionHandlerWrapper;
        return set;
    }

    private void run(MulticastSet multicastSet) {
        executorService.submit(() -> {
            try {
                long start = System.nanoTime();
                multicastSet.run();
                testDurationInMs = (System.nanoTime() - start) / 1_000_000;
                testIsDone.set(true);
            } catch (InterruptedException e) {
                // one of the tests stops the execution, no need to be noisy
                LOGGER.warn("Run has been interrupted");
            } catch (Exception e) {
                if (e.getCause() instanceof InterruptedException) {
                    LOGGER.warn("Run has been interrupted");
                } else {
                    LOGGER.warn("Error during run", e);
                }
            }
        });
    }
}
