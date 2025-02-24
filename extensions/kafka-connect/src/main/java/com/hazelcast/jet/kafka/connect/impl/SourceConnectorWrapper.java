/*
 * Copyright 2024 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.kafka.connect.impl;

import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.core.Processor.Context;
import com.hazelcast.jet.kafka.connect.impl.message.TaskConfigPublisher;
import com.hazelcast.jet.kafka.connect.impl.message.TaskConfigMessage;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.topic.Message;
import org.apache.kafka.connect.connector.ConnectorContext;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.hazelcast.client.impl.protocol.util.PropertiesUtil.toMap;
import static com.hazelcast.internal.util.Preconditions.checkRequiredProperty;
import static com.hazelcast.jet.impl.util.ExceptionUtil.rethrow;
import static com.hazelcast.jet.impl.util.ReflectionUtils.newInstance;

/**
 * This class wraps a Kafka Connector and TaskRunner
 */
public class SourceConnectorWrapper {
    private final ILogger logger = Logger.getLogger(SourceConnectorWrapper.class);
    private SourceConnector sourceConnector;
    private int tasksMax;
    private TaskRunner taskRunner;
    private final ReentrantLock reconfigurationLock = new ReentrantLock();
    private final State state = new State();
    // Name is specified by user properties
    private String name;
    private final boolean isMasterProcessor;
    private final int processorOrder;
    private TaskConfigPublisher taskConfigPublisher;
    private final AtomicBoolean receivedTaskConfiguration = new AtomicBoolean();
    // this should be refactored later
    private Consumer<Boolean> activeStatusSetter = ignored -> {
    };

    public SourceConnectorWrapper(Properties propertiesFromUser, int processorOrder, Context context) {
        validatePropertiesFromUser(propertiesFromUser);

        this.processorOrder = processorOrder;
        this.isMasterProcessor = processorOrder == 0;

        // Order of resource creation is important.
        // First create the topic. Any processor can create the topic
        createTopic(context.hazelcastInstance(), context.executionId());

        // Then create the source connector. Now source connector can use the topic
        createSourceConnector(propertiesFromUser);
    }

    void validatePropertiesFromUser(Properties propertiesFromUser) {
        checkRequiredProperty(propertiesFromUser, "connector.class");
        name = checkRequiredProperty(propertiesFromUser, "name");

        String propertyValue = checkRequiredProperty(propertiesFromUser, "tasks.max");
        tasksMax = Integer.parseInt(propertyValue);
    }

    void createSourceConnector(Properties propertiesFromUser) {
        String connectorClazz = propertiesFromUser.getProperty("connector.class");
        logger.fine("Initializing connector '" + name + "' of class '" + connectorClazz + "'");

        sourceConnector = newConnectorInstance(connectorClazz);
        sourceConnector.initialize(new JetConnectorContext());

        logger.fine("Starting connector '" + name + "'. Below are the propertiesFromUser");
        Map<String, String> map = toMap(propertiesFromUser);
        sourceConnector.start(map);

        logger.fine("Creating task runner '" + name + "'");
        createTaskRunner();
    }

    // Package private for testing
    TaskRunner createTaskRunner() {
        String taskName = name + "-task-" + processorOrder;
        taskRunner = new TaskRunner(taskName, state, this::createSourceTask);
        requestTaskReconfiguration();
        return taskRunner;
    }

     void setActiveStatusSetter(Consumer<Boolean> activeStatusSetter) {
        this.activeStatusSetter = activeStatusSetter;
    }

    public boolean hasTaskConfiguration() {
        return receivedTaskConfiguration.get();
    }

    void createTopic(HazelcastInstance hazelcastInstance, long executionId) {
        if (hazelcastInstance != null) {
            taskConfigPublisher = new TaskConfigPublisher(hazelcastInstance);
            taskConfigPublisher.createTopic(executionId);

            // All processors must listen the topic
            taskConfigPublisher.addMessageListener(this::processMessage);
        }
    }

    void destroyTopic() {
        taskConfigPublisher.removeMessageListeners();
        if (isMasterProcessor) {
            // Only master processor can destroy the topic
            taskConfigPublisher.destroyTopic();
        }
    }

    protected void publishMessage(TaskConfigMessage taskConfigMessage) {
        if (taskConfigPublisher != null) {
            logger.info("Publishing TaskConfigTopic");
            taskConfigPublisher.publish(taskConfigMessage);
        }
    }

    private void processMessage(Message<TaskConfigMessage> message) {
        logger.info("Received TaskConfigTopic message");
        TaskConfigMessage taskConfigMessage = message.getMessageObject();
        processMessage(taskConfigMessage);
    }

    // Protected so that it can be called from tests to simulate received topic
    protected void processMessage(TaskConfigMessage taskConfigMessage) {
        // Update state
        state.setTaskConfigs(taskConfigMessage.getTaskConfigs());

        // Get my taskConfig
        Map<String, String> taskConfig = state.getTaskConfig(processorOrder);

        final boolean active = taskConfig != null;
        activeStatusSetter.accept(active);

        if (taskConfig != null) {
            // Pass my taskConfig to taskRunner
            logger.info("Updating taskRunner with processorOrder = " + processorOrder
                        + " with taskConfig=" + maskPasswords(taskConfig));

            taskRunner.updateTaskConfig(taskConfig);

        }
        receivedTaskConfiguration.set(true);
    }

    private Map<String, String> maskPasswords(Map<String, String> configMap) {
        var newMap = new LinkedHashMap<>(configMap);
        newMap.replaceAll((k, v) -> {
            if (k.toLowerCase(Locale.ROOT).contains("password")) {
                return "****";
            } else {
                return v;
            }
        });
        return newMap;
    }

    public List<SourceRecord> poll() {
        try {
        return taskRunner.poll();
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    public void commitRecord(SourceRecord sourceRecord) {
        taskRunner.commitRecord(sourceRecord);
    }

    public State copyState() {
        return taskRunner.copyState();
    }

    public void restoreState(State state) {
        taskRunner.restoreState(state);
    }

    public void commit() {
        taskRunner.commit();
    }

    public String getTaskRunnerName() {
        return taskRunner.getName();
    }

    @SuppressWarnings({"ConstantConditions", "java:S1193"})
    private static SourceConnector newConnectorInstance(String connectorClazz) {
        try {
            return newInstance(Thread.currentThread().getContextClassLoader(), connectorClazz);
        } catch (Exception e) {
            if (e instanceof ClassNotFoundException) {
                throw new HazelcastException("Connector class '" + connectorClazz + "' not found. " +
                                             "Did you add the connector jar to the job?", e);
            }
            throw rethrow(e);
        }
    }

    public void close() {
        logger.fine("Stopping connector '" + name + "'");
        taskRunner.stop();
        sourceConnector.stop();
        destroyTopic();
        logger.fine("Connector '" + name + "' stopped");
    }


    private SourceTask createSourceTask() {
        Class<? extends SourceTask> taskClass = sourceConnector.taskClass().asSubclass(SourceTask.class);
        return newInstance(Thread.currentThread().getContextClassLoader(), taskClass.getName());
    }

    /**
     * This method is called from two different places
     * <p>
     * 1. When Connector starts for the first time, it calls this method to distribute taskConfigs
     * <p>
     * 2. When Kafka Connect plugin discovers that task reconfiguration is necessary
     */
    void requestTaskReconfiguration() {
        if (!isMasterProcessor) {
            logger.fine("requestTaskReconfiguration is skipped because Source Connector is not master");
            return;
        }
        try {
            reconfigurationLock.lock();
            logger.info("Updating tasks configuration");
            List<Map<String, String>> taskConfigs = sourceConnector.taskConfigs(tasksMax);

            // Log taskConfigs
            for (int index = 0; index < taskConfigs.size(); index++) {
                Map<String, String> map = taskConfigs.get(index);
                logger.fine("sourceConnector index " + index + " taskConfig=" + map);
            }
            // Publish taskConfigs
            TaskConfigMessage taskConfigMessage = new TaskConfigMessage();
            taskConfigMessage.setTaskConfigs(taskConfigs);
            publishMessage(taskConfigMessage);
            logger.info(taskConfigs.size() + " task configs were sent");
        } finally {
            reconfigurationLock.unlock();
        }
    }

    @Override
    public String toString() {
        return "ConnectorWrapper{" +
               "name='" + name + '\'' +
               ", tasksMax=" + tasksMax +
               ", isMasterProcessor=" + isMasterProcessor +
               ", processorOrder=" + processorOrder +
               ", receivedTaskConfiguration=" + receivedTaskConfiguration +
               '}';
    }

    public boolean hasTaskRunner() {
        return taskRunner != null;
    }

    private class JetConnectorContext implements ConnectorContext {
        @Override
        public void requestTaskReconfiguration() {
            SourceConnectorWrapper.this.requestTaskReconfiguration();
        }

        @Override
        public void raiseError(Exception e) {
            throw rethrow(e);
        }
    }
}
