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

import static org.junit.Assert.assertTrue;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.GroupCoordinatorNotAvailableException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import common.TestUtils;
import common.WhiskProperties;

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
    public void stegosaurus() throws UnsupportedEncodingException, InterruptedException, ExecutionException {
        for (int i = 0; i < 10; i++) {
            String msg = "Stegosaurus-" + System.currentTimeMillis();
            System.out.println(msg);
            publish("Dinosaurs", msg);

            String received = consumeOneMessage("Dinosaurs");
            System.out.println("consumed: " + received);
            assertTrue(received.equals(msg));
        }
    }

    /**
     * Publishes a single message to the kafka server.
     */
    private static void publish(String topic, String message) throws InterruptedException, ExecutionException {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, WhiskProperties.getKafkaHost() + ":" + WhiskProperties.getKafkaPort());
        props.put(ProducerConfig.ACKS_CONFIG, "1");

        StringSerializer keySerializer = new StringSerializer();
        StringSerializer valueSerializer = new StringSerializer();
        KafkaProducer<String, String> producer = new KafkaProducer<String, String>(props, keySerializer, valueSerializer);
        ProducerRecord<String, String> data = new ProducerRecord<String, String>(topic, message);
        RecordMetadata status = producer.send(data).get();
        System.out.format("sent message: %s[%d][%d]\n", status.topic(), status.partition(), status.offset());
        producer.close();
    }

    /**
     * Pulls messages message from a Kafka topic and returns the most recent one
     * or null if no messages found.
     */
    static String consumeOneMessage(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kafkatest");
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, WhiskProperties.getKafkaHost() + ":" + WhiskProperties.getKafkaPort());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        ByteArrayDeserializer keyDeserializer = new ByteArrayDeserializer();
        ByteArrayDeserializer valueDeserializer = new ByteArrayDeserializer();
        KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<byte[], byte[]>(props, keyDeserializer, valueDeserializer);
        try {
            consumer.subscribe(Arrays.asList(topic));
            System.out.print("received: ");
            ConsumerRecords<byte[], byte[]> records = consumer.poll(10000);
            System.out.println(records.count());
            assertTrue(records.count() >= 1);
            String last = null;
            for (ConsumerRecord<byte[], byte[]> record : records) {
                String result = new String(record.value());
                System.out.println(result);
                last = result;
            }
            return last;
        } catch (GroupCoordinatorNotAvailableException e) {
            // see http://permalink.gmane.org/gmane.comp.apache.kafka.user/10313
            // may fail the very first time
            System.out.println("retrying");
            return consumeOneMessage(topic);
        } finally {
            consumer.close();
        }
    }
}
