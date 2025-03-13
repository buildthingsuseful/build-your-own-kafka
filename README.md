Here's a markdown version of the page:

```markdown
# Building Your Own Kafka-like System From Scratch: A Step-by-Step Guide

If you're looking to truly understand Kafka's architecture by implementing a simplified version yourself, you've come to the right place. Rather than just copying code, we'll build SimpleKafka incrementally, understanding each component as we go. This approach will give you a deep understanding of distributed messaging systems.

## Workflow Summary
This incremental staged approach allows you to build and understand each component of a Kafka-like system starting from stage 1 to stage 7 in detail:

1. Set up the project structure
2. Create the core protocol layer
3. Implementing Zookeeper Integration
4. Building the storage layer
5. Build the broker
6. Develop the client library
7. Building higher level producer and consumer APIs
8. Test the system

By building each component yourself, you'll gain a deep understanding of Kafka's architecture and the design decisions behind it. This knowledge will be invaluable when working with the real Kafka or designing your own distributed systems.

## Testing the System

### 1. Compile the project using Maven:
```shell
mvn clean package
```

### 2. Start ZooKeeper
```shell
# Start ZooKeeper with default configuration
zkServer start

# If that doesn't work, try:
zookeeper-server-start /usr/local/etc/kafka/zookeeper.properties

# Or create a simple config file:
echo "tickTime=2000" > zk.cfg
echo "dataDir=/tmp/zookeeper" >> zk.cfg
echo "clientPort=2181" >> zk.cfg
zookeeper-server-start zk.cfg
```

### 3. Start Multiple Broker Instances
```shell
# Terminal 1 - Broker 1
java -cp target/simple-kafka-1.0-SNAPSHOT.jar com.simplekafka.broker.SimpleKafkaBroker 1 localhost 9091 2181

# Terminal 2 - Broker 2
java -cp target/simple-kafka-1.0-SNAPSHOT.jar com.simplekafka.broker.SimpleKafkaBroker 2 localhost 9092 2181

# Terminal 3 - Broker 3
java -cp target/simple-kafka-1.0-SNAPSHOT.jar com.simplekafka.broker.SimpleKafkaBroker 3 localhost 9093 2181
```

### 4. Produce Messages
```shell
java -cp target/simple-kafka-1.0-SNAPSHOT.jar com.simplekafka.client.SimpleKafkaProducer localhost 9091 test-topic
```

### 5. Consume Messages
```shell
java -cp target/simple-kafka-1.0-SNAPSHOT.jar com.simplekafka.client.SimpleKafkaConsumer localhost 9091 test-topic 0
```

### Key Concepts to Focus On During Testing

#### 1. Topic Partitioning and Replication
Watch how a topic gets divided into partitions and how those partitions are distributed across brokers.

#### 2. Leader and Follower Mechanics
- How one broker becomes the leader
- How followers replicate data from the leader
- What happens when a leader fails and a new leader is elected

#### 3. The Controller Role
- The controller is elected through ZooKeeper
- It manages partition assignments
- It handles broker failures
- A new controller is elected if the current one fails

#### 4. Message Persistence
- The log segment structure
- How messages are appended sequentially
- How indices map offsets to file positions

#### 5. Client-Broker Protocol
- The binary protocol format
- Request/response patterns
- How clients discover and connect to the right brokers
```