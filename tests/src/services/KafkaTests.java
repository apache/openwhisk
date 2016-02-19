/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import kafka.admin.AdminUtils;
import kafka.api.FetchRequest;
import kafka.api.FetchRequestBuilder;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.common.TopicAndPartition;
import kafka.javaapi.FetchResponse;
import kafka.javaapi.OffsetResponse;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.javaapi.producer.Producer;
import kafka.message.MessageAndOffset;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;
import kafka.utils.ZKStringSerializer$;

import org.I0Itec.zkclient.ZkClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import common.WhiskProperties;
import common.TestUtils;

/**
 * Tests that interact directly with kafka
 */
public class KafkaTests {

    @Rule
    public TestRule watcher = TestUtils.makeTestWatcher();

    /**
     * Basic test of publish-subscribe using Kafka.
     */
    @Test
    public void stegosaurus() throws UnsupportedEncodingException, InterruptedException {
        ensureTopicExists("Dinosaurs");

        publish("Dinosaurs", "Stegosaurus");

        String msg = consumeOneMessage("Dinosaurs");
        System.out.println("consumed: " + msg);
        assertTrue(msg.equals("Stegosaurus"));
    }

    /**
     * Create a topic in kafka. This shouldn't really be necessary, but it
     * avoids annoying error messages during tests.
     */
    static void ensureTopicExists(String topic) {
        // Create a ZooKeeper client
        ZkClient zkClient = new ZkClient(WhiskProperties.getZookeeperHost() + ":" + WhiskProperties.getZookeeperPort(), 10000, 10000, ZKStringSerializer$.MODULE$);

        boolean exists = AdminUtils.topicExists(zkClient, topic);
        if (!exists) {
            AdminUtils.createTopic(zkClient, topic, 1, 1, new Properties());
        }
        do {
            TestUtils.sleep(1);
        } while (!AdminUtils.topicExists(zkClient, topic));
    }

    /**
     * Publish a single message to the kafka server
     * 
     */
    private static void publish(String topic, String message) {
        Properties props = new Properties();
        props.put("metadata.broker.list", WhiskProperties.getKafkaHost() + ":" + WhiskProperties.getKafkaPort());
        props.put("serializer.class", "kafka.serializer.StringEncoder");
        props.put("request.required.acks", "1");

        ProducerConfig config = new ProducerConfig(props);

        Producer<String, String> producer = new Producer<String, String>(config);
        KeyedMessage<String, String> data = new KeyedMessage<String, String>(topic, message);
        producer.send(data);
        producer.close();
    }

    /**
     * Pull one message from a Kafka topic. returns null if no messages found
     */
    static String consumeOneMessage(String topic) throws UnsupportedEncodingException, InterruptedException {
        // Note: this fetchSize of 100000 might need to be increased if large
        // batches are written to Kafka
        return consumeMessage(topic, 0, 100000);
    }

    /**
     * Pull one message from a Kafka topic. returns null if no messages found
     */
    private static String consumeMessage(String topic, int partition, int fetchSize) throws UnsupportedEncodingException,
            InterruptedException {
        final String someClient = "someClient";

        // create a consumer to connect to the kafka server port, socket
        // timeout of 10 secs, socket receive buffer of ~1MB
        SimpleConsumer consumer = new SimpleConsumer(WhiskProperties.getKafkaHost(), WhiskProperties.getKafkaPort(), 10000, 1024000, someClient);

        for (int i = 0; i < 10; i++) {
            // try 10 times to fetch, with 1 second pause between attempts
            Thread.sleep(1000);
            long offset = getLastOffset(consumer, topic, partition, kafka.api.OffsetRequest.LatestTime(), someClient);
            offset = offset > 0 ? offset - 1 : 0;
            System.out.format("[%s] reading from offset: %d\n", topic, offset);

            FetchRequest req = new FetchRequestBuilder().clientId(someClient).addFetch(topic, partition, offset, fetchSize).build();
            FetchResponse fetchResponse = consumer.fetch(req);

            assertFalse(fetchResponse.toString(), fetchResponse.hasError());

            ByteBufferMessageSet messages = fetchResponse.messageSet(topic, 0);
            for (MessageAndOffset msg : messages) {
                ByteBuffer payload = msg.message().payload();
                byte[] bytes = new byte[payload.limit()];
                payload.get(bytes);

                consumer.close();
                return new String(bytes, "UTF-8");
            }
        }
        // if we get here we failed to fetch a message after 10 tries in 10
        // seconds
        consumer.close();
        System.err.println("Failed to fetch a message");
        return null;
    }

    /**
     * Get the last offset which was previous consumed by a consumer on a topic.
     */
    private static long getLastOffset(SimpleConsumer consumer, String topic, int partition, long whichTime, String clientName) {
        TopicAndPartition topicAndPartition = new TopicAndPartition(topic, partition);
        Map<TopicAndPartition, PartitionOffsetRequestInfo> requestInfo = new HashMap<TopicAndPartition, PartitionOffsetRequestInfo>();
        requestInfo.put(topicAndPartition, new PartitionOffsetRequestInfo(whichTime, 1));
        kafka.javaapi.OffsetRequest request = new kafka.javaapi.OffsetRequest(requestInfo,
                kafka.api.OffsetRequest.CurrentVersion(), clientName);
        OffsetResponse response = consumer.getOffsetsBefore(request);

        if (response.hasError()) {
            System.err.println("Error fetching data Offset Data the Broker. Reason: " + response.errorCode(topic, partition));
            return 0;
        }

        long[] offsets = response.offsets(topic, partition);
        return offsets[0];
    }
}
