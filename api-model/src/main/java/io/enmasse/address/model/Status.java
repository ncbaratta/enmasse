/*
 * Copyright 2017-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.address.model;

import java.util.*;

import javax.validation.Valid;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.enmasse.common.model.AbstractHasMetadata;
import io.fabric8.kubernetes.api.model.Doneable;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.BuildableReference;
import io.sundr.builder.annotations.Inline;

/**
 * Represents the status of an address
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder",
        refs= {@BuildableReference(AbstractHasMetadata.class)},
        inline = @Inline(
                type = Doneable.class,
                prefix = "Doneable",
                value = "done"
                )
        )
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Status {

    @JsonProperty("isReady")
    private boolean ready = false;
    private Phase phase = Phase.Pending;
    private List<String> messages = new ArrayList<>();
    private List<@Valid BrokerStatus> brokerStatuses = new ArrayList<>();

    public Status() {
    }

    public Status(boolean ready) {
        this.ready = ready;
    }

    public Status(io.enmasse.address.model.Status other) {
        this.ready = other.isReady();
        this.phase = other.getPhase();
        this.messages = new ArrayList<>(other.getMessages());
        this.brokerStatuses = new ArrayList<>();
        for (BrokerStatus brokerStatus : other.getBrokerStatuses()) {
            brokerStatuses.add(new BrokerStatus(brokerStatus.getClusterId(), brokerStatus.getContainerId(), brokerStatus.getState()));
        }
    }

    public boolean isReady() {
        return ready;
    }

    public Phase getPhase() {
        return phase;
    }

    public List<BrokerStatus> getBrokerStatuses() {
        return Collections.unmodifiableList(brokerStatuses);
    }

    public Status setReady(boolean ready) {
        this.ready = ready;
        return this;
    }

    public Status setPhase(Phase phase) {
        this.phase = phase;
        return this;
    }

    public List<String> getMessages() {
        return messages;
    }

    public Status appendMessage(String message) {
        this.messages.add(message);
        return this;
    }

    public Status clearMessages() {
        this.messages.clear();
        return this;
    }

    public Status setMessages(List<String> messages) {
        this.messages = new ArrayList<>(messages);
        return this;
    }

    public Status appendBrokerStatus(BrokerStatus brokerStatus) {
        this.brokerStatuses.add(brokerStatus);
        return this;
    }

    public Status setBrokerStatuses(List<BrokerStatus> brokerStatuses) {
        this.brokerStatuses = new ArrayList<>(brokerStatuses);
        return this;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Status status = (Status) o;
        return ready == status.ready &&
                phase == status.phase &&
                Objects.equals(messages, status.messages) &&
                Objects.equals(brokerStatuses, status.brokerStatuses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ready, phase, messages, brokerStatuses);
    }


    @Override
    public String toString() {
        return new StringBuilder()
                .append("{ready=").append(ready)
                .append(",").append("phase=").append(phase)
                .append(",").append("messages=").append(messages)
                .append(",").append("brokerStatuses=").append(brokerStatuses)
                .append("}")
                .toString();
    }

    public void addAllBrokerStatuses(List<BrokerStatus> toAdd) {
        brokerStatuses.addAll(toAdd);
    }
}
