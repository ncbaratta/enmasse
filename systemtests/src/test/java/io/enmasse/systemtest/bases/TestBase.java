/*
 * Copyright 2016-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.systemtest.bases;

import com.google.common.collect.Ordering;
import io.enmasse.systemtest.*;
import io.enmasse.systemtest.ability.ITestBase;
import io.enmasse.systemtest.ability.ITestSeparator;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.amqp.AmqpClientFactory;
import io.enmasse.systemtest.amqp.ReceiverStatus;
import io.enmasse.systemtest.amqp.UnauthorizedAccessException;
import io.enmasse.systemtest.apiclients.AddressApiClient;
import io.enmasse.systemtest.apiclients.UserApiClient;
import io.enmasse.systemtest.cmdclients.KubeCMDClient;
import io.enmasse.systemtest.messagingclients.AbstractClient;
import io.enmasse.systemtest.messagingclients.ClientArgument;
import io.enmasse.systemtest.messagingclients.ClientArgumentMap;
import io.enmasse.systemtest.messagingclients.rhea.RheaClientConnector;
import io.enmasse.systemtest.messagingclients.rhea.RheaClientReceiver;
import io.enmasse.systemtest.messagingclients.rhea.RheaClientSender;
import io.enmasse.systemtest.mqtt.MqttClientFactory;
import io.enmasse.systemtest.resources.SchemaData;
import io.enmasse.systemtest.selenium.SeleniumManagement;
import io.enmasse.systemtest.selenium.SeleniumProvider;
import io.enmasse.systemtest.selenium.page.ConsoleWebPage;
import io.enmasse.systemtest.timemeasuring.Operation;
import io.enmasse.systemtest.timemeasuring.TimeMeasuringSystem;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.sasl.SaslSystemException;
import org.apache.qpid.proton.message.Message;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;

import javax.jms.DeliveryMode;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.security.sasl.AuthenticationException;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.time.Duration.ofMinutes;
import static io.enmasse.systemtest.TimeoutBudget.ofDuration;
import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for all tests
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class TestBase implements ITestBase, ITestSeparator {
    protected static final Environment environment = Environment.getInstance();
    protected static final Kubernetes kubernetes = Kubernetes.getInstance();
    protected static final GlobalLogCollector logCollector = new GlobalLogCollector(kubernetes,
            new File(environment.testLogDir()));
    protected static AddressApiClient addressApiClient;
    private static Logger log = CustomLogger.getLogger();
    protected AmqpClientFactory amqpClientFactory;
    protected MqttClientFactory mqttClientFactory;
    protected UserCredentials managementCredentials = new UserCredentials(null, null);
    protected UserCredentials defaultCredentials = new UserCredentials(null, null);
    private List<AddressSpace> addressSpaceList = new ArrayList<>();
    private UserApiClient userApiClient;
    private boolean reuseAddressSpace;

    @BeforeEach
    public void setup() throws Exception {
        if (addressApiClient == null) {
            addressApiClient = new AddressApiClient(kubernetes);
        }
        if (!reuseAddressSpace) {
            addressSpaceList = new ArrayList<>();
        }
        amqpClientFactory = new AmqpClientFactory(kubernetes, environment, null, defaultCredentials);
        mqttClientFactory = new MqttClientFactory(kubernetes, environment, null, defaultCredentials);
    }

    @AfterEach
    public void teardown() throws Exception {
        try {
            if (mqttClientFactory != null) {
                mqttClientFactory.close();
            }
            if (amqpClientFactory != null) {
                amqpClientFactory.close();
            }

            if (!environment.skipCleanup() && !reuseAddressSpace) {
                deleteAddressspacesFromList();
            } else {
                log.warn("Remove address spaces in tear down - SKIPPED!");
            }
        } catch (Exception e) {
            log.error("Error tearing down test: {}", e.getMessage());
            throw e;
        }
    }

    protected void setReuseAddressSpace() {
        reuseAddressSpace = true;
    }

    protected void unsetReuseAddressSpace() {
        reuseAddressSpace = false;
    }


    //================================================================================================
    //==================================== AddressSpace methods ======================================
    //================================================================================================

    protected void createAddressSpace(AddressSpace addressSpace) throws Exception {
        createAddressSpace(addressSpace, addressApiClient);
    }

    protected void createAddressSpaceList(AddressSpace... addressSpaces) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.CREATE_ADDRESS_SPACE);
        List<AddressSpace> addrSpacesResponse = new ArrayList<>();
        ArrayList<AddressSpace> spaces = new ArrayList<>();
        for (AddressSpace addressSpace : addressSpaces) {
            if (!TestUtils.existAddressSpace(addressApiClient, addressSpace.getName())) {
                log.info("Address space '" + addressSpace + "' doesn't exist and will be created.");
                spaces.add(addressSpace);
                if (!addressSpace.equals(getSharedAddressSpace())) {
                    addressSpaceList.add(addressSpace);
                }
            } else {
                log.warn("Address space '" + addressSpace + "' already exists.");
                addrSpacesResponse.add(TestUtils.getAddressSpaceObject(addressApiClient, addressSpace.getName()));
            }
        }
        addressApiClient.createAddressSpaceList(spaces.toArray(new AddressSpace[0]));
        List<AddressSpace> results = TestUtils.getAddressSpacesObjects(addressApiClient);
        for (AddressSpace addressSpace : results) {
            logCollector.startCollecting(addressSpace);
            addrSpacesResponse.add(TestUtils.waitForAddressSpaceReady(addressApiClient, addressSpace));
        }
        Arrays.stream(addressSpaces).forEach(originalAddrSpace -> {
            originalAddrSpace.setInfraUuid(addrSpacesResponse.stream().filter(
                    resposeAddrSpace -> resposeAddrSpace.getName().equals(originalAddrSpace.getName())).findFirst().get().getInfraUuid());
            if (originalAddrSpace.getEndpoints().isEmpty()) {
                originalAddrSpace.setEndpoints(addrSpacesResponse.stream().filter(
                        resposeAddrSpace -> resposeAddrSpace.getName().equals(originalAddrSpace.getName())).findFirst().get().getEndpoints());
                log.info(String.format("Address-space '%s' endpoints successfully set", originalAddrSpace.getName()));
            }
            log.info(String.format("Address-space successfully created: %s", originalAddrSpace));
        });
        TimeMeasuringSystem.stopOperation(operationID);
    }

    protected void createAddressSpace(AddressSpace addressSpace, AddressApiClient apiClient) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.CREATE_ADDRESS_SPACE);
        AddressSpace addrSpaceResponse;
        if (!TestUtils.existAddressSpace(apiClient, addressSpace.getName())) {
            log.info("Address space '{}' doesn't exist and will be created.", addressSpace);
            apiClient.createAddressSpace(addressSpace);
            addrSpaceResponse = TestUtils.waitForAddressSpaceReady(apiClient, addressSpace);

            if (!addressSpace.equals(getSharedAddressSpace())) {
                addressSpaceList.add(addressSpace);
            }
        } else {
            addrSpaceResponse = TestUtils.getAddressSpaceObject(apiClient, addressSpace.getName());
            log.info("Address space '" + addressSpace + "' already exists.");
        }

        addressSpace.setEndpoints(addrSpaceResponse.getEndpoints());
        log.info("Address-space '{}' endpoints successfully set.", addressSpace.getName());

        addressSpace.setInfraUuid(addrSpaceResponse.getInfraUuid());
        log.info("Address-space successfully created: '{}'", addressSpace);
        TimeMeasuringSystem.stopOperation(operationID);
    }

    protected void deleteAddressspacesFromList() throws Exception {
        log.info("All addressspaces will be removed");
        for (AddressSpace addressSpace : addressSpaceList) {
            deleteAddressSpace(addressSpace);
        }
        addressSpaceList.clear();
    }

    protected void addToAddressSpacess(AddressSpace addressSpace) {
        this.addressSpaceList.add(addressSpace);
    }

    protected static void deleteAddressSpace(AddressSpace addressSpace) throws Exception {
        deleteAddressSpace(addressSpace, addressApiClient);
    }

    protected static void deleteAddressSpace(AddressSpace addressSpace, AddressApiClient apiClient) throws Exception {
        if (TestUtils.existAddressSpace(apiClient, addressSpace.getName())) {
            TestUtils.deleteAddressSpaceAndWait(apiClient, kubernetes, addressSpace, logCollector);
        } else {
            log.info("Address space '" + addressSpace + "' doesn't exists!");
        }
    }

    protected void deleteAllAddressSpaces() throws Exception {
        TestUtils.deleteAllAddressSpaces(addressApiClient, logCollector);
        for (AddressSpace addressSpace : addressSpaceList) {
            TestUtils.waitForAddressSpaceDeleted(kubernetes, addressSpace);
        }
    }

    protected AddressSpace getSharedAddressSpace() {
        return null;
    }

    protected void replaceAddressSpace(AddressSpace addressSpace) throws Exception {
        replaceAddressSpace(addressSpace, true);
    }

    protected void replaceAddressSpace(AddressSpace addressSpace, boolean waitForPlanApplied) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.UPDATE_ADDRESS_SPACE);
        AddressSpace addrSpaceResponse;
        if (TestUtils.existAddressSpace(addressApiClient, addressSpace.getName())) {
            log.info("Address space '{}' exists and will be updated.", addressSpace);
            final String currentResourceVersion = addressApiClient.getAddressSpace(addressSpace.getName()).getJsonObject("metadata").getString("resourceVersion");
            addressApiClient.replaceAddressSpace(addressSpace);
            TestUtils.waitForChangedResourceVersion(ofDuration(ofMinutes(5)), addressApiClient, addressSpace.getName(), currentResourceVersion);
            if (waitForPlanApplied) {
                TestUtils.waitForAddressSpacePlanApplied(addressApiClient, addressSpace);
            }
            addrSpaceResponse = TestUtils.waitForAddressSpaceReady(addressApiClient, addressSpace);

            if (!addressSpace.equals(getSharedAddressSpace())) {
                addressSpaceList.add(addressSpace);
            }
        } else {
            addrSpaceResponse = TestUtils.getAddressSpaceObject(addressApiClient, addressSpace.getName());
            log.info("Address space '{}' does not exists.", addressSpace);
        }

        addressSpace.setEndpoints(addrSpaceResponse.getEndpoints());
        log.info("Address-space '{}' endpoints successfully set.", addressSpace.getName());

        addressSpace.setInfraUuid(addrSpaceResponse.getInfraUuid());
        log.info("Address-space successfully updated: '{}'", addressSpace);
        TimeMeasuringSystem.stopOperation(operationID);
    }

    protected AddressSpace getAddressSpace(String name) throws Exception {
        return TestUtils.getAddressSpaceObject(addressApiClient, name);
    }

    protected List<AddressSpace> getAddressSpaces() throws Exception {
        return TestUtils.getAddressSpacesObjects(addressApiClient);
    }

    protected void waitForAddressSpaceReady(AddressSpace addressSpace) throws Exception {
        waitForAddressSpaceReady(addressSpace, addressApiClient);
    }

    protected void waitForAddressSpaceReady(AddressSpace addressSpace, AddressApiClient apiClient) throws Exception {
        TestUtils.waitForAddressSpaceReady(apiClient, addressSpace);
    }

    protected void waitForAddressSpacePlanApplied(AddressSpace addressSpace) throws Exception {
        TestUtils.waitForAddressSpacePlanApplied(addressApiClient, addressSpace);
    }

    protected boolean reloadAddressSpaceEndpoints(AddressSpace addressSpace) throws Exception {
        AddressSpace addrSpaceResponse = TestUtils.getAddressSpaceObject(addressApiClient, addressSpace.getName());
        addressSpace.setEndpoints(addrSpaceResponse.getEndpoints());
        return !addrSpaceResponse.getEndpoints().isEmpty();
    }

    //================================================================================================
    //====================================== Address methods =========================================
    //================================================================================================

    protected void deleteAddresses(AddressSpace addressSpace, Destination... destinations) throws Exception {
        logCollector.collectConfigMaps();
        logCollector.collectRouterState("deleteAddresses");
        TestUtils.delete(addressApiClient, addressSpace, destinations);
    }

    protected void appendAddresses(AddressSpace addressSpace, Destination... destinations) throws Exception {
        TimeoutBudget budget = new TimeoutBudget(10, TimeUnit.MINUTES);
        appendAddresses(addressSpace, budget, destinations);
    }

    protected void appendAddresses(AddressSpace addressSpace, TimeoutBudget timeout, Destination... destinations) throws Exception {
        appendAddresses(addressSpace, true, timeout, destinations);
    }

    protected void appendAddresses(AddressSpace addressSpace, boolean wait, Destination... destinations) throws Exception {
        TimeoutBudget budget = new TimeoutBudget(10, TimeUnit.MINUTES);
        appendAddresses(addressSpace, wait, budget, destinations);
    }

    protected void appendAddresses(AddressSpace addressSpace, boolean wait, TimeoutBudget timeout, Destination... destinations) throws Exception {
        TestUtils.appendAddresses(addressApiClient, kubernetes, timeout, addressSpace, wait, destinations);
        logCollector.collectConfigMaps();
    }

    protected void appendAddresses(AddressSpace addressSpace, boolean wait, int batchSize, Destination... destinations) throws Exception {
        TimeoutBudget timeout = new TimeoutBudget(10, TimeUnit.MINUTES);
        TestUtils.appendAddresses(addressApiClient, kubernetes, timeout, addressSpace, wait, batchSize, destinations);
        logCollector.collectConfigMaps();
    }

    protected void setAddresses(AddressSpace addressSpace, int expectedCode, Destination... destinations) throws Exception {
        TimeoutBudget budget = new TimeoutBudget(10, TimeUnit.MINUTES);
        logCollector.collectRouterState("setAddresses");

        if (expectedCode == HTTP_CONFLICT) {
            try {
                setAddresses(addressSpace, budget, expectedCode, destinations);
            } catch (ExecutionException ee) {
                log.info(ee.getMessage());
                throw new AddressAlreadyExistsException("Address cannot be created, already exists");
            }
        } else {
            setAddresses(addressSpace, budget, expectedCode, destinations);
        }
    }

    protected void setAddresses(AddressSpace addressSpace, Destination... destinations) throws Exception {
        setAddresses(addressSpace, HttpURLConnection.HTTP_CREATED, destinations);
    }


    protected void setAddresses(AddressSpace addressSpace, TimeoutBudget timeout, Destination... destinations) throws Exception {
        setAddresses(addressSpace, timeout, HttpURLConnection.HTTP_CREATED, destinations);
    }

    protected void setAddresses(AddressSpace addressSpace, TimeoutBudget timeout, int expectedCode, Destination... destinations) throws Exception {
        TestUtils.setAddresses(addressApiClient, kubernetes, timeout, addressSpace, true, expectedCode, destinations);
        logCollector.collectConfigMaps();
    }

    protected void setAddresses(AddressSpace addressSpace, AddressApiClient apiClient, Destination... destinations) throws Exception {
        TestUtils.setAddresses(apiClient, kubernetes, new TimeoutBudget(10, TimeUnit.MINUTES), addressSpace, true, HTTP_CREATED, destinations);
        logCollector.collectConfigMaps();
    }

    protected void replaceAddress(AddressSpace addressSpace, Destination destination) throws Exception {
        TestUtils.replaceAddress(addressApiClient, addressSpace, destination, true, new TimeoutBudget(3, TimeUnit.MINUTES));
    }

    protected JsonObject sendRestApiRequest(HttpMethod method, URL url, int expectedCode, Optional<JsonObject> payload) throws Exception {
        return TestUtils.sendRestApiRequest(addressApiClient, method, url, expectedCode, payload);
    }

    /**
     * give you a list of names of all deployed addresses (or single deployed address)
     *
     * @param addressName name of single address
     * @return list of addresses
     * @throws Exception
     */
    protected Future<List<String>> getAddresses(AddressSpace addressSpace, Optional<String> addressName) throws Exception {
        return TestUtils.getAddresses(addressApiClient, addressSpace, addressName, new ArrayList<>());
    }

    /**
     * give you a list of objects of all deployed addresses (or single deployed address)
     *
     * @param addressName name of single address
     * @return list of addresses
     * @throws Exception
     */
    protected Future<List<Address>> getAddressesObjects(AddressSpace addressSpace, Optional<String> addressName, Optional<HashMap<String, String>> requestParams) throws Exception {
        return TestUtils.getAddressesObjects(addressApiClient, addressSpace, addressName, requestParams, new ArrayList<>());
    }

    protected Future<List<Address>> getAddressesObjects(AddressSpace addressSpace, Optional<String> addressName) throws Exception {
        return getAddressesObjects(addressSpace, addressName, Optional.empty());
    }

    //================================================================================================
    //======================================= User methods ===========================================
    //================================================================================================

    protected UserApiClient getUserApiClient() throws Exception {
        if (userApiClient == null) {
            userApiClient = new UserApiClient(kubernetes);
        }
        return userApiClient;
    }

    protected JsonObject createUser(AddressSpace addressSpace, UserCredentials credentials) throws Exception {
        log.info("User {} in address space {} will be created", credentials, addressSpace.getName());
        if (!userExist(addressSpace, credentials.getUsername())) {
            return getUserApiClient().createUser(addressSpace.getName(), credentials);
        }
        return new JsonObject();
    }

    protected JsonObject createUser(AddressSpace addressSpace, User user) throws Exception {
        log.info("User {} in address space {} will be created", user, addressSpace.getName());
        if (!userExist(addressSpace, user.getUsername())) {
            return getUserApiClient().createUser(addressSpace.getName(), user);
        }
        return new JsonObject();
    }

    protected JsonObject createUserFederated(AddressSpace addressSpace, UserCredentials credentials) throws Exception {
        User user = new User()
                .setUserCredentials(credentials)
                .setType(User.Type.FEDERATED)
                .addAuthorization(new User.AuthorizationRule()
                        .addOperation(User.Operation.MANAGE));
        return createUserFederated(addressSpace, user);
    }

    protected JsonObject createUserFederated(AddressSpace addressSpace, User user) throws Exception {
        log.info("Federated user {} in address space {} will be created", user.getUsername(), addressSpace.getName());
        if (!userExist(addressSpace, user.getUsername())) {
            KubeCMDClient.createOcUser(user.getUsername());
            String userID = KubeCMDClient.getOpenshiftUserId(user.getUsername());
            return getUserApiClient().createUser(
                    addressSpace.getName(),
                    user.toJson(addressSpace.getName(), user.getUsername(), user.getUsername(), userID),
                    HTTP_CREATED);
        }
        return new JsonObject();
    }

    protected JsonObject createUserServiceAccount(AddressSpace addressSpace, UserCredentials credentials, String namespace) throws Exception {
        User user = new User()
                .setUserCredentials(credentials)
                .setType(User.Type.SERVICEACCOUNT)
                .addAuthorization(new User.AuthorizationRule()
                        .addAddress("*")
                        .addOperation(User.Operation.SEND)
                        .addOperation(User.Operation.RECEIVE)
                        .addOperation(User.Operation.VIEW))
                .addAuthorization(new User.AuthorizationRule()
                        .addOperation(User.Operation.MANAGE));
        return createUserServiceaccount(addressSpace, user, namespace);
    }

    protected JsonObject createUserServiceaccount(AddressSpace addressSpace, User user, String namespace) throws Exception {
        log.info("ServiceAccount user {} in address space {} will be created", user.getUsername(), addressSpace.getName());
        if (!userExist(addressSpace, user.getUsername())) {
            String serviceaccountName = kubernetes.createServiceAccount(user.getUsername(), namespace);
            user.setUsername(serviceaccountName);
            return getUserApiClient().createUser(addressSpace.getName(), user);
        }
        return new JsonObject();
    }

    protected JsonObject updateUser(AddressSpace addressSpace, User user) throws Exception {
        log.info("User {} in address space {} will be updated", user, addressSpace.getName());
        return getUserApiClient().updateUser(addressSpace.getName(), user);
    }

    protected void removeUser(AddressSpace addressSpace, String username) throws Exception {
        log.info("User {} in address space {} will be removed", username, addressSpace.getName());
        getUserApiClient().deleteUser(addressSpace.getName(), username);
    }

    protected void createUsers(AddressSpace addressSpace, String prefixName, String prefixPswd, int from, int to)
            throws Exception {
        for (int i = from; i < to; i++) {
            createUser(addressSpace, new UserCredentials(prefixName + i, prefixPswd + i));
        }
    }

    protected void removeUsers(AddressSpace addressSpace, List<String> users) throws Exception {
        for (String user : users) {
            removeUser(addressSpace, user);
        }
    }

    protected void removeUsers(AddressSpace addressSpace, String prefixName, int from, int to) throws Exception {
        for (int i = from; i < to; i++) {
            removeUser(addressSpace, prefixName + i);
        }
    }

    protected boolean userExist(AddressSpace addressSpace, String username) throws Exception {
        String id = String.format("%s.%s", addressSpace.getName(), username);
        JsonObject response = getUserApiClient().getUserList(addressSpace.getName());
        log.info("User list for {}: {}", addressSpace.getName(), response.toString());
        JsonArray users = response.getJsonArray("items");
        for (Object user : users) {
            if (((JsonObject) user).getJsonObject("metadata").getString("name").equals(id)) {
                log.info("User {} in addressspace {} already exists", username, addressSpace.getName());
                return true;
            }
        }
        return false;
    }

    //================================================================================================
    //======================================= Help methods ===========================================
    //================================================================================================

    /**
     * give you a schema object
     *
     * @return schema object
     * @throws Exception
     */
    protected Future<SchemaData> getSchema() throws Exception {
        return TestUtils.getSchema(addressApiClient);
    }

    /**
     * give you a list of objects of all deployed addresses (or single deployed address)
     *
     * @param addressName name of single address
     * @return list of Destinations
     * @throws Exception
     */
    protected Future<List<Destination>> getDestinationsObjects(AddressSpace addressSpace, Optional<String> addressName) throws Exception {
        return TestUtils.getDestinationsObjects(addressApiClient, addressSpace, addressName, new ArrayList<>());
    }

    protected void scaleKeycloak(int numReplicas) throws Exception {
        scaleInGlobal("keycloak", numReplicas);
    }

    /**
     * scale up/down deployment to count of replicas, includes waiting for expected replicas
     *
     * @param deployment  name of deployment
     * @param numReplicas count of replicas
     * @throws InterruptedException
     */
    private void scaleInGlobal(String deployment, int numReplicas) throws InterruptedException {
        if (numReplicas >= 0) {
            TimeoutBudget budget = new TimeoutBudget(10, TimeUnit.MINUTES);
            TestUtils.setReplicas(kubernetes, null, deployment, numReplicas, budget);
        } else {
            throw new IllegalArgumentException("'numReplicas' must be greater than 0");
        }

    }

    protected boolean isBrokered(AddressSpace addressSpace) {
        return addressSpace.getType().equals(AddressSpaceType.BROKERED);
    }

    protected void assertCanConnect(AddressSpace addressSpace, UserCredentials credentials, List<Destination> destinations) throws Exception {
        for (Destination destination : destinations) {
            String message = String.format("Client failed, cannot connect to %s under user %s", destination.getAddress(), credentials);
            AddressType addressType = AddressType.getEnum(destination.getType());
            assertTrue(canConnectWithAmqpAddress(addressSpace, credentials, addressType, destination.getAddress(), true), message);
        }
    }

    protected void assertCannotConnect(AddressSpace addressSpace, UserCredentials credentials, List<Destination> destinations) throws Exception {
        for (Destination destination : destinations) {
            String message = String.format("Client failed, can connect to %s under user %s", destination.getAddress(), credentials);
            AddressType addressType = AddressType.getEnum(destination.getType());
            assertFalse(canConnectWithAmqpAddress(addressSpace, credentials, addressType, destination.getAddress(), false), message);
        }
    }

    private boolean canConnectWithAmqpAddress(AddressSpace addressSpace, UserCredentials credentials, AddressType addressType, String address, boolean defaultValue) throws Exception {
        Set<AddressType> brokeredAddressTypes = new HashSet<>(Arrays.asList(AddressType.QUEUE, AddressType.TOPIC));
        if (isBrokered(addressSpace) && !brokeredAddressTypes.contains(addressType)) {
            return defaultValue;
        }
        AmqpClient client = amqpClientFactory.createAddressClient(addressSpace, addressType);
        client.getConnectOptions().setCredentials(credentials);

        try {
            Future<List<Message>> received = client.recvMessages(address, 1);
            Future<Integer> sent = client.sendMessages(address, Collections.singletonList("msg1"));

            int numReceived = received.get(1, TimeUnit.MINUTES).size();
            int numSent = sent.get(1, TimeUnit.MINUTES);
            return (numSent == numReceived);
        } catch (ExecutionException | SecurityException | UnauthorizedAccessException ex) {
            Throwable cause = ex;
            if (ex instanceof ExecutionException) {
                cause = ex.getCause();
            }

            if (cause instanceof AuthenticationException || cause instanceof SaslSystemException || cause instanceof SecurityException || cause instanceof UnauthorizedAccessException) {
                log.info("canConnectWithAmqpAddress {} ({}): {}", address, addressType, ex.getMessage());
                return false;
            } else {
                log.warn("canConnectWithAmqpAddress {} ({}) exception", address, addressType, ex);
                throw ex;
            }
        } finally {
            client.close();
        }
    }

    protected Endpoint getMessagingRoute(AddressSpace addressSpace) {
        Endpoint messagingEndpoint = addressSpace.getEndpointByServiceName("messaging");
        if (messagingEndpoint == null) {
            String externalEndpointName = TestUtils.getExternalEndpointName(addressSpace, "messaging-" + addressSpace.getInfraUuid());
            messagingEndpoint = kubernetes.getExternalEndpoint(externalEndpointName);
        }
        if (TestUtils.resolvable(messagingEndpoint)) {
            return messagingEndpoint;
        } else {
            return kubernetes.getEndpoint("messaging-" + addressSpace.getInfraUuid(), "amqps");
        }
    }

    protected String getOCConsoleRoute() {
        return String.format("%s/console", environment.getApiUrl());
    }

    protected String getConsoleRoute(AddressSpace addressSpace) {
        Endpoint consoleEndpoint = addressSpace.getEndpointByServiceName("console");
        if (consoleEndpoint == null) {
            String externalEndpointName = TestUtils.getExternalEndpointName(addressSpace, "console");
            consoleEndpoint = kubernetes.getExternalEndpoint(externalEndpointName);
        }
        String consoleRoute = String.format("https://%s", consoleEndpoint.toString());
        log.info(consoleRoute);
        return consoleRoute;
    }

    /**
     * selenium provider with Firefox webdriver
     */
    protected SeleniumProvider getFirefoxSeleniumProvider() throws Exception {
        SeleniumProvider seleniumProvider = new SeleniumProvider();
        seleniumProvider.setupDriver(environment, kubernetes, TestUtils.getFirefoxDriver());
        return seleniumProvider;
    }

    protected void waitForSubscribersConsole(AddressSpace addressSpace, Destination destination, int expectedCount) throws Exception {
        int budget = 60; //seconds
        waitForSubscribersConsole(addressSpace, destination, expectedCount, budget);
    }

    /**
     * wait for expected count of subscribers on topic (check via console)
     *
     * @param budget timeout budget in seconds
     */
    private void waitForSubscribersConsole(AddressSpace addressSpace, Destination destination, int expectedCount, int budget) throws Exception {
        SeleniumProvider selenium = null;
        try {
            SeleniumManagement.deployFirefoxApp();
            selenium = getFirefoxSeleniumProvider();
            ConsoleWebPage console = new ConsoleWebPage(selenium, getConsoleRoute(addressSpace), addressApiClient, addressSpace, defaultCredentials);
            console.openWebConsolePage();
            console.openAddressesPageWebConsole();

            selenium.waitUntilPropertyPresent(budget, expectedCount, () -> console.getAddressItem(destination).getReceiversCount());
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            if (selenium != null) {
                selenium.tearDownDrivers();
            }
            SeleniumManagement.removeFirefoxApp();
        }
    }

    /**
     * wait for expected count of subscribers on topic
     *
     * @param addressSpace
     * @param topic         name of topic
     * @param expectedCount count of expected subscribers
     * @throws Exception
     */
    protected void waitForSubscribers(BrokerManagement brokerManagement, AddressSpace addressSpace, String topic, int expectedCount) throws Exception {
        TimeoutBudget budget = new TimeoutBudget(1, TimeUnit.MINUTES);
        waitForSubscribers(brokerManagement, addressSpace, topic, expectedCount, budget);
    }

    private void waitForSubscribers(BrokerManagement brokerManagement, AddressSpace addressSpace, String topic, int expectedCount, TimeoutBudget budget) throws Exception {
        AmqpClient queueClient = null;
        try {
            queueClient = amqpClientFactory.createQueueClient(addressSpace);
            queueClient.setConnectOptions(queueClient.getConnectOptions().setCredentials(managementCredentials));
            String replyQueueName = "reply-queue";
            Destination replyQueue = Destination.queue(replyQueueName, getDefaultPlan(AddressType.QUEUE));
            appendAddresses(addressSpace, replyQueue);

            boolean done = false;
            int actualSubscribers = 0;
            do {
                actualSubscribers = getSubscriberCount(brokerManagement, queueClient, replyQueue, topic);
                log.info("Have " + actualSubscribers + " subscribers. Expecting " + expectedCount);
                if (actualSubscribers != expectedCount) {
                    Thread.sleep(1000);
                } else {
                    done = true;
                }
            } while (budget.timeLeft() >= 0 && !done);
            if (!done) {
                throw new RuntimeException("Only " + actualSubscribers + " out of " + expectedCount + " subscribed before timeout");
            }
        } finally {
            Objects.requireNonNull(queueClient).close();
        }
    }

    private void waitForBrokerReplicas(AddressSpace addressSpace, Destination destination, int expectedReplicas, boolean readyRequired, TimeoutBudget budget, long checkInterval) throws Exception {
        TestUtils.waitForNBrokerReplicas(addressApiClient, kubernetes, addressSpace, expectedReplicas, readyRequired, destination, budget, checkInterval);
    }

    private void waitForBrokerReplicas(AddressSpace addressSpace, Destination destination,
                                       int expectedReplicas, TimeoutBudget budget) throws Exception {
        waitForBrokerReplicas(addressSpace, destination, expectedReplicas, true, budget, 5000);
    }

    protected void waitForBrokerReplicas(AddressSpace addressSpace, Destination destination, int expectedReplicas) throws
            Exception {
        TimeoutBudget budget = new TimeoutBudget(10, TimeUnit.MINUTES);
        waitForBrokerReplicas(addressSpace, destination, expectedReplicas, budget);
    }

    protected void waitForRouterReplicas(AddressSpace addressSpace, int expectedReplicas) throws
            InterruptedException {
        TimeoutBudget budget = new TimeoutBudget(3, TimeUnit.MINUTES);
        Map<String, String> labels = new HashMap<>();
        labels.put("name", "qdrouterd");
        labels.put("infraUuid", addressSpace.getInfraUuid());
        TestUtils.waitForNReplicas(kubernetes, expectedReplicas, labels, budget);
    }

    /**
     * Wait for destinations are in isReady=true state within default timeout (10 MINUTE)
     */
    protected void waitForDestinationsReady(AddressSpace addressSpace, Destination... destinations) throws Exception {
        TimeoutBudget budget = new TimeoutBudget(10, TimeUnit.MINUTES);
        TestUtils.waitForDestinationsReady(addressApiClient, addressSpace, budget, destinations);
    }

    /**
     * return list of queue names created for subscribers
     *
     * @param queueClient
     * @param replyQueue  queue for answer is required
     * @param topic       topic name
     * @return
     * @throws Exception
     */
    private List<String> getBrokerQueueNames(BrokerManagement brokerManagement, AmqpClient
            queueClient, Destination replyQueue, String topic) throws Exception {
        return brokerManagement.getQueueNames(queueClient, replyQueue, topic);
    }

    /**
     * get count of subscribers subscribed to 'topic'
     *
     * @param queueClient queue client with admin permissions
     * @param replyQueue  queue for answer is required
     * @param topic       topic name
     * @return
     * @throws Exception
     */
    private int getSubscriberCount(BrokerManagement brokerManagement, AmqpClient queueClient, Destination
            replyQueue, String topic) throws Exception {
        List<String> queueNames = getBrokerQueueNames(brokerManagement, queueClient, replyQueue, topic);

        AtomicInteger subscriberCount = new AtomicInteger(0);
        queueNames.forEach((String queue) -> {
            try {
                subscriberCount.addAndGet(brokerManagement.getSubscriberCount(queueClient, replyQueue, queue));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        return subscriberCount.get();
    }

    protected ArrayList<Destination> generateTopicsList(String prefix, IntStream range) {
        ArrayList<Destination> addresses = new ArrayList<>();
        range.forEach(i -> addresses.add(Destination.topic(prefix + i, getDefaultPlan(AddressType.QUEUE))));
        return addresses;
    }

    protected ArrayList<Destination> generateQueueList(String prefix, IntStream range) {
        ArrayList<Destination> addresses = new ArrayList<>();
        range.forEach(i -> addresses.add(Destination.queue(prefix + i, getDefaultPlan(AddressType.QUEUE))));
        return addresses;
    }

    protected ArrayList<Destination> generateQueueTopicList(String infix, IntStream range) {
        ArrayList<Destination> addresses = new ArrayList<>();
        range.forEach(i -> {
            if (i % 2 == 0) {
                addresses.add(Destination.topic(String.format("topic-%s-%d", infix, i), getDefaultPlan(AddressType.TOPIC)));
            } else {
                addresses.add(Destination.queue(String.format("queue-%s-%d", infix, i), getDefaultPlan(AddressType.QUEUE)));
            }
        });
        return addresses;
    }

    protected boolean sendMessage(AddressSpace addressSpace, AbstractClient client, UserCredentials
            credentials, String address, String content, int count, boolean logToOutput) {
        ClientArgumentMap arguments = new ClientArgumentMap();
        arguments.put(ClientArgument.USERNAME, credentials.getUsername());
        arguments.put(ClientArgument.PASSWORD, credentials.getPassword());
        arguments.put(ClientArgument.CONN_SSL, "true");
        arguments.put(ClientArgument.MSG_CONTENT, content);
        arguments.put(ClientArgument.BROKER, getMessagingRoute(addressSpace).toString());
        arguments.put(ClientArgument.ADDRESS, address);
        arguments.put(ClientArgument.COUNT, Integer.toString(count));
        client.setArguments(arguments);

        return client.run(logToOutput);
    }

    /**
     * attach N receivers into one address with default username/password
     */
    protected List<AbstractClient> attachReceivers(AddressSpace addressSpace, Destination destination,
                                                   int receiverCount) throws Exception {
        return attachReceivers(addressSpace, destination, receiverCount, defaultCredentials);
    }

    /**
     * attach N receivers into one address with own username/password
     */
    List<AbstractClient> attachReceivers(AddressSpace addressSpace, Destination destination,
                                         int receiverCount, UserCredentials credentials) throws Exception {
        ClientArgumentMap arguments = new ClientArgumentMap();
        arguments.put(ClientArgument.BROKER, getMessagingRoute(addressSpace).toString());
        arguments.put(ClientArgument.TIMEOUT, "120");
        arguments.put(ClientArgument.CONN_SSL, "true");
        arguments.put(ClientArgument.USERNAME, credentials.getUsername());
        arguments.put(ClientArgument.PASSWORD, credentials.getPassword());
        arguments.put(ClientArgument.LOG_MESSAGES, "json");
        arguments.put(ClientArgument.ADDRESS, destination.getAddress());
        arguments.put(ClientArgument.CONN_PROPERTY, "connection_property1~50");
        arguments.put(ClientArgument.CONN_PROPERTY, "connection_property2~testValue");

        List<AbstractClient> receivers = new ArrayList<>();
        for (int i = 0; i < receiverCount; i++) {
            RheaClientReceiver rec = new RheaClientReceiver();
            rec.setArguments(arguments);
            rec.runAsync(false);
            receivers.add(rec);
        }

        Thread.sleep(15000); //wait for attached
        return receivers;
    }

    /**
     * attach senders to destinations (for N-th destination is attached N+1 senders)
     */
    List<AbstractClient> attachSenders(AddressSpace addressSpace, List<Destination> destinations) {
        List<AbstractClient> senders = new ArrayList<>();

        ClientArgumentMap arguments = new ClientArgumentMap();
        arguments.put(ClientArgument.BROKER, getMessagingRoute(addressSpace).toString());
        arguments.put(ClientArgument.TIMEOUT, "60");
        arguments.put(ClientArgument.CONN_SSL, "true");
        arguments.put(ClientArgument.USERNAME, defaultCredentials.getUsername());
        arguments.put(ClientArgument.PASSWORD, defaultCredentials.getPassword());
        arguments.put(ClientArgument.LOG_MESSAGES, "json");
        arguments.put(ClientArgument.MSG_CONTENT, "msg no.%d");
        arguments.put(ClientArgument.COUNT, "30");
        arguments.put(ClientArgument.DURATION, "30");
        arguments.put(ClientArgument.CONN_PROPERTY, "connection_property1~50");
        arguments.put(ClientArgument.CONN_PROPERTY, "connection_property2~testValue");

        for (int i = 0; i < destinations.size(); i++) {
            arguments.put(ClientArgument.ADDRESS, destinations.get(i).getAddress());
            for (int j = 0; j < i + 1; j++) {
                AbstractClient send = new RheaClientSender();
                send.setArguments(arguments);
                send.runAsync(false);
                senders.add(send);
            }
        }

        return senders;
    }

    /**
     * attach receivers to destinations (for N-th destination is attached N+1 senders)
     */
    List<AbstractClient> attachReceivers(AddressSpace addressSpace, List<Destination> destinations) {
        List<AbstractClient> receivers = new ArrayList<>();

        ClientArgumentMap arguments = new ClientArgumentMap();
        arguments.put(ClientArgument.BROKER, getMessagingRoute(addressSpace).toString());
        arguments.put(ClientArgument.TIMEOUT, "60");
        arguments.put(ClientArgument.CONN_SSL, "true");
        arguments.put(ClientArgument.USERNAME, defaultCredentials.getUsername());
        arguments.put(ClientArgument.PASSWORD, defaultCredentials.getPassword());
        arguments.put(ClientArgument.LOG_MESSAGES, "json");
        arguments.put(ClientArgument.CONN_PROPERTY, "connection_property1~50");
        arguments.put(ClientArgument.CONN_PROPERTY, "connection_property2~testValue");

        for (int i = 0; i < destinations.size(); i++) {
            arguments.put(ClientArgument.ADDRESS, destinations.get(i).getAddress());
            for (int j = 0; j < i + 1; j++) {
                AbstractClient rec = new RheaClientReceiver();
                rec.setArguments(arguments);
                rec.runAsync(false);
                receivers.add(rec);
            }
        }

        return receivers;
    }

    /**
     * create M connections with N receivers and K senders
     */
    protected AbstractClient attachConnector(AddressSpace addressSpace, Destination destination,
                                             int connectionCount,
                                             int senderCount, int receiverCount, UserCredentials credentials) {
        ClientArgumentMap arguments = new ClientArgumentMap();
        arguments.put(ClientArgument.BROKER, getMessagingRoute(addressSpace).toString());
        arguments.put(ClientArgument.TIMEOUT, "120");
        arguments.put(ClientArgument.CONN_SSL, "true");
        arguments.put(ClientArgument.USERNAME, credentials.getUsername());
        arguments.put(ClientArgument.PASSWORD, credentials.getPassword());
        arguments.put(ClientArgument.OBJECT_CONTROL, "CESR");
        arguments.put(ClientArgument.ADDRESS, destination.getAddress());
        arguments.put(ClientArgument.COUNT, Integer.toString(connectionCount));
        arguments.put(ClientArgument.SENDER_COUNT, Integer.toString(senderCount));
        arguments.put(ClientArgument.RECEIVER_COUNT, Integer.toString(receiverCount));
        arguments.put(ClientArgument.CONN_PROPERTY, "connection_property1~50");
        arguments.put(ClientArgument.CONN_PROPERTY, "connection_property2~testValue");

        AbstractClient cli = new RheaClientConnector();
        cli.setArguments(arguments);
        cli.runAsync(false);

        return cli;
    }

    /**
     * stop all clients from list of Abstract clients
     */
    protected void stopClients(List<AbstractClient> clients) {
        if (clients != null) {
            log.info("Stopping clients...");
            clients.forEach(AbstractClient::stop);
        }
    }

    /**
     * create users and groups for wildcard authz tests
     */
    protected List<User> createUsersWildcard(AddressSpace addressSpace, String operation) throws
            Exception {
        List<User> users = new ArrayList<>();
        users.add(new User()
                .setUsername("user1")
                .setPassword("password")
                .addAuthorization(new User.AuthorizationRule()
                        .addOperation(operation)
                        .addAddress("*")));

        users.add(new User()
                .setUsername("user2")
                .setPassword("password")
                .addAuthorization(new User.AuthorizationRule()
                        .addOperation(operation)
                        .addAddress("queue/*")));

        users.add(new User()
                .setUsername("user3")
                .setPassword("password")
                .addAuthorization(new User.AuthorizationRule()
                        .addOperation(operation)
                        .addAddress("topic/*")));

        users.add(new User()
                .setUsername("user4")
                .setPassword("password")
                .addAuthorization(new User.AuthorizationRule()
                        .addOperation(operation)
                        .addAddress("queueA*")));

        users.add(new User()
                .setUsername("user5")
                .setPassword("password")
                .addAuthorization(new User.AuthorizationRule()
                        .addOperation(operation)
                        .addAddress("topicA*")));

        for (User user : users) {
            createUser(addressSpace, user);
        }
        return users;
    }

    protected List<Destination> getAddressesWildcard() {
        Destination queue = Destination.queue("queue/1234", getDefaultPlan(AddressType.QUEUE));
        Destination queue2 = Destination.queue("queue/ABCD", getDefaultPlan(AddressType.QUEUE));
        Destination topic = Destination.topic("topic/2345", getDefaultPlan(AddressType.TOPIC));
        Destination topic2 = Destination.topic("topic/ABCD", getDefaultPlan(AddressType.TOPIC));

        return Arrays.asList(queue, queue2, topic, topic2);
    }

    protected void logWithSeparator(Logger logger, String... messages) {
        logger.info("--------------------------------------------------------------------------------");
        for (String message : messages)
            logger.info(message);
    }

    /**
     * body for rest api tests
     */
    protected void runRestApiTest(AddressSpace addressSpace, Destination d1, Destination d2) throws Exception {
        List<String> destinationsNames = Arrays.asList(d1.getAddress(), d2.getAddress());
        setAddresses(addressSpace, d1);
        appendAddresses(addressSpace, d2);

        //d1, d2
        Future<List<String>> response = getAddresses(addressSpace, Optional.empty());
        assertThat("Rest api does not return all addresses", response.get(1, TimeUnit.MINUTES), is(destinationsNames));
        log.info("addresses {} successfully created", Arrays.toString(destinationsNames.toArray()));

        //get specific address d2
        response = getAddresses(addressSpace, Optional.ofNullable(TestUtils.sanitizeAddress(d2.getName())));
        assertThat("Rest api does not return specific address", response.get(1, TimeUnit.MINUTES), is(destinationsNames.subList(1, 2)));

        deleteAddresses(addressSpace, d1);

        //d2
        response = getAddresses(addressSpace, Optional.ofNullable(TestUtils.sanitizeAddress(d2.getName())));
        assertThat("Rest api does not return right addresses", response.get(1, TimeUnit.MINUTES), is(destinationsNames.subList(1, 2)));
        log.info("address {} successfully deleted", d1.getAddress());

        deleteAddresses(addressSpace, d2);

        //empty
        response = getAddresses(addressSpace, Optional.empty());
        assertThat("Rest api returns addresses", response.get(1, TimeUnit.MINUTES), is(Collections.emptyList()));
        log.info("addresses {} successfully deleted", d2.getAddress());

        setAddresses(addressSpace, d1, d2);
        deleteAddresses(addressSpace, d1, d2);

        response = getAddresses(addressSpace, Optional.empty());
        assertThat("Rest api returns addresses", response.get(1, TimeUnit.MINUTES), is(Collections.emptyList()));
        log.info("addresses {} successfully deleted", Arrays.toString(destinationsNames.toArray()));
    }

    protected void sendReceiveLargeMessage(JmsProvider jmsProvider, int sizeInMB, Destination dest, int count) throws Exception {
        sendReceiveLargeMessage(jmsProvider, sizeInMB, dest, count, DeliveryMode.NON_PERSISTENT);
    }

    protected void sendReceiveLargeMessage(JmsProvider jmsProvider, int sizeInMB, Destination dest, int count, int mode) throws Exception {
        int size = sizeInMB * 1024 * 1024;

        Session session = jmsProvider.getConnection().createSession(false, Session.AUTO_ACKNOWLEDGE);
        javax.jms.Queue testQueue = (javax.jms.Queue) jmsProvider.getDestination(dest.getAddress());
        List<javax.jms.Message> messages = jmsProvider.generateMessages(session, count, size);

        MessageProducer sender = session.createProducer(testQueue);
        MessageConsumer receiver = session.createConsumer(testQueue);
        List<javax.jms.Message> recvd;

        jmsProvider.sendMessages(sender, messages, mode, javax.jms.Message.DEFAULT_PRIORITY, javax.jms.Message.DEFAULT_TIME_TO_LIVE);
        log.info("{}MB {} message sent", sizeInMB, mode == DeliveryMode.PERSISTENT ? "durable" : "non-durable");

        recvd = jmsProvider.receiveMessages(receiver, count, 2000);
        assertThat("Wrong count of received messages", recvd.size(), Matchers.is(count));
        log.info("{}MB {} message received", sizeInMB, mode == DeliveryMode.PERSISTENT ? "durable" : "non-durable");
    }

    protected void deleteAddressSpaceCreatedBySC(String namespace, AddressSpace addressSpace) throws Exception {
        TestUtils.deleteAddressSpaceCreatedBySC(kubernetes, addressSpace, namespace, logCollector);
    }

    protected List<Destination> getAllStandardAddresses() {
        return Arrays.asList(
                Destination.queue("test-queue", DestinationPlan.STANDARD_SMALL_QUEUE),
                Destination.topic("test-topic", DestinationPlan.STANDARD_SMALL_TOPIC),
                Destination.queue("test-queue-sharded", DestinationPlan.STANDARD_LARGE_QUEUE),
                Destination.topic("test-topic-sharded", DestinationPlan.STANDARD_LARGE_TOPIC),
                Destination.anycast("test-anycast"),
                Destination.multicast("test-multicast"));
    }

    protected List<Destination> getAllBrokeredAddresses() {
        return Arrays.asList(
                Destination.queue("test-queue", DestinationPlan.BROKERED_QUEUE),
                Destination.topic("test-topic", DestinationPlan.BROKERED_TOPIC));
    }

    protected void sendDurableMessages(AddressSpace addressSpace, Destination destination,
                                       UserCredentials credentials, int count) throws Exception {
        AmqpClient client = amqpClientFactory.createQueueClient(addressSpace);
        client.getConnectOptions().setCredentials(credentials);
        List<Message> listOfMessages = new ArrayList<>();
        IntStream.range(0, count).forEach(num -> {
            Message msg = Message.Factory.create();
            msg.setAddress(destination.getAddress());
            msg.setDurable(true);
            listOfMessages.add(msg);
        });
        Future<Integer> sent = client.sendMessages(destination.getAddress(), listOfMessages.toArray(new Message[0]));
        assertThat("Cannot send durable messages to " + destination, sent.get(1, TimeUnit.MINUTES), is(count));
        client.close();
    }

    protected void receiveDurableMessages(AddressSpace addressSpace, Destination dest,
                                          UserCredentials credentials, int count) throws Exception {
        AmqpClient client = amqpClientFactory.createQueueClient(addressSpace);
        client.getConnectOptions().setCredentials(credentials);
        ReceiverStatus receiverStatus = client.recvMessagesWithStatus(dest.getAddress(), count);
        assertThat("Cannot receive durable messages from " + dest + ". Got " + receiverStatus.getNumReceived(), receiverStatus.getResult().get(1, TimeUnit.MINUTES).size(), is(count));
        client.close();
    }

    //================================================================================================
    //==================================== Asserts methods ===========================================
    //================================================================================================
    protected <T extends Comparable<T>> void assertSorted(String message, Iterable<T> list) throws Exception {
        assertSorted(message, list, false);
    }

    protected <T> void assertSorted(String message, Iterable<T> list, Comparator<T> comparator) throws Exception {
        assertSorted(message, list, false, comparator);
    }

    protected <T extends Comparable<T>> void assertSorted(String message, Iterable<T> list, boolean reverse) {
        log.info("Assert sort reverse: " + reverse);
        if (!reverse)
            assertTrue(Ordering.natural().isOrdered(list), message);
        else
            assertTrue(Ordering.natural().reverse().isOrdered(list), message);
    }

    protected <T> void assertSorted(String message, Iterable<T> list, boolean reverse, Comparator<T> comparator) {
        log.info("Assert sort reverse: " + reverse);
        if (!reverse)
            assertTrue(Ordering.from(comparator).isOrdered(list), message);
        else
            assertTrue(Ordering.from(comparator).reverse().isOrdered(list), message);
    }

    protected void assertWaitForValue(int expected, Callable<Integer> fn, TimeoutBudget budget) throws Exception {
        Integer got = null;
        log.info("waiting for expected value '{}' ...", expected);
        while (budget.timeLeft() >= 0) {
            got = fn.call();
            if (got != null && expected == got.intValue()) {
                return;
            }
            Thread.sleep(100);
        }
        fail(String.format("Incorrect results value! expected: '%s', got: '%s'", expected, Objects.requireNonNull(got).intValue()));
    }

    protected void assertWaitForValue(int expected, Callable<Integer> fn) throws Exception {
        assertWaitForValue(expected, fn, new TimeoutBudget(10, TimeUnit.SECONDS));
    }
}
