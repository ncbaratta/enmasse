/*
 * Copyright 2016-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.systemtest.standard;

import io.enmasse.systemtest.*;
import io.enmasse.systemtest.ability.ITestBaseStandard;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.bases.TestBaseWithShared;
import io.enmasse.systemtest.resolvers.JmsProviderParameterResolver;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.enmasse.systemtest.TestTag.nonPR;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JmsProviderParameterResolver.class)
public class QueueTest extends TestBaseWithShared implements ITestBaseStandard {
    private static Logger log = CustomLogger.getLogger();
    private Connection connection;

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    public static void runQueueTest(AmqpClient client, Destination dest) throws InterruptedException, ExecutionException, TimeoutException, IOException {
        runQueueTest(client, dest, 1024);
    }

    public static void runQueueTest(AmqpClient client, Destination dest, int countMessages) throws InterruptedException, TimeoutException, ExecutionException, IOException {
        List<String> msgs = TestUtils.generateMessages(countMessages);
        Count<Message> predicate = new Count<>(msgs.size());
        Future<Integer> numSent = client.sendMessages(dest.getAddress(), msgs, predicate);

        assertNotNull(numSent, "Sending messages didn't start");
        int actual = 0;
        try {
            actual = numSent.get(1, TimeUnit.MINUTES);
        } catch (TimeoutException t) {
            logCollector.collectRouterState("runQueueTestSend");
            fail("Sending messages timed out after sending " + predicate.actual());
        }
        assertThat("Wrong count of messages sent", actual, is(msgs.size()));

        predicate = new Count<>(msgs.size());
        Future<List<Message>> received = client.recvMessages(dest.getAddress(), predicate);
        actual = 0;
        try {
            actual = received.get(1, TimeUnit.MINUTES).size();
        } catch (TimeoutException t) {
            logCollector.collectRouterState("runQueueTestRecv");
            fail("Receiving messages timed out after " + predicate.actual() + " msgs received");
        }

        assertThat("Wrong count of messages received", actual, is(msgs.size()));
    }

    @Test
    @Tag(nonPR)
    void testColocatedQueues() throws Exception {
        Destination q1 = Destination.queue("queue1", DestinationPlan.STANDARD_SMALL_QUEUE);
        Destination q2 = Destination.queue("queue2", DestinationPlan.STANDARD_SMALL_QUEUE);
        Destination q3 = Destination.queue("queue3", DestinationPlan.STANDARD_SMALL_QUEUE);
        setAddresses(q1, q2, q3);

        AmqpClient client = amqpClientFactory.createQueueClient();
        runQueueTest(client, q1);
        runQueueTest(client, q2);
        runQueueTest(client, q3);
    }

    @Test
    void testShardedQueues() throws Exception {
        Destination q1 = Destination.queue("shardedQueue1", DestinationPlan.STANDARD_LARGE_QUEUE);
        Destination q2 = new Destination("shardedQueue2", null, sharedAddressSpace.getName(), "sharded_addr_2", AddressType.QUEUE.toString(), DestinationPlan.STANDARD_LARGE_QUEUE);
        addressApiClient.createAddress(q2);

        appendAddresses(q1);
        waitForDestinationsReady(q2);

        AmqpClient client = amqpClientFactory.createQueueClient();
        runQueueTest(client, q1);
        runQueueTest(client, q2);
    }

    @Test
    @Tag(nonPR)
    void testRestApi() throws Exception {
        Destination q1 = Destination.queue("queue1", getDefaultPlan(AddressType.QUEUE));
        Destination q2 = Destination.queue("queue2", getDefaultPlan(AddressType.QUEUE));

        runRestApiTest(sharedAddressSpace, q1, q2);
    }

    @Test
    @Tag(nonPR)
    void testCreateDeleteQueue() throws Exception {
        List<String> queues = IntStream.range(0, 16).mapToObj(i -> "queue-create-delete-" + i).collect(Collectors.toList());
        Destination destExtra = Destination.queue("ext-queue", DestinationPlan.STANDARD_SMALL_QUEUE);

        List<Destination> addresses = new ArrayList<>();
        queues.forEach(queue -> addresses.add(Destination.queue(queue, DestinationPlan.STANDARD_SMALL_QUEUE)));

        AmqpClient client = amqpClientFactory.createQueueClient();
        for (Destination address : addresses) {
            setAddresses(address, destExtra);
            Thread.sleep(20_000);

            //runQueueTest(client, address, 1); //TODO! commented due to issue #429

            deleteAddresses(address);
            Future<List<String>> response = getAddresses(Optional.empty());
            assertThat("Extra destination was not created ",
                    response.get(20, TimeUnit.SECONDS), is(Collections.singletonList(destExtra.getAddress())));
            deleteAddresses(destExtra);
            response = getAddresses(Optional.empty());
            assertThat("No destinations are expected",
                    response.get(20, TimeUnit.SECONDS), is(java.util.Collections.emptyList()));
            Thread.sleep(20_000);
        }
    }

    @Test
    void testMessagePriorities() throws Exception {
        Destination dest = Destination.queue("messagePrioritiesQueue", getDefaultPlan(AddressType.QUEUE));
        setAddresses(dest);

        AmqpClient client = amqpClientFactory.createQueueClient();
        Thread.sleep(30_000);

        int msgsCount = 1024;
        List<Message> listOfMessages = new ArrayList<>();
        for (int i = 0; i < msgsCount; i++) {
            Message msg = Message.Factory.create();
            msg.setAddress(dest.getAddress());
            msg.setBody(new AmqpValue(dest.getAddress()));
            msg.setSubject("subject");
            msg.setPriority((short) (i % 10));
            listOfMessages.add(msg);
        }

        Future<Integer> sent = client.sendMessages(dest.getAddress(),
                listOfMessages.toArray(new Message[0]));
        assertThat("Wrong count of messages sent", sent.get(1, TimeUnit.MINUTES), is(msgsCount));

        Future<List<Message>> received = client.recvMessages(dest.getAddress(), msgsCount);
        assertThat("Wrong count of messages received", received.get(1, TimeUnit.MINUTES).size(), is(msgsCount));

        int sub = 1;
        for (Message m : received.get()) {
            for (Message mSub : received.get().subList(sub, received.get().size())) {
                assertTrue(m.getPriority() >= mSub.getPriority(), "Wrong order of messages");
            }
            sub++;
        }
    }

    @Test
    void testScaledown() throws Exception {
        Destination before = Destination.queue("scalequeue", DestinationPlan.STANDARD_LARGE_QUEUE);
        Destination after = Destination.queue("scalequeue", DestinationPlan.STANDARD_SMALL_QUEUE);
        testScale(before, after);
    }

    @Test
    void testScaleup() throws Exception {
        Destination before = Destination.queue("scalequeue", DestinationPlan.STANDARD_SMALL_QUEUE);
        Destination after = Destination.queue("scalequeue", DestinationPlan.STANDARD_LARGE_QUEUE);
        testScale(before, after);
    }

    private void testScale(Destination before, Destination after) throws Exception {
        assertEquals(before.getAddress(), after.getAddress());
        assertEquals(before.getName(), after.getName());
        assertEquals(before.getType(), after.getType());
        setAddresses(before);

        AmqpClient client = amqpClientFactory.createQueueClient();
        final List<String> prefixes = Arrays.asList("foo", "bar", "baz", "quux");
        final int numMessages = 1000;
        final int totalNumMessages = numMessages * prefixes.size();
        final int numReceiveBeforeDraining = numMessages / 2;
        final int numReceivedAfterScaled = totalNumMessages - numReceiveBeforeDraining;
        final int numReceivedAfterScaledPhase1 = numReceivedAfterScaled / 2;
        final int numReceivedAfterScaledPhase2 = numReceivedAfterScaled - numReceivedAfterScaledPhase1;

        List<Future<Integer>> sent = prefixes.stream().map(prefix -> client.sendMessages(before.getAddress(), TestUtils.generateMessages(prefix, numMessages))).collect(Collectors.toList());

        assertAll("All sender should send all messages",
                () -> assertThat("Wrong count of messages sent: sender0",
                        sent.get(0).get(1, TimeUnit.MINUTES), is(numMessages)),
                () -> assertThat("Wrong count of messages sent: sender1",
                        sent.get(1).get(1, TimeUnit.MINUTES), is(numMessages)),
                () -> assertThat("Wrong count of messages sent: sender2",
                        sent.get(2).get(1, TimeUnit.MINUTES), is(numMessages)),
                () -> assertThat("Wrong count of messages sent: sender3",
                        sent.get(3).get(1, TimeUnit.MINUTES), is(numMessages))
        );

        Future<List<Message>> received = client.recvMessages(before.getAddress(), numReceiveBeforeDraining);
        assertThat("Wrong count of messages received",
                received.get(1, TimeUnit.MINUTES).size(), is(numReceiveBeforeDraining));


        replaceAddress(getSharedAddressSpace(), after);
        // Receive messages sent before address was replaced
        assertThat("Wrong count of messages received", client.recvMessages(after.getAddress(), numReceivedAfterScaledPhase1).get(1, TimeUnit.MINUTES).size(), is(numReceivedAfterScaledPhase1));

        Thread.sleep(30_000);

        // Give system a chance to do something stupid
        assertThat("Wrong count of messages received", client.recvMessages(after.getAddress(), numReceivedAfterScaledPhase2).get(1, TimeUnit.MINUTES).size(), is(numReceivedAfterScaledPhase2));

        // Ensure send and receive works after address was replaced
        assertThat("Wrong count of messages sent", client.sendMessages(after.getAddress(), TestUtils.generateMessages(prefixes.get(0), numMessages)).get(1, TimeUnit.MINUTES), is(numMessages));
        assertThat("Wrong count of messages received", client.recvMessages(after.getAddress(), numMessages).get(1, TimeUnit.MINUTES).size(), is(numMessages));

        // Ensure there are no brokers in Draining state
        TestUtils.waitForBrokersDrained(addressApiClient, getSharedAddressSpace(), new TimeoutBudget(3, TimeUnit.MINUTES), after);

        // Ensure send and receive works after all brokers are drained
        assertThat("Wrong count of messages sent", client.sendMessages(after.getAddress(), TestUtils.generateMessages(prefixes.get(1), numMessages)).get(1, TimeUnit.MINUTES), is(numMessages));
        assertThat("Wrong count of messages received", client.recvMessages(after.getAddress(), numMessages).get(1, TimeUnit.MINUTES).size(), is(numMessages));
    }

    @Test
    public void testConcurrentOperations() throws Exception {
        HashMap<CompletableFuture<Void>, List<UserCredentials>> company = new HashMap<>();
        int customersCount = 10;
        int usersCount = 5;
        int destinationCount = 10;
        String destNamePrefix = "queue";

        for (int i = 0; i < customersCount; i++) {
            //define users
            ArrayList<UserCredentials> users = new ArrayList<>(usersCount);
            for (int j = 0; j < usersCount; j++) {
                users.add(new UserCredentials(
                        String.format("uname-%d-%d", i, j),
                        String.format("p$$wd-%d-%d", i, j)));
            }

            //define destinations
            Destination[] destinations = new Destination[destinationCount];
            for (int destI = 0; destI < destinationCount; destI++) {
                destinations[destI] = Destination.queue(String.format("%s.%s.%s", destNamePrefix, i, destI), getDefaultPlan(AddressType.QUEUE));
            }

            //run async: append addresses; create users; send/receive messages
            final int customerIndex = i;
            company.put(CompletableFuture.runAsync(() ->
            {
                try {
                    int messageCount = 43;
                    appendAddresses(false, -1, destinations);
                    doMessaging(Arrays.asList(destinations), users, destNamePrefix, customerIndex, messageCount);
                } catch (Exception e) {
                    e.printStackTrace();
                    fail(e.getMessage());
                }
            }, runnable -> new Thread(runnable).start()), users);
        }

        //once one of the doMessaging method is finished  then remove appropriate users
        for (Map.Entry<CompletableFuture<Void>, List<UserCredentials>> customer : company.entrySet()) {
            customer.getKey().get();
            removeUsers(sharedAddressSpace, customer.getValue().stream().map(UserCredentials::getUsername).collect(Collectors.toList()));
        }
    }

    @Test
    @Disabled("due to issue #1330")
    void testLargeMessages(JmsProvider jmsProvider) throws Exception {
        Destination addressQueue = Destination.queue("jmsQueue", getDefaultPlan(AddressType.QUEUE));
        setAddresses(addressQueue);

        connection = jmsProvider.createConnection(getMessagingRoute(sharedAddressSpace).toString(), defaultCredentials,
                "jmsCliId", addressQueue);
        connection.start();

        sendReceiveLargeMessage(jmsProvider, 50, addressQueue, 1);
        sendReceiveLargeMessage(jmsProvider, 10, addressQueue, 1);
        sendReceiveLargeMessage(jmsProvider, 1, addressQueue, 1);
        sendReceiveLargeMessage(jmsProvider, 50, addressQueue, 1, DeliveryMode.PERSISTENT);
        sendReceiveLargeMessage(jmsProvider, 10, addressQueue, 1, DeliveryMode.PERSISTENT);
        sendReceiveLargeMessage(jmsProvider, 1, addressQueue, 1, DeliveryMode.PERSISTENT);
    }
}

