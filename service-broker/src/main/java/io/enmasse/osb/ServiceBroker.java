/*
 * Copyright 2016-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.osb;

import io.enmasse.address.model.CoreCrd;
import io.enmasse.admin.model.v1.AdminCrd;
import io.enmasse.api.auth.AuthApi;
import io.enmasse.api.auth.KubeAuthApi;
import io.enmasse.k8s.api.CachingSchemaProvider;
import io.enmasse.k8s.api.AddressSpaceApi;
import io.enmasse.k8s.api.ConfigMapAddressSpaceApi;
import io.enmasse.k8s.api.KubeSchemaApi;
import io.enmasse.k8s.api.SchemaApi;
import io.enmasse.user.api.NullUserApi;
import io.enmasse.user.api.UserApi;
import io.enmasse.user.keycloak.KeycloakUserApi;
import io.enmasse.osb.api.provision.ConsoleProxy;
import io.enmasse.user.keycloak.KeycloakFactory;
import io.enmasse.user.keycloak.KubeKeycloakFactory;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.NamespacedOpenShiftClient;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Base64;

public class ServiceBroker extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(ServiceBroker.class.getName());
    private final NamespacedOpenShiftClient client;
    private final ServiceBrokerOptions options;

    static {
        try {
            CoreCrd.registerCustomCrds();
            AdminCrd.registerCustomCrds();
        } catch (RuntimeException t) {
            t.printStackTrace();
            throw new ExceptionInInitializerError(t);
        }
    }

    private ServiceBroker(ServiceBrokerOptions options) {
        this.client = new DefaultOpenShiftClient();
        this.options = options;
    }

    @Override
    public void start(Future<Void> startPromise) throws Exception {
        SchemaApi schemaApi = KubeSchemaApi.create(client, client.getNamespace(), true);
        CachingSchemaProvider schemaProvider = new CachingSchemaProvider();
        schemaApi.watchSchema(schemaProvider, options.getResyncInterval());

        ensureRouteExists(client, options);
        ensureCredentialsExist(client, options);

        AddressSpaceApi addressSpaceApi = new ConfigMapAddressSpaceApi(client);
        AuthApi authApi = new KubeAuthApi(client, client.getConfiguration().getOauthToken());

        UserApi userApi = createUserApi(options);

        ConsoleProxy consoleProxy = addressSpace -> {
            Route route = client.routes().withName(options.getConsoleProxyRouteName()).get();
            if (route == null) {
                return null;
            }
            return String.format("https://%s/console/%s", route.getSpec().getHost(), addressSpace.getMetadata().getName());
        };

        vertx.deployVerticle(new HTTPServer(addressSpaceApi, schemaProvider, authApi, options.getCertDir(), options.getEnableRbac(), userApi, options.getListenPort(), consoleProxy),
                result -> {
                    if (result.succeeded()) {
                        log.info("EnMasse Service Broker started");
                        startPromise.complete();
                    } else {
                        startPromise.fail(result.cause());
                    }
                });
    }

    private void ensureCredentialsExist(NamespacedOpenShiftClient client, ServiceBrokerOptions options) {
        Secret secret = client.secrets().withName(options.getServiceCatalogCredentialsSecretName()).get();
        if (secret == null) {
            client.secrets().createNew()
                    .editOrNewMetadata()
                    .withName(options.getServiceCatalogCredentialsSecretName())
                    .addToLabels("app", "enmasse")
                    .endMetadata()
                    .addToData("token", Base64.getEncoder().encodeToString(client.getConfiguration().getOauthToken().getBytes(StandardCharsets.UTF_8)))
                    .done();
        }
    }

    private UserApi createUserApi(ServiceBrokerOptions options) {
        KeycloakFactory keycloakFactory = new KubeKeycloakFactory(client,
            options.getStandardAuthserviceConfigName(),
            options.getStandardAuthserviceCredentialsSecretName(),
            options.getStandardAuthserviceCertSecretName());
        if (keycloakFactory.isKeycloakAvailable()) {
            log.info("Using Keycloak for User API");
            return new KeycloakUserApi(keycloakFactory, Clock.systemUTC());
        } else {
            log.info("Using Null for User API");
            return new NullUserApi();
        }
    }

    private static void ensureRouteExists(NamespacedOpenShiftClient client, ServiceBrokerOptions serviceBrokerOptions) throws IOException {
        Route proxyRoute = client.routes().withName(serviceBrokerOptions.getConsoleProxyRouteName()).get();
        if (proxyRoute == null) {
            String caCertificate = new String(Files.readAllBytes(new File(serviceBrokerOptions.getCertDir(), "tls.crt").toPath()), StandardCharsets.UTF_8);
            client.routes().createNew()
                    .editOrNewMetadata()
                    .withName(serviceBrokerOptions.getConsoleProxyRouteName())
                    .addToLabels("app", "enmasse")
                    .endMetadata()
                    .editOrNewSpec()
                    .editOrNewPort()
                    .withNewTargetPort("https")
                    .endPort()
                    .editOrNewTo()
                    .withKind("Service")
                    .withName("service-broker")
                    .endTo()
                    .editOrNewTls()
                    .withTermination("reencrypt")
                    .withCaCertificate(caCertificate)
                    .endTls()
                    .endSpec()
                    .done();
        }
    }

    public static void main(String args[]) {
        try {
            Vertx vertx = Vertx.vertx();
            vertx.deployVerticle(new ServiceBroker(ServiceBrokerOptions.fromEnv(System.getenv())));
        } catch (IllegalArgumentException e) {
            System.out.println(String.format("Unable to parse arguments: %s", e.getMessage()));
            System.exit(1);
        } catch (Exception e) {
            System.out.println("Error starting service broker: " + e.getMessage());
            System.exit(1);
        }
    }
}
