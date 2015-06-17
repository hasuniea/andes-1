/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.andes.mqtt;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dna.mqtt.wso2.AndesMQTTBridge;
import org.wso2.andes.kernel.AndesException;
import org.wso2.andes.kernel.SubscriptionAlreadyExistsException;
import org.wso2.andes.mqtt.connectors.DistributedStoreConnector;
import org.wso2.andes.mqtt.connectors.MQTTConnector;
import org.wso2.andes.mqtt.utils.MQTTUtils;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.dna.mqtt.wso2.AndesMQTTBridge.QOSLevel;
import static org.dna.mqtt.wso2.AndesMQTTBridge.SubscriptionEvent;
import static org.dna.mqtt.wso2.AndesMQTTBridge.getBridgeInstance;

/**
 * Will manage and hold topic information,
 * this class will be declared as singleton since the state of the topicSubscriptions will be
 * preserved here, all the operations relational to a topic will go through this.
 */
public class MQTTopicManager {

    /*
     * Will log the messages generated through the class
     */
    private static Log log = LogFactory.getLog(MQTTopicManager.class);
    /**
     * Channel id will be defined as the key and the value will hold the topic<->subscription information
     * We could go with the hash map since we don't need immediate reflection of values being added during the runtime
     * i.e subscription getting bound when a message is given out for distribution, for topicSubscriptions we only need to deliver
     * messages to subscribers who were bound before the message was published to the broker
     */
    private Map<String, MQTTopics> topicSubscriptions = new HashMap<String, MQTTopics>();
    /**
     * The instance which will be referred
     */
    private static MQTTopicManager instance = new MQTTopicManager();

    /**
     * Will keep the reference with the bridge
     */
    private static AndesMQTTBridge mqttAndesConnectingBridge = null;

    /**
     * The channel reference which will be used to interact with the Andes Kernal
     */
    private MQTTConnector connector = new DistributedStoreConnector();

    /**
     * The class will be declared as singleton since the state will be centralized
     */
    private MQTTopicManager() {
    }

    /**
     * All centralized instance will be taken for
     *
     * @return the instance which will hold all relevant topic information
     */
    public static MQTTopicManager getInstance() {
        return instance;
    }

    /**
     * Will initialize the bridge which will connect with the MQTT protocol handler
     *
     * @param mqttAndesConnection connection
     * @throws MQTTException will invalidate if attempted to initialize more than once
     */
    public void initProtocolEngine(AndesMQTTBridge mqttAndesConnection) throws MQTTException {
        if (null == mqttAndesConnectingBridge) {
            mqttAndesConnectingBridge = mqttAndesConnection;
            log.info("MQTT andes connecting bridge initialized successfully");
        } else {
            final String error = "Attempting to initialize the bridge more than once, there cannot be more than " +
                    "one bridge instance";
            log.error(error);
            throw new MQTTException(error);
        }
    }

    /**
     * Adds a topic message to the cluster
     *
     * @param messageContext Which will hold message information
     * @throws MQTTException
     * @see org.wso2.andes.mqtt.MQTTMessageContext
     */
    public void addTopicMessage(MQTTMessageContext messageContext) throws MQTTException {

        if (log.isDebugEnabled()) {
            log.debug("Incoming message received with id : " + messageContext.getMqttLocalMessageID() + ", QOS level : "
                    + messageContext.getQosLevel() + ", for topic :" + messageContext.getTopic() + ", with retain :" +
                    messageContext.isRetain());
        }
        //Will add the topic message to the cluster for distribution
        try {
            connector.addMessage(messageContext);
        } catch (MQTTException e) {
            //Will need to rollback the state
            final String error = "Error occurred while publishing the message";
            log.error(error, e);
            throw e;
        }

    }

    /**
     * Will include the topic subscription to the list of topicSubscriptions maintained.
     *
     * @param topicName           the name of the topic the subscription is being registered for
     * @param mqttClientChannelID the channel identity of the subscriber maintained by the protocol reference
     * @param qos                 the level of QOS the message subscription was created the value can be wither 0,1 or 2
     * @param isCleanSession      indicates whether the subscription should be durable or not
     * @throws MQTTException if the subscriber addition was not successful
     * @see org.dna.mqtt.wso2.AndesMQTTBridge.QOSLevel
     */
    public void addTopicSubscription(String topicName, String mqttClientChannelID, QOSLevel qos,
                                     boolean isCleanSession) throws MQTTException {
        //Will extract out the topic information if the topic is created already
        MQTTopics topics = topicSubscriptions.get(mqttClientChannelID);
        String subscriptionID = null;
        //Will generate a unique identifier for the subscription
        final UUID subscriptionChannelID = MQTTUtils.generateSubscriptionChannelID(mqttClientChannelID, topicName,
                qos.getQosValue(), isCleanSession);
        //If the topic has not being created before
        if (null == topics) {
            //First the topic should be registered in the cluster
            //Once the cluster registration is successful the topic will be created
            topics = new MQTTopics(mqttClientChannelID);
            //Will set the topic specific subscription id generated
            topicSubscriptions.put(mqttClientChannelID, topics);

        } else {
            if (log.isDebugEnabled()) {
                log.debug("The topic " + topics + "has local subscriptions already");
            }
        }
        //Will add the subscription to the topic
        //The status will be false if the subscriber with the same channel id exists
        try {
            //First the topic should be registered in the cluster
            subscriptionID = registerTopicSubscriptionInCluster(topicName, mqttClientChannelID, isCleanSession,
                    qos, subscriptionChannelID);
            topics.addSubscriber(mqttClientChannelID, qos, isCleanSession, subscriptionID, subscriptionChannelID, topicName);

        } catch (SubscriptionAlreadyExistsException ignore) {
            //We do not throw this any further, the process should not stop due to this
            final String message = "Error while adding the subscriber to the cluster";
            log.error(message, ignore);
        } catch (MQTTException ex) {
            //In case if an error occurs we need to rollback the subscription created cluster wide
            connector.removeSubscriber(this, topicName, subscriptionID, subscriptionChannelID,
                    isCleanSession, mqttClientChannelID);
            final String message = "Error while adding the subscriber to the cluster";
            log.error(message, ex);
            throw ex;
        }
    }

    /**
     * Will be called during the event where the subscriber disconnection or un-subscription is triggered
     *
     * @param mqttClientChannelID the id of the channel which the subscriber is bound to
     * @param unSubscribedTopic   the name of the topic unsubscribed
     * @param action              describes whether its a disconnection or an un-subscription
     * @throws MQTTException occurs if the subscriber was not disconnected properly
     */
    public void removeOrDisconnectTopicSubscription(String mqttClientChannelID, String unSubscribedTopic,
                                                    SubscriptionEvent action) throws MQTTException {

        Collection<MQTTSubscription> topicSubscriptionList;
        MQTTopics mqtTopics = topicSubscriptions.get(mqttClientChannelID);

        if (null != mqtTopics) {
            if (null == unSubscribedTopic) {
                //this means we need to remove all subscriptions relevant for the channel
                topicSubscriptionList = mqtTopics.getAllSubscriptionsForChannel();
            } else {
                topicSubscriptionList = new ArrayList<MQTTSubscription>();
                topicSubscriptionList.add(mqtTopics.getSubscription(unSubscribedTopic));
            }

            for (MQTTSubscription subscription : topicSubscriptionList) {
                //Will get the topic name bound for subscription
                String topic = subscription.getTopicName();
                //Will remove the subscriber off the list
                mqtTopics.removeSubscriber(topic);
                String subscriberChannelID = subscription.getSubscriberChannelID();
                UUID subscriberChannel = subscription.getSubscriptionChannel();
                boolean isCleanSession = subscription.isCleanSession();
                //The corresponding subscription created cluster wide will be topic name and the local channel id
                //Will remove the subscriber cluster wide
                try {
                    //Will indicate the disconnection of the topic
                    if (action == SubscriptionEvent.DISCONNECT && !(subscription.getQOSLevel() == QOSLevel.AT_MOST_ONCE
                            && !subscription.isCleanSession())) {
                        connector.disconnectSubscriber(this, topic, subscriberChannelID, subscriberChannel,
                                isCleanSession, mqttClientChannelID);
                    } else {
                        //If un-subscribed we need to remove the subscription off
                        connector.removeSubscriber(this, topic, subscriberChannelID, subscriberChannel,
                                isCleanSession, mqttClientChannelID);
                    }
                    if (log.isDebugEnabled()) {
                        final String message = "Subscription with cluster id " + subscriberChannelID + " disconnected " +
                                "from topic " + topic;
                        log.debug(message);
                    }

                } catch (MQTTException ex) {
                    //Should re state the connection of the subscriber back to the map
                    mqtTopics.addSubscriber(unSubscribedTopic, subscription);
                    final String error = "Error occurred while removing the subscription " + mqttClientChannelID;
                    log.error(error, ex);
                    throw ex;
                }
            }
        } else {
            //If the connection is publisher based
            UUID publisherID = connector.removePublisher(mqttClientChannelID);
            if (null == publisherID) {
                log.warn("A subscriber or a publisher with Connection with id " + mqttClientChannelID + " cannot be " +
                        "found to disconnect.");
            }
        }
    }

    /**
     * Will notify to the subscribers who are bound to the topic
     *
     * @param storageName   the topic the message was published that will be stored
     * @param message       the message content
     * @param messageID     the identifier of the message
     * @param publishedQOS  the level of qos the message was published
     * @param shouldRetain  whether the message should retain after it was published
     * @param subscriberQOS the level of QOS of the subscription
     * @throws MQTTException during a failure to deliver the message to the subscribers
     */
    public void distributeMessageToSubscriber(String storageName, String destination, ByteBuffer message, long messageID,
                                              int publishedQOS, boolean shouldRetain, String channelID, int subscriberQOS)
            throws MQTTException {
        //Will generate a unique id, cannot force MQTT to have a long as the message id since the protocol looks for
        //unsigned short
        int mqttLocalMessageID = 1;

        //We need to keep track of the message if the QOS level is > 0
        if (subscriberQOS > QOSLevel.AT_MOST_ONCE.getQosValue()) {
            //We need to add the message information to maintain state, in-order to identify the messages
            // once the acks receive

            MQTTopics topicSubscriptions = this.topicSubscriptions.get(channelID);

            //There could be a situation where the message was published, but before it arrived to the subscription
            //The subscriber has disconnected at a situation as such we have to indicate the disconnection
            if (null != topicSubscriptions) {
                Integer mid = topicSubscriptions.addOnFlightMessage(destination, storageName, messageID);

                if (log.isDebugEnabled()) {
                    log.debug("The message with id " + mid + " is sent for delivery to subscriber, " + channelID +
                            " for topic " + destination);
                }
                getBridgeInstance().distributeMessageToSubscriptions(destination, publishedQOS, message,
                        shouldRetain, mid, channelID);
            } else {
                throw new MQTTException("The subscriber with id " + channelID +
                        " has disconnected hence message will not be published to " + messageID);
            }
        } else {
            getBridgeInstance().distributeMessageToSubscriptions(destination, publishedQOS, message,
                    shouldRetain, mqttLocalMessageID, channelID);
        }
    }

    /**
     * Will trigger during the time where an ack was received for a message
     *
     * @param mqttChannelID the identifier of the channel
     * @param messageID     the message id on which the ack was received
     */
    public void onMessageAck(String mqttChannelID, int messageID) throws MQTTException {
        if (log.isDebugEnabled()) {
            log.debug("Message ack received for id " + messageID + " for subscription " + mqttChannelID);
        }

        MQTTopics subscriptions = topicSubscriptions.get(mqttChannelID);

        if (null != subscriptions) {
            MQTTSubscription subscription = subscriptions.removeOnFlightMessage(messageID);
            if (null != subscription) {
                long clusterSpecificMessageID = subscription.ackReceived(messageID);
                String subscribedTopic = subscription.getTopicName();
                String storageQueueIdentifier = subscription.getStorageIdentifier();
                UUID subscriptionChannel = subscription.getSubscriptionChannel();
                //Informs the cluster regarding the subscription
                messageAck(subscribedTopic, clusterSpecificMessageID, storageQueueIdentifier, subscriptionChannel);
            } else {
                String error = "Could not find information to get subscription information for message ack with id " +
                        messageID + " for channel " + mqttChannelID;
                log.error(error);
            }
        } else {
            String error = "A message acknowledgment had arrived for id " + messageID + " for subscription " +
                    mqttChannelID + " but the subscriber information cannot be found";
            log.error(error);
        }

    }

    /**
     * This will be called when simulating the ack for the server for QOS 0 messages
     *
     * @param topic        the name of the topic the ack will be simulated for
     * @param messageID    the id of the message the ack will be simulated for
     * @param storageName  the storage name representation
     * @param subChannelID the id of the subscription channel
     * @throws MQTTException occurs if the ack failed to be processed by the kernal
     */
    public void implicitAck(String topic, long messageID, String storageName, UUID subChannelID) throws MQTTException {
        messageAck(topic, messageID, storageName, subChannelID);
    }

    /**
     * Will interact with the kernal and will create a cluster wide indication of the topic
     *
     * @param topicName             the name of the topic which should be registered in the cluster
     * @param mqttClientID          the subscriber id which is local to the node
     * @param isCleanSession        should the subscription be identified as durable
     * @param qos                   the subscriber level qos
     * @param subscriptionChannelID the unique identifier of the subscription channel
     * @return topic subscription id which will represent the topic in the cluster
     */
    private String registerTopicSubscriptionInCluster(String topicName, String mqttClientID, boolean isCleanSession,
                                                      QOSLevel qos, UUID subscriptionChannelID)
            throws MQTTException, SubscriptionAlreadyExistsException {
        //Will generate a unique id for the client
        //Per topic only one subscription will be created across the cluster
        String topicSpecificClientID = MQTTUtils.generateTopicSpecficClientID(mqttClientID);
        if (log.isDebugEnabled()) {
            log.debug("Cluster wide topic connection was created with id " + topicSpecificClientID + " for topic " +
                    topicName + " with clean session " + isCleanSession);
        }

        //Will register the topic cluster wide
        connector.addSubscriber(this, topicName, topicSpecificClientID, mqttClientID, isCleanSession,
                qos, subscriptionChannelID);

        return topicSpecificClientID;
    }

    /**
     * When acknowledgments arrive for the delivered messages this method will be called
     *
     * @param topic       the name of the topic the ack was received
     * @param messageID   the id of the message the ack was received
     * @param storageName the name of the representation of the topic in the store
     * @throws MQTTException at an event where the ack was not properly processed
     */
    private void messageAck(String topic, long messageID, String storageName, UUID subChannelID) throws MQTTException {
        try {
            connector.messageAck(messageID, topic, storageName, subChannelID);
        } catch (AndesException ex) {
            final String message = "Error occurred while cleaning up the acked message";
            log.error(message, ex);
            throw new MQTTException(message, ex);
        }
    }

}
