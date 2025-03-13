package com.simplekafka.broker;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simplified implementation of a Kafka-like broker
 */
public class SimpleKafkaBroker {
    private static final Logger LOGGER = Logger.getLogger(SimpleKafkaBroker.class.getName());
    private static final String DATA_DIR = "data";

    private final int brokerId;
    private final String brokerHost;
    private final int brokerPort;
    private final Map<String, List<Partition>> topics;
    private final ExecutorService executor;
    private final ServerSocketChannel serverChannel;
    private final AtomicBoolean isRunning;
    private final AtomicBoolean isController;
    private final Map<Integer, BrokerInfo> clusterMetadata;
    private final ZookeeperClient zkClient;

    public SimpleKafkaBroker(int brokerId, String host, int port, int zkPort) throws IOException {
        this.brokerId = brokerId;
        this.brokerHost = host;
        this.brokerPort = port;
        this.topics = new ConcurrentHashMap<>();
        this.executor = Executors.newFixedThreadPool(10);
        this.serverChannel = ServerSocketChannel.open();
        this.isRunning = new AtomicBoolean(false);
        this.isController = new AtomicBoolean(false);
        this.clusterMetadata = new ConcurrentHashMap<>();

        // Initialize data directory
        File dataDir = new File(DATA_DIR + File.separator + brokerId);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        // Initialize ZooKeeper client
        this.zkClient = new ZookeeperClient("localhost", zkPort);
    }

    /**
     * Notify a broker about topic creation
     */
    private void notifyBrokerForTopicCreation(int brokerId, String topic) {
        BrokerInfo broker = clusterMetadata.get(brokerId);
        if (broker == null)
            return;

        executor.submit(() -> {
            try (SocketChannel brokerChannel = SocketChannel.open()) {
                brokerChannel.connect(new InetSocketAddress(broker.getHost(), broker.getPort()));

                // Prepare notification
                ByteBuffer request = ByteBuffer.allocate(3 + topic.length());
                request.put(Protocol.TOPIC_NOTIFICATION);
                request.putShort((short) topic.length());
                request.put(topic.getBytes());
                request.flip();

                // Send notification
                brokerChannel.write(request);

                // Read acknowledgment
                ByteBuffer response = ByteBuffer.allocate(1);
                brokerChannel.read(response);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to notify broker " + brokerId + " about topic creation", e);
            }
        });
    }

    /**
     * Handle topic notification from controller
     */
    private void handleTopicNotification(SocketChannel clientChannel, ByteBuffer buffer) throws IOException {
        short topicLength = buffer.getShort();
        byte[] topicBytes = new byte[topicLength];
        buffer.get(topicBytes);
        String topic = new String(topicBytes);

        LOGGER.info("Received topic notification for: " + topic);

        // Load topic metadata from ZooKeeper
        try {
            loadTopic(topic);

            // Send acknowledgment
            ByteBuffer response = ByteBuffer.allocate(1);
            response.put((byte) 0); // Acknowledgment
            response.flip();
            clientChannel.write(response);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load topic: " + topic, e);

            // Send error response
            ByteBuffer response = ByteBuffer.allocate(1);
            response.put((byte) 1); // Error
            response.flip();
            clientChannel.write(response);
        }
    }

    /**
     * Load topic metadata from ZooKeeper
     */
    private void loadTopic(String topic) throws Exception {
        if (topics.containsKey(topic)) {
            LOGGER.info("Topic already loaded: " + topic);
            return;
        }

        String topicPath = "/topics/" + topic;
        if (!zkClient.exists(topicPath)) {
            throw new Exception("Topic does not exist in ZooKeeper: " + topic);
        }

        String topicDir = DATA_DIR + File.separator + brokerId + File.separator + topic;
        new File(topicDir).mkdirs();

        List<String> partitionIds = zkClient.getChildren(topicPath + "/partitions");
        List<Partition> partitions = new ArrayList<>();

        for (String partitionId : partitionIds) {
            int id = Integer.parseInt(partitionId);
            String partitionPath = topicPath + "/partitions/" + partitionId;
            String partitionData = zkClient.getData(partitionPath);

            String[] parts = partitionData.split(";");
            int leader = Integer.parseInt(parts[0]);

            List<Integer> followers = new ArrayList<>();
            if (parts.length > 1 && !parts[1].isEmpty()) {
                String[] followerIds = parts[1].split(",");
                for (String followerId : followerIds) {
                    if (!followerId.isEmpty()) {
                        followers.add(Integer.parseInt(followerId));
                    }
                }
            }

            String partitionDir = topicDir + File.separator + id;
            new File(partitionDir).mkdirs();

            Partition partition = new Partition(id, leader, followers, partitionDir);
            partitions.add(partition);

            LOGGER.info("Loaded partition " + id + " for topic " + topic +
                    ", leader: " + leader + ", followers: " + followers);
        }

        topics.put(topic, partitions);
        LOGGER.info("Successfully loaded topic: " + topic + " with " + partitions.size() + " partitions");
    }

    /**
     * Load all topics from ZooKeeper
     */
    public void loadTopics() {
        try {
            List<String> topicNames = zkClient.getChildren("/topics");

            for (String topic : topicNames) {
                try {
                    loadTopic(topic);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Failed to load topic: " + topic, e);
                }
            }

            LOGGER.info("Loaded " + topics.size() + " topics");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load topics", e);
        }
    }

    /**
     * Main entry point for running a broker
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: SimpleKafkaBroker <brokerId> <host> <port> [zkPort]");
            System.exit(1);
        }

        try {
            int brokerId = Integer.parseInt(args[0]);
            String host = args[1];
            int port = Integer.parseInt(args[2]);
            int zkPort = args.length > 3 ? Integer.parseInt(args[3]) : 2181;

            SimpleKafkaBroker broker = new SimpleKafkaBroker(brokerId, host, port, zkPort);
            broker.start();

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(broker::stop));

            System.out.println("SimpleKafka broker started. Press Ctrl+C to stop.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to start broker", e);
        }
    }

    /**
     * Start the broker server
     */
    public void start() throws IOException {
        if (isRunning.compareAndSet(false, true)) {
            // Bind to socket
            serverChannel.socket().bind(new InetSocketAddress(brokerHost, brokerPort));
            serverChannel.configureBlocking(false);

            LOGGER.info("SimpleKafka broker started on " + brokerHost + ":" + brokerPort);

            // Register with ZooKeeper
            registerWithZookeeper();

            // Start controller election process
            electController();

            // Load existing topics
            loadTopics();

            // Accept client connections
            executor.submit(this::acceptConnections);
        }
    }

    /**
     * Stop the broker server
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                LOGGER.info("Stopping SimpleKafka broker...");

                // Close server socket
                serverChannel.close();

                // Close all topic partitions
                for (List<Partition> partitions : topics.values()) {
                    for (Partition partition : partitions) {
                        partition.close();
                    }
                }

                // Shut down executor
                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.SECONDS);

                // Close ZooKeeper connection
                zkClient.close();

                LOGGER.info("SimpleKafka broker stopped");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error stopping broker", e);
            }
        }
    }

    /**
     * Register this broker with ZooKeeper
     */
    private void registerWithZookeeper() {
        try {
            zkClient.connect();
            String brokerPath = "/brokers/" + brokerId;
            String brokerData = brokerHost + ":" + brokerPort;
            zkClient.createEphemeralNode(brokerPath, brokerData);

            // Add broker info to local metadata
            BrokerInfo selfInfo = new BrokerInfo(brokerId, brokerHost, brokerPort);
            clusterMetadata.put(brokerId, selfInfo);

            // Watch for other brokers
            zkClient.watchChildren("/brokers", this::onBrokersChanged);

            LOGGER.info("Registered with ZooKeeper at " + zkClient.getConnectString());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to register with ZooKeeper", e);
        }
    }

    /**
     * Handle changes in the broker list from ZooKeeper
     */
    private void onBrokersChanged(List<String> brokerIds) {
        LOGGER.info("Broker change detected. Current brokers: " + brokerIds);

        // Update cluster metadata
        for (String id : brokerIds) {
            try {
                int brokerId = Integer.parseInt(id);
                if (!clusterMetadata.containsKey(brokerId)) {
                    String brokerData = zkClient.getData("/brokers/" + id);
                    String[] hostPort = brokerData.split(":");
                    BrokerInfo info = new BrokerInfo(
                            brokerId,
                            hostPort[0],
                            Integer.parseInt(hostPort[1]));
                    clusterMetadata.put(brokerId, info);
                    LOGGER.info("Added broker: " + info);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to process broker info", e);
            }
        }

        // Remove brokers that have disappeared
        List<Integer> toRemove = new ArrayList<>();
        for (Integer brokerId : clusterMetadata.keySet()) {
            if (!brokerIds.contains(String.valueOf(brokerId))) {
                toRemove.add(brokerId);
            }
        }

        for (Integer brokerId : toRemove) {
            clusterMetadata.remove(brokerId);
            LOGGER.info("Removed broker: " + brokerId);
        }

        // Re-elect controller if needed
        if (!brokerIds.contains(String.valueOf(brokerId)) && isController.get()) {
            isController.set(false);
            LOGGER.info("This broker is no longer in the cluster, giving up controller status");
        } else if (isController.get()) {
            // As controller, rebalance partitions due to cluster changes
            rebalancePartitions();
        } else {
            // Re-attempt controller election
            electController();
        }
    }

    /**
     * Participate in controller election
     */
    private void electController() {
        try {
            String controllerPath = "/controller";
            
            // First, make sure the node doesn't already exist (or is empty)
            boolean nodeExists = zkClient.exists(controllerPath);
            if (nodeExists) {
                String existingData = zkClient.getData(controllerPath);
                if (existingData == null || existingData.trim().isEmpty()) {
                    // Node exists but has empty data, try to delete it
                    zkClient.deleteNode(controllerPath);
                    nodeExists = false;
                    LOGGER.info("Deleted empty controller node");
                }
            }
            
            // Now try to create the node
            boolean becameController = false;
            if (!nodeExists) {
                becameController = zkClient.createEphemeralNode(controllerPath, String.valueOf(brokerId));
            }
            
            if (becameController) {
                isController.set(true);
                LOGGER.info("This broker is now the active controller");
                
                // As controller, ensure all topics are properly replicated
                rebalancePartitions();
            } else {
                // Double-check the data
                String controllerId = zkClient.getData(controllerPath);
                if (controllerId == null || controllerId.trim().isEmpty()) {
                    LOGGER.warning("Controller node exists but has no data. This is unexpected.");
                    // Try again after a delay
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                            electController();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                    return;
                }
                
                LOGGER.info("Current controller is broker " + controllerId);
                
                // Watch controller node for changes
                zkClient.watchNode(controllerPath, this::onControllerChange);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Controller election failed", e);
            
            // Try again after a delay
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    electController();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    /**
     * Handle controller change notification
     */
    private void onControllerChange() {
        LOGGER.info("Controller changed, initiating new election");
        electController();
    }

    /**
     * Rebalance partitions across available brokers
     */
    private void rebalancePartitions() {
        if (!isController.get()) {
            return;
        }

        LOGGER.info("Rebalancing partitions across cluster");

        for (Map.Entry<String, List<Partition>> entry : topics.entrySet()) {
            String topic = entry.getKey();
            List<Partition> partitions = entry.getValue();

            for (Partition partition : partitions) {
                // Ensure each partition has a leader
                if (partition.getLeader() == -1 || !clusterMetadata.containsKey(partition.getLeader())) {
                    // Assign a new leader
                    List<Integer> brokers = new ArrayList<>(clusterMetadata.keySet());
                    if (!brokers.isEmpty()) {
                        int newLeader = brokers.get(0);
                        partition.setLeader(newLeader);

                        // Set other brokers as followers
                        List<Integer> followers = new ArrayList<>();
                        for (int i = 1; i < Math.min(brokers.size(), 3); i++) {
                            followers.add(brokers.get(i));
                        }
                        partition.setFollowers(followers);

                        // Update partition metadata in ZooKeeper
                        updatePartitionMetadata(topic, partition);

                        LOGGER.info("Reassigned partition " + partition.getId() +
                                " of topic " + topic +
                                " to leader " + newLeader +
                                " with followers " + followers);
                    }
                }
            }
        }
    }

    /**
     * Update partition metadata in ZooKeeper
     */
    private void updatePartitionMetadata(String topic, Partition partition) {
        try {
            String path = "/topics/" + topic + "/partitions/" + partition.getId();
            String data = partition.getLeader() + ";";
            for (int follower : partition.getFollowers()) {
                data += follower + ",";
            }

            if (zkClient.exists(path)) {
                zkClient.setData(path, data);
            } else {
                zkClient.createPersistentNode(path, data);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to update partition metadata", e);
        }
    }

    /**
     * Accept client connections
     */
    private void acceptConnections() {
        while (isRunning.get()) {
            try {
                SocketChannel clientChannel = serverChannel.accept();
                if (clientChannel != null) {
                    clientChannel.configureBlocking(false);
                    LOGGER.info("Accepted connection from " + clientChannel.getRemoteAddress());

                    // Handle client connection in a separate thread
                    executor.submit(() -> handleClient(clientChannel));
                }

                Thread.sleep(100); // Small pause to prevent CPU spin
            } catch (Exception e) {
                if (isRunning.get()) {
                    LOGGER.log(Level.SEVERE, "Error accepting connection", e);
                }
            }
        }
    }

    /**
     * Handle client connection
     */
    private void handleClient(SocketChannel clientChannel) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(1024);

            while (clientChannel.isOpen() && isRunning.get()) {
                buffer.clear();
                int bytesRead = clientChannel.read(buffer);

                if (bytesRead > 0) {
                    buffer.flip();
                    // Process the message based on protocol
                    processClientMessage(clientChannel, buffer);
                } else if (bytesRead < 0) {
                    // Connection closed by client
                    clientChannel.close();
                    break;
                }

                Thread.sleep(50); // Small pause to prevent CPU spin
            }
        } catch (Exception e) {
            if (isRunning.get()) {
                LOGGER.log(Level.SEVERE, "Error handling client", e);
            }
        } finally {
            try {
                if (clientChannel.isOpen()) {
                    clientChannel.close();
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing client channel", e);
            }
        }
    }

    /**
     * Process client message based on SimpleKafka wire protocol
     */
    private void processClientMessage(SocketChannel clientChannel, ByteBuffer buffer) throws IOException {
        byte messageType = buffer.get();

        switch (messageType) {
            case Protocol.PRODUCE:
                handleProduceRequest(clientChannel, buffer);
                break;
            case Protocol.FETCH:
                handleFetchRequest(clientChannel, buffer);
                break;
            case Protocol.METADATA:
                handleMetadataRequest(clientChannel, buffer);
                break;
            case Protocol.CREATE_TOPIC:
                handleCreateTopicRequest(clientChannel, buffer);
                break;
            case Protocol.REPLICATE:
                handleReplicateRequest(clientChannel, buffer);
                break;
            case Protocol.TOPIC_NOTIFICATION:
                handleTopicNotification(clientChannel, buffer);
                break;
            default:
                LOGGER.warning("Unknown message type: " + messageType);
                Protocol.sendErrorResponse(clientChannel, "Unknown message type");
        }
    }

    /**
     * Handle produce request from client
     */
    private void handleProduceRequest(SocketChannel clientChannel, ByteBuffer buffer) throws IOException {
        short topicLength = buffer.getShort();
        byte[] topicBytes = new byte[topicLength];
        buffer.get(topicBytes);
        String topic = new String(topicBytes);

        int partition = buffer.getInt();
        int messageSize = buffer.getInt();
        byte[] message = new byte[messageSize];
        buffer.get(message);

        LOGGER.info("Produce request for topic: " + topic + ", partition: " + partition);

        // Check if topic exists
        if (!topics.containsKey(topic)) {
            Protocol.sendErrorResponse(clientChannel, "Topic does not exist");
            return;
        }

        // Find the partition
        List<Partition> partitions = topics.get(topic);
        Partition targetPartition = null;

        for (Partition p : partitions) {
            if (p.getId() == partition) {
                targetPartition = p;
                break;
            }
        }

        if (targetPartition == null) {
            Protocol.sendErrorResponse(clientChannel, "Partition does not exist");
            return;
        }

        // Check if this broker is the leader for the partition
        if (targetPartition.getLeader() != brokerId) {
            // Forward to leader
            forwardProduceToLeader(clientChannel, topic, partition, message, targetPartition.getLeader());
            return;
        }

        // Append message to log
        long offset = targetPartition.append(message);

        // Replicate to followers
        replicateToFollowers(topic, targetPartition, message, offset);

        // Send acknowledgment to client
        ByteBuffer response = ByteBuffer.allocate(10);
        response.put(Protocol.PRODUCE_RESPONSE);
        response.putLong(offset);
        response.put((byte) (offset > -1 ? 0 : 1)); // 0 = success, 1 = error
        response.flip();
        clientChannel.write(response);
    }

    /**
     * Forward produce request to leader broker
     */
    private void forwardProduceToLeader(SocketChannel clientChannel, String topic, int partition,
            byte[] message, int leaderId) throws IOException {
        BrokerInfo leader = clusterMetadata.get(leaderId);
        if (leader == null) {
            Protocol.sendErrorResponse(clientChannel, "Leader broker not available");
            return;
        }

        try (SocketChannel leaderChannel = SocketChannel.open()) {
            leaderChannel.connect(new InetSocketAddress(leader.getHost(), leader.getPort()));

            // Prepare forwarded produce request
            ByteBuffer request = ByteBuffer.allocate(9 + topic.length() + message.length);
            request.put(Protocol.PRODUCE);
            request.putShort((short) topic.length());
            request.put(topic.getBytes());
            request.putInt(partition);
            request.putInt(message.length);
            request.put(message);
            request.flip();

            // Send request to leader
            leaderChannel.write(request);

            // Read response from leader
            ByteBuffer response = ByteBuffer.allocate(10);
            leaderChannel.read(response);
            response.flip();

            // Forward leader's response back to client
            clientChannel.write(response);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to forward produce request to leader", e);
            Protocol.sendErrorResponse(clientChannel, "Failed to forward to leader");
        }
    }

    /**
     * Replicate message to follower brokers
     */
    private void replicateToFollowers(String topic, Partition partition, byte[] message, long offset) {
        for (int followerId : partition.getFollowers()) {
            if (followerId == brokerId)
                continue; // Skip self

            BrokerInfo follower = clusterMetadata.get(followerId);
            if (follower == null)
                continue;

            executor.submit(() -> {
                try (SocketChannel followerChannel = SocketChannel.open()) {
                    followerChannel.connect(new InetSocketAddress(follower.getHost(), follower.getPort()));

                    // Prepare replication request
                    ByteBuffer request = ByteBuffer.allocate(17 + topic.length() + message.length);
                    request.put(Protocol.REPLICATE);
                    request.putShort((short) topic.length());
                    request.put(topic.getBytes());
                    request.putInt(partition.getId());
                    request.putLong(offset);
                    request.putInt(message.length);
                    request.put(message);
                    request.flip();

                    // Send request to follower
                    followerChannel.write(request);

                    // Read acknowledgment
                    ByteBuffer response = ByteBuffer.allocate(1);
                    followerChannel.read(response);
                    response.flip();

                    byte ack = response.get();
                    LOGGER.info("Replication to follower " + followerId + " " +
                            (ack == Protocol.REPLICATE_ACK ? "succeeded" : "failed"));
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Replication to follower " + followerId + " failed", e);
                }
            });
        }
    }

    /**
     * Handle replication request from leader
     */
    private void handleReplicateRequest(SocketChannel clientChannel, ByteBuffer buffer) throws IOException {
        short topicLength = buffer.getShort();
        byte[] topicBytes = new byte[topicLength];
        buffer.get(topicBytes);
        String topic = new String(topicBytes);

        int partitionId = buffer.getInt();
        long offset = buffer.getLong();
        int messageSize = buffer.getInt();
        byte[] message = new byte[messageSize];
        buffer.get(message);

        LOGGER.info("Replication request for topic: " + topic + ", partition: " + partitionId + ", offset: " + offset);

        // Check if topic exists
        if (!topics.containsKey(topic)) {
            ByteBuffer response = ByteBuffer.allocate(1);
            response.put((byte) 0); // Failed
            response.flip();
            clientChannel.write(response);
            return;
        }

        // Find the partition
        List<Partition> partitions = topics.get(topic);
        Partition targetPartition = null;

        for (Partition p : partitions) {
            if (p.getId() == partitionId) {
                targetPartition = p;
                break;
            }
        }

        if (targetPartition == null) {
            ByteBuffer response = ByteBuffer.allocate(1);
            response.put((byte) 0); // Failed
            response.flip();
            clientChannel.write(response);
            return;
        }

        // Append message to log (as follower)
        long appendedOffset = targetPartition.append(message);

        // Send acknowledgment
        ByteBuffer response = ByteBuffer.allocate(1);
        response.put(Protocol.REPLICATE_ACK);
        response.flip();
        clientChannel.write(response);
    }

    /**
     * Handle fetch request from client
     */
    private void handleFetchRequest(SocketChannel clientChannel, ByteBuffer buffer) throws IOException {
        short topicLength = buffer.getShort();
        byte[] topicBytes = new byte[topicLength];
        buffer.get(topicBytes);
        String topic = new String(topicBytes);

        int partition = buffer.getInt();
        long offset = buffer.getLong();
        int maxBytes = buffer.getInt();

        LOGGER.info("Fetch request for topic: " + topic + ", partition: " + partition +
                ", offset: " + offset + ", maxBytes: " + maxBytes);

        // Check if topic exists
        if (!topics.containsKey(topic)) {
            Protocol.sendErrorResponse(clientChannel, "Topic does not exist");
            return;
        }

        // Find the partition
        List<Partition> partitions = topics.get(topic);
        Partition targetPartition = null;

        for (Partition p : partitions) {
            if (p.getId() == partition) {
                targetPartition = p;
                break;
            }
        }

        if (targetPartition == null) {
            Protocol.sendErrorResponse(clientChannel, "Partition does not exist");
            return;
        }

        // Check if the offset is valid
        if (offset >= targetPartition.getLogEndOffset()) {
            // No messages available at this offset
            ByteBuffer response = ByteBuffer.allocate(5);
            response.put(Protocol.FETCH_RESPONSE);
            response.putInt(0); // 0 messages
            response.flip();
            clientChannel.write(response);
            return;
        }

        // Read messages from log
        List<byte[]> messages = targetPartition.readMessages(offset, maxBytes);

        // Send response
        int totalSize = 5; // 1 byte for response type, 4 bytes for message count
        for (byte[] msg : messages) {
            totalSize += 12 + msg.length; // 8 bytes for offset, 4 bytes for length, plus message bytes
        }

        ByteBuffer response = ByteBuffer.allocate(totalSize);
        response.put(Protocol.FETCH_RESPONSE);
        response.putInt(messages.size());

        long currentOffset = offset;
        for (byte[] msg : messages) {
            response.putLong(currentOffset);
            response.putInt(msg.length);
            response.put(msg);
            currentOffset++;
        }

        response.flip();
        clientChannel.write(response);
    }

    /**
     * Handle metadata request from client
     */
    private void handleMetadataRequest(SocketChannel clientChannel, ByteBuffer buffer) throws IOException {
        // Prepare response with metadata
        int size = 5; // 1 byte for response type, 4 bytes for topic count

        // Calculate size for topics metadata
        for (Map.Entry<String, List<Partition>> entry : topics.entrySet()) {
            size += 6 + entry.getKey().length(); // 2 bytes for length, string, 4 bytes for partition count

            // Add size for each partition
            size += entry.getValue().size() * 12; // 4 bytes for id, 4 bytes for leader, 4 bytes for follower count

            // Add size for followers
            for (Partition partition : entry.getValue()) {
                size += partition.getFollowers().size() * 4; // 4 bytes per follower ID
            }
        }

        // Add size for brokers metadata
        size += 4; // 4 bytes for broker count
        size += clusterMetadata.size() * 10; // 4 bytes for id, 2 bytes for host length, 4 bytes for port

        // Add estimated size for broker hostnames
        for (BrokerInfo broker : clusterMetadata.values()) {
            size += broker.getHost().length();
        }

        ByteBuffer response = ByteBuffer.allocate(size);
        response.put(Protocol.METADATA_RESPONSE);

        // Add broker metadata
        response.putInt(clusterMetadata.size());
        for (BrokerInfo broker : clusterMetadata.values()) {
            response.putInt(broker.getId());
            response.putShort((short) broker.getHost().length());
            response.put(broker.getHost().getBytes());
            response.putInt(broker.getPort());
        }

        // Add topic metadata
        response.putInt(topics.size());
        for (Map.Entry<String, List<Partition>> entry : topics.entrySet()) {
            String topic = entry.getKey();
            List<Partition> partitions = entry.getValue();

            response.putShort((short) topic.length());
            response.put(topic.getBytes());
            response.putInt(partitions.size());

            for (Partition partition : partitions) {
                response.putInt(partition.getId());
                response.putInt(partition.getLeader());

                List<Integer> followers = partition.getFollowers();
                response.putInt(followers.size());
                for (Integer follower : followers) {
                    response.putInt(follower);
                }
            }
        }

        response.flip();
        clientChannel.write(response);
    }

    /**
     * Handle create topic request from client
     */
    private void handleCreateTopicRequest(SocketChannel clientChannel, ByteBuffer buffer) throws IOException {
        short topicLength = buffer.getShort();
        byte[] topicBytes = new byte[topicLength];
        buffer.get(topicBytes);
        String topic = new String(topicBytes);

        int numPartitions = buffer.getInt();
        short replicationFactor = buffer.getShort();

        LOGGER.info("Create topic request: " + topic +
                ", partitions: " + numPartitions +
                ", replication: " + replicationFactor);

        // Check if topic already exists
        if (topics.containsKey(topic)) {
            Protocol.sendErrorResponse(clientChannel, "Topic already exists");
            return;
        }

        // Validate parameters
        if (numPartitions <= 0 || replicationFactor <= 0 ||
                replicationFactor > clusterMetadata.size()) {
            Protocol.sendErrorResponse(clientChannel, "Invalid topic configuration");
            return;
        }

        // As controller, create the topic
        if (isController.get()) {
            createTopic(topic, numPartitions, replicationFactor);

            // Send success response
            ByteBuffer response = ByteBuffer.allocate(2);
            response.put(Protocol.CREATE_TOPIC_RESPONSE);
            response.put((byte) 0); // 0 = success
            response.flip();
            clientChannel.write(response);
        } else {
            // Forward to controller
            forwardCreateTopicToController(clientChannel, topic, numPartitions, replicationFactor);
        }
    }

    /**
     * Forward create topic request to controller
     */
    private void forwardCreateTopicToController(SocketChannel clientChannel, String topic,
            int numPartitions, short replicationFactor) throws IOException {
        // Find controller
        int controllerId = -1;
        try {
            String controllerData = zkClient.getData("/controller");
            controllerId = Integer.parseInt(controllerData);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to get controller info", e);
            Protocol.sendErrorResponse(clientChannel, "Controller not available");
            return;
        }

        BrokerInfo controller = clusterMetadata.get(controllerId);
        if (controller == null) {
            Protocol.sendErrorResponse(clientChannel, "Controller broker not available");
            return;
        }

        try (SocketChannel controllerChannel = SocketChannel.open()) {
            controllerChannel.connect(new InetSocketAddress(controller.getHost(), controller.getPort()));

            // Prepare forwarded create topic request
            ByteBuffer request = ByteBuffer.allocate(9 + topic.length());
            request.put(Protocol.CREATE_TOPIC);
            request.putShort((short) topic.length());
            request.put(topic.getBytes());
            request.putInt(numPartitions);
            request.putShort(replicationFactor);
            request.flip();

            // Send request to controller
            controllerChannel.write(request);

            // Read response from controller
            ByteBuffer response = ByteBuffer.allocate(2);
            controllerChannel.read(response);
            response.flip();

            // Forward controller's response back to client
            clientChannel.write(response);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to forward create topic request to controller", e);
            Protocol.sendErrorResponse(clientChannel, "Failed to forward to controller");
        }
    }

    /**
     * Create a new topic with the specified configuration
     */
    private void createTopic(String topic, int numPartitions, short replicationFactor) {
        if (!isController.get()) {
            LOGGER.warning("Only the controller can create topics");
            return;
        }

        try {
            // Create topic directory
            String topicDir = DATA_DIR + File.separator + brokerId + File.separator + topic;
            new File(topicDir).mkdirs();

            // Create topic in ZooKeeper
            String topicPath = "/topics/" + topic;
            if (!zkClient.exists(topicPath)) {
                zkClient.createPersistentNode(topicPath, "");
                zkClient.createPersistentNode(topicPath + "/partitions", "");
            }

            // Create partitions
            List<Partition> partitions = new ArrayList<>();
            List<Integer> brokerIds = new ArrayList<>(clusterMetadata.keySet());

            for (int i = 0; i < numPartitions; i++) {
                int partitionId = i;
                String partitionDir = topicDir + File.separator + partitionId;
                new File(partitionDir).mkdirs();

                // Select leader and followers
                int leaderIndex = i % brokerIds.size();
                int leaderId = brokerIds.get(leaderIndex);

                List<Integer> followers = new ArrayList<>();
                for (int j = 1; j < replicationFactor; j++) {
                    int followerIndex = (leaderIndex + j) % brokerIds.size();
                    followers.add(brokerIds.get(followerIndex));
                }

                // Create partition
                Partition partition = new Partition(partitionId, leaderId, followers, partitionDir);
                partitions.add(partition);

                // Store partition metadata in ZooKeeper
                String partitionPath = topicPath + "/partitions/" + partitionId;
                String partitionData = leaderId + ";";
                for (int follower : followers) {
                    partitionData += follower + ",";
                }

                zkClient.createPersistentNode(partitionPath, partitionData);

                LOGGER.info("Created partition " + partitionId +
                        " for topic " + topic +
                        " with leader " + leaderId +
                        " and followers " + followers);
            }

            // Add topic to broker's metadata
            topics.put(topic, partitions);

            // Notify all brokers to load the topic
            for (int brokerId : brokerIds) {
                if (brokerId != this.brokerId) {
                    notifyBrokerForTopicCreation(brokerId, topic);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to create topic", e);
        }
    }
}