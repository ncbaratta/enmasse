/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.selenium;


import io.enmasse.systemtest.*;
import io.enmasse.systemtest.timemeasuring.Operation;
import io.enmasse.systemtest.timemeasuring.TimeMeasuringSystem;
import io.fabric8.kubernetes.api.model.Pod;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class SeleniumManagement {
    private static Logger log = CustomLogger.getLogger();

    public static void deployFirefoxApp() throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.CREATE_SELENIUM_CONTAINER);
        log.info("Deploy firefox deployment");
        try {
            SystemtestsKubernetesApps.deployFirefoxSeleniumApp(SystemtestsKubernetesApps.SELENIUM_PROJECT, Kubernetes.getInstance());
        } catch (Exception e) {
            log.error("Deployment of firefox app failed", e);
            throw e;
        } finally {
            TimeMeasuringSystem.stopOperation(operationID);
        }
    }

    public static void deployChromeApp() throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.CREATE_SELENIUM_CONTAINER);
        log.info("Deploy chrome deployment");
        try {
            SystemtestsKubernetesApps.deployChromeSeleniumApp(SystemtestsKubernetesApps.SELENIUM_PROJECT, Kubernetes.getInstance());
        } catch (Exception e) {
            log.error("Deployment of chrome app failed", e);
            throw e;
        } finally {
            TimeMeasuringSystem.stopOperation(operationID);
        }
    }

    public static void removeFirefoxApp() throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.DELETE_SELENIUM_CONTAINER);
        SystemtestsKubernetesApps.deleteFirefoxSeleniumApp(SystemtestsKubernetesApps.SELENIUM_PROJECT, Kubernetes.getInstance());
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void removeChromeApp() throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(Operation.DELETE_SELENIUM_CONTAINER);
        SystemtestsKubernetesApps.deleteChromeSeleniumApp(SystemtestsKubernetesApps.SELENIUM_PROJECT, Kubernetes.getInstance());
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void restartSeleniumApp() throws Exception {
        List<String> beforeRestart = Kubernetes.getInstance().listPods(SystemtestsKubernetesApps.SELENIUM_PROJECT).stream().map(pod -> pod.getMetadata().getName()).collect(Collectors.toList());
        SystemtestsKubernetesApps.deleteSeleniumPod(SystemtestsKubernetesApps.SELENIUM_PROJECT, Kubernetes.getInstance());
        TestUtils.waitUntilCondition("Selenium pods ready", () -> {
                    List<String> current = TestUtils.listReadyPods(Kubernetes.getInstance(), SystemtestsKubernetesApps.SELENIUM_PROJECT).stream().map(pod -> pod.getMetadata().getName()).collect(Collectors.toList());
                    current.removeAll(beforeRestart);
                    log.info("Following pods are in ready state {}", current);
                    return  current.size() == beforeRestart.size();
                },
                new TimeoutBudget(1, TimeUnit.MINUTES));
    }
}
