package com.simplekafka.broker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Defines the wire protocol for Build Your Own Kafka
 */
public class Protocol {
    // Client request types
    public static final byte PRODUCE = 0x01;
    public static final byte FETCH = 0x02;
    public static final byte METADATA = 0x03;
    public static final byte CREATE_TOPIC = 0x04;
    
    // Broker response types
    public static final byte PRODUCE_RESPONSE = 0x11;
    public static final byte FETCH_RESPONSE = 0x12;
    public static final byte METADATA_RESPONSE = 0x13;
    public static final byte CREATE_TOPIC_RESPONSE = 0x14;
    public static final byte ERROR_RESPONSE = 0x1F;
    
    // Internal broker communication
    public static final byte REPLICATE = 0x21;
    public static final byte REPLICATE_ACK = 0x22;
    public static final byte TOPIC_NOTIFICATION = 0x23;
    
    /**
     * Send an error response to the client
     */
    public static void sendErrorResponse(SocketChannel channel, String errorMessage) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(3 + errorMessage.length());
        buffer.put(ERROR_RESPONSE);
        buffer.putShort((short) errorMessage.length());
        buffer.put(errorMessage.getBytes());
        buffer.flip();
        channel.write(buffer);
    }
    
    /**
     * Encode a producer request
     */
    public static ByteBuffer encodeProduceRequest(String topic, int partition, byte[] message) {
        ByteBuffer buffer = ByteBuffer.allocate(11 + topic.length() + message.length);
        buffer.put(PRODUCE);
        buffer.putShort((short) topic.length());
        buffer.put(topic.getBytes());
        buffer.putInt(partition);
        buffer.putInt(message.length);
        buffer.put(message);
        buffer.flip();
        return buffer;
    }
    
    /**
     * Encode a fetch request
     */
    public static ByteBuffer encodeFetchRequest(String topic, int partition, long offset, int maxBytes) {
        ByteBuffer buffer = ByteBuffer.allocate(19 + topic.length());
        buffer.put(FETCH);
        buffer.putShort((short) topic.length());
        buffer.put(topic.getBytes());
        buffer.putInt(partition);
        buffer.putLong(offset);
        buffer.putInt(maxBytes);
        buffer.flip();
        return buffer;
    }
    
    /**
     * Encode a metadata request
     */
    public static ByteBuffer encodeMetadataRequest() {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put(METADATA);
        buffer.flip();
        return buffer;
    }
    
    /**
     * Encode a create topic request
     */
    public static ByteBuffer encodeCreateTopicRequest(String topic, int numPartitions, short replicationFactor) {
        ByteBuffer buffer = ByteBuffer.allocate(9 + topic.length());
        buffer.put(CREATE_TOPIC);
        buffer.putShort((short) topic.length());
        buffer.put(topic.getBytes());
        buffer.putInt(numPartitions);
        buffer.putShort(replicationFactor);
        buffer.flip();
        return buffer;
    }
    
    /**
     * Encode a replication request
     */
    public static ByteBuffer encodeReplicateRequest(String topic, int partition, long offset, byte[] message) {
        ByteBuffer buffer = ByteBuffer.allocate(17 + topic.length() + message.length);
        buffer.put(REPLICATE);
        buffer.putShort((short) topic.length());
        buffer.put(topic.getBytes());
        buffer.putInt(partition);
        buffer.putLong(offset);
        buffer.putInt(message.length);
        buffer.put(message);
        buffer.flip();
        return buffer;
    }
    
    /**
     * Encode a topic notification
     */
    public static ByteBuffer encodeTopicNotification(String topic) {
        ByteBuffer buffer = ByteBuffer.allocate(3 + topic.length());
        buffer.put(TOPIC_NOTIFICATION);
        buffer.putShort((short) topic.length());
        buffer.put(topic.getBytes());
        buffer.flip();
        return buffer;
    }
    
    /**
     * Decode a produce response
     */
    public static ProduceResult decodeProduceResponse(ByteBuffer buffer) {
        byte responseType = buffer.get();
        if (responseType != PRODUCE_RESPONSE) {
            if (responseType == ERROR_RESPONSE) {
                short errorLength = buffer.getShort();
                byte[] errorBytes = new byte[errorLength];
                buffer.get(errorBytes);
                String error = new String(errorBytes);
                return new ProduceResult(-1, error);
            }
            return new ProduceResult(-1, "Invalid response type");
        }
        
        long offset = buffer.getLong();
        byte status = buffer.get();
        
        return new ProduceResult(offset, status == 0 ? null : "Produce failed");
    }
    
    /**
     * Decode a fetch response
     */
    public static FetchResult decodeFetchResponse(ByteBuffer buffer) {
        byte responseType = buffer.get();
        if (responseType != FETCH_RESPONSE) {
            if (responseType == ERROR_RESPONSE) {
                short errorLength = buffer.getShort();
                byte[] errorBytes = new byte[errorLength];
                buffer.get(errorBytes);
                String error = new String(errorBytes);
                return new FetchResult(new byte[0][], error);
            }
            return new FetchResult(new byte[0][], "Invalid response type");
        }
        
        int messageCount = buffer.getInt();
        byte[][] messages = new byte[messageCount][];
        
        for (int i = 0; i < messageCount; i++) {
            long offset = buffer.getLong(); // Skip offset
            int messageSize = buffer.getInt();
            messages[i] = new byte[messageSize];
            buffer.get(messages[i]);
        }
        
        return new FetchResult(messages, null);
    }
    
    /**
     * Decode metadata response
     */
    public static MetadataResult decodeMetadataResponse(ByteBuffer buffer) {
        byte responseType = buffer.get();
        if (responseType != METADATA_RESPONSE) {
            if (responseType == ERROR_RESPONSE) {
                short errorLength = buffer.getShort();
                byte[] errorBytes = new byte[errorLength];
                buffer.get(errorBytes);
                String error = new String(errorBytes);
                return new MetadataResult(new ArrayList<>(), new ArrayList<>(), error);
            }
            return new MetadataResult(new ArrayList<>(), new ArrayList<>(), "Invalid response type");
        }
        
        // Parse broker info
        int brokerCount = buffer.getInt();
        List<BrokerInfo> brokers = new ArrayList<>();
        
        for (int i = 0; i < brokerCount; i++) {
            int brokerId = buffer.getInt();
            short hostLength = buffer.getShort();
            byte[] hostBytes = new byte[hostLength];
            buffer.get(hostBytes);
            String host = new String(hostBytes);
            int port = buffer.getInt();
            
            brokers.add(new BrokerInfo(brokerId, host, port));
        }
        
        // Parse topic metadata
        int topicCount = buffer.getInt();
        List<TopicMetadata> topics = new ArrayList<>();
        
        for (int i = 0; i < topicCount; i++) {
            short topicLength = buffer.getShort();
            byte[] topicBytes = new byte[topicLength];
            buffer.get(topicBytes);
            String topicName = new String(topicBytes);
            
            int partitionCount = buffer.getInt();
            List<PartitionMetadata> partitions = new ArrayList<>();
            
            for (int j = 0; j < partitionCount; j++) {
                int partitionId = buffer.getInt();
                int leaderId = buffer.getInt();
                
                int replicas = buffer.getInt();
                List<Integer> replicaIds = new ArrayList<>();
                
                for (int k = 0; k < replicas; k++) {
                    replicaIds.add(buffer.getInt());
                }
                
                partitions.add(new PartitionMetadata(partitionId, leaderId, replicaIds));
            }
            
            topics.add(new TopicMetadata(topicName, partitions));
        }
        
        return new MetadataResult(brokers, topics, null);
    }
    
    /**
     * Result class for produce operations
     */
    public static class ProduceResult {
        private final long offset;
        private final String error;
        
        public ProduceResult(long offset, String error) {
            this.offset = offset;
            this.error = error;
        }
        
        public long getOffset() {
            return offset;
        }
        
        public String getError() {
            return error;
        }
        
        public boolean isSuccess() {
            return error == null;
        }
    }
    
    /**
     * Result class for fetch operations
     */
    public static class FetchResult {
        private final byte[][] messages;
        private final String error;
        
        public FetchResult(byte[][] messages, String error) {
            this.messages = messages;
            this.error = error;
        }
        
        public byte[][] getMessages() {
            return messages;
        }
        
        public int getMessageCount() {
            return messages.length;
        }
        
        public String getError() {
            return error;
        }
        
        public boolean isSuccess() {
            return error == null;
        }
    }
    
    /**
     * Result class for metadata operations
     */
    public static class MetadataResult {
        private final List<BrokerInfo> brokers;
        private final List<TopicMetadata> topics;
        private final String error;
        
        public MetadataResult(List<BrokerInfo> brokers, List<TopicMetadata> topics, String error) {
            this.brokers = brokers;
            this.topics = topics;
            this.error = error;
        }
        
        public List<BrokerInfo> getBrokers() {
            return brokers;
        }
        
        public List<TopicMetadata> getTopics() {
            return topics;
        }
        
        public String getError() {
            return error;
        }
        
        public boolean isSuccess() {
            return error == null;
        }
    }
    
    /**
     * Topic metadata class
     */
    public static class TopicMetadata {
        private final String name;
        private final List<PartitionMetadata> partitions;
        
        public TopicMetadata(String name, List<PartitionMetadata> partitions) {
            this.name = name;
            this.partitions = partitions;
        }
        
        public String getName() {
            return name;
        }
        
        public List<PartitionMetadata> getPartitions() {
            return partitions;
        }
    }
    
    /**
     * Partition metadata class
     */
    public static class PartitionMetadata {
        private final int id;
        private final int leader;
        private final List<Integer> replicas;
        
        public PartitionMetadata(int id, int leader, List<Integer> replicas) {
            this.id = id;
            this.leader = leader;
            this.replicas = replicas;
        }
        
        public int getId() {
            return id;
        }
        
        public int getLeader() {
            return leader;
        }
        
        public List<Integer> getReplicas() {
            return replicas;
        }
    }
}