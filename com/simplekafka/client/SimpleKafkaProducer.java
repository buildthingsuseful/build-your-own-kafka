package com.simplekafka.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Example producer for Build Your Own Kafka
 */
public class SimpleKafkaProducer {
    private static final Logger LOGGER = Logger.getLogger(SimpleKafkaProducer.class.getName());
    private static final int DEFAULT_PARTITIONS = 3;
    private static final short DEFAULT_REPLICATION = 2;
    
    private final SimpleKafkaClient client;
    private final String topic;
    private final Random random;
    private final boolean createTopicIfNotExists;
    
    /**
     * Create a SimpleKafka producer with default settings
     */
    public SimpleKafkaProducer(String bootstrapBroker, int bootstrapPort, String topic) {
        this(bootstrapBroker, bootstrapPort, topic, true);
    }
    
    /**
     * Create a SimpleKafka producer with custom settings
     */
    public SimpleKafkaProducer(String bootstrapBroker, int bootstrapPort, String topic, boolean createTopicIfNotExists) {
        this.client = new SimpleKafkaClient(bootstrapBroker, bootstrapPort);
        this.topic = topic;
        this.random = new Random();
        this.createTopicIfNotExists = createTopicIfNotExists;
    }
    
    /**
     * Initialize the producer
     */
    public void initialize() throws IOException {
        client.initialize();
        
        // Check if topic exists, create if not
        if (client.getTopicMetadata(topic) == null && createTopicIfNotExists) {
            LOGGER.info("Topic does not exist. Creating: " + topic);
            boolean created = client.createTopic(topic, DEFAULT_PARTITIONS, DEFAULT_REPLICATION);
            if (!created) {
                throw new IOException("Failed to create topic: " + topic);
            }
        }
    }
    
    /**
     * Send a message to a random partition
     */
    public long send(String message) throws IOException {
        SimpleKafkaClient.TopicMetadata metadata = client.getTopicMetadata(topic);
        if (metadata == null) {
            throw new IOException("Topic does not exist: " + topic);
        }
        
        int partitionCount = metadata.getPartitions().size();
        int partition = random.nextInt(partitionCount);
        
        return send(message, partition);
    }
    
    /**
     * Send a message to a specific partition
     */
    public long send(String message, int partition) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        return client.send(topic, partition, data);
    }
    
    /**
     * Close the producer
     */
    public void close() {
        // No resources to close in this simple implementation
    }
    
    /**
     * Main method for demonstration
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: SimpleKafkaProducer <broker> <port> <topic>");
            System.exit(1);
        }
        
        String broker = args[0];
        int port = Integer.parseInt(args[1]);
        String topic = args[2];
        
        try {
            SimpleKafkaProducer producer = new SimpleKafkaProducer(broker, port, topic);
            producer.initialize();
            
            System.out.println("Producer initialized. Sending 10 messages...");
            
            // Send 10 messages
            for (int i = 0; i < 10; i++) {
                String message = "Message " + i + " - " + System.currentTimeMillis();
                long offset = producer.send(message);
                System.out.println("Sent message to offset: " + offset);
                
                Thread.sleep(1000); // Wait 1 second between messages
            }
            
            producer.close();
            System.out.println("Producer finished sending messages");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Producer error", e);
        }
    }
}