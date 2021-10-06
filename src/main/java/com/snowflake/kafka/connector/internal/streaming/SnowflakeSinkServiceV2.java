package com.snowflake.kafka.connector.internal.streaming;

import com.codahale.metrics.MetricRegistry;
import com.google.common.annotations.VisibleForTesting;
import com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig;
import com.snowflake.kafka.connector.Utils;
import com.snowflake.kafka.connector.internal.*;
import com.snowflake.kafka.connector.records.RecordService;
import com.snowflake.kafka.connector.records.SnowflakeMetadataConfig;
import java.util.*;
import java.util.concurrent.ExecutionException;
import net.snowflake.ingest.streaming.OpenChannelRequest;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestChannel;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestClient;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestClientFactory;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnowflakeSinkServiceV2 implements SnowflakeSinkService {

  private static final Logger LOGGER = LoggerFactory.getLogger(SnowflakeSinkServiceV2.class);

  private static final long ONE_HOUR = 60 * 60 * 1000L;
  private static final long TEN_MINUTES = 10 * 60 * 1000L;
  protected static final long CLEAN_TIME = 60 * 1000L; // one minutes

  // Assume next three values are a threshold after which we will call insertRows API
  // Set in config (Time based flush) in seconds
  private long flushTime;
  // Set in config (buffer size based flush) in bytes
  private long fileSize;

  // Set in config (Threshold before we call insertRows API) corresponds to # of
  // records in kafka
  private long recordNum;
  private final SnowflakeConnectionService conn;

  private final RecordService recordService;
  private boolean isStopped;
  private final SnowflakeTelemetryService telemetryService;
  private Map<String, String> topic2TableMap;

  // Behavior to be set at the start of connector start. (For tombstone records)
  private SnowflakeSinkConnectorConfig.BehaviorOnNullValues behaviorOnNullValues;

  // default is true unless the configuration provided is false;
  // If this is true, we will enable Mbean for required classes and emit JMX metrics for monitoring
  private boolean enableCustomJMXMonitoring = SnowflakeSinkConnectorConfig.JMX_OPT_DEFAULT;

  // ------ Streaming Ingest ------ //
  // needs url, username. p8 key, role name
  private final SnowflakeStreamingIngestClient streamingIngestClient;

  private final Map<String, String> connectorConfig;

  private final String taskId;

  private final String streamingIngestClientName;

  /**
   * Key if formulated in {@link #getNameIndex(String, int)} } value is the Streaming Ingest Channel
   * implementation
   */
  private final Map<String, TopicPartitionChannel> partitionsToChannel;

  public SnowflakeSinkServiceV2(
      SnowflakeConnectionService conn, Map<String, String> connectorConfig) {
    if (conn == null || conn.isClosed()) {
      throw SnowflakeErrors.ERROR_5010.getException();
    }

    this.fileSize = SnowflakeSinkConnectorConfig.BUFFER_SIZE_BYTES_DEFAULT;
    this.recordNum = SnowflakeSinkConnectorConfig.BUFFER_COUNT_RECORDS_DEFAULT;
    this.flushTime = SnowflakeSinkConnectorConfig.BUFFER_FLUSH_TIME_SEC_DEFAULT;
    this.conn = conn;
    this.recordService = new RecordService();
    isStopped = false;
    this.telemetryService = conn.getTelemetryClient();
    this.topic2TableMap = new HashMap<>();

    // Setting the default value in constructor
    // meaning it will not ignore the null values (Tombstone records wont be ignored/filtered)
    this.behaviorOnNullValues = SnowflakeSinkConnectorConfig.BehaviorOnNullValues.DEFAULT;

    this.connectorConfig = connectorConfig;
    // Streaming ingest requires a properties.
    // TODO request a builder pattern for SI Client.
    Properties streamingClientProps = new Properties();
    streamingClientProps.putAll(connectorConfig);
    this.taskId = connectorConfig.getOrDefault(Utils.TASK_ID, "-1");
    this.streamingIngestClientName = "KC_CLIENT_" + conn.getConnectorName() + "_" + taskId;
    this.streamingIngestClient =
        SnowflakeStreamingIngestClientFactory.builder(this.streamingIngestClientName)
            .setProperties(streamingClientProps)
            .build();
    this.partitionsToChannel = new HashMap<>();
  }

  @Override
  public void startTask(String tableName, String topic, int partition) {
    String nameIndex = getNameIndex(topic, partition);
    LOGGER.info(
        "Opening a channel with name:{} for table name:{}, topic:{}, partition:{}",
        nameIndex,
        tableName,
        topic,
        partition);
    // the table should be present before opening a channel so lets do a table existence check here
    createTableIfNotExists(tableName);
    SnowflakeStreamingIngestChannel partitionChannel =
        streamingIngestClient.openChannel(getOpenChannelRequest(nameIndex, tableName));
    TopicPartitionChannel topicPartitionChannel =
        new TopicPartitionChannel(partitionChannel, this.conn, tableName);
    partitionsToChannel.putIfAbsent(nameIndex, topicPartitionChannel);
  }

  @Override
  public void insert(Collection<SinkRecord> records) {
    Map<String, List<SinkRecord>> topicPartitionsToRecords = new HashMap<>();
    for (SinkRecord record : records) {
      // check if need to handle null value records
      if (recordService.shouldSkipNullValue(record, behaviorOnNullValues)) {
        continue;
      }
      String topicPartitionChannelName = getOrInitTopicPartitionChannelName(record);
      // if present, just add.
      // if not present, create a new arraylist and then add it to the list
      topicPartitionsToRecords
          .computeIfAbsent(topicPartitionChannelName, mapKey -> new ArrayList<>())
          .add(record);
    }

    // we will not worry about any flushing logic, once we know which records corresponds to which
    // topicPartitions, we will start processing those records

    // once they are processed, we will immediately call insertRows API and not buffer
    // TODO:: Optimize processing and calling insertRows in its own thread
    topicPartitionsToRecords.forEach(
        (topicPartitionChannelName, sinkRecords) -> {
          TopicPartitionChannel partitionChannel =
              partitionsToChannel.get(topicPartitionChannelName);
          // sets the received records in put API in TopicPartitionChannel
          //          partitionChannel.setSinkRecordsFromKafka(sinkRecords);

          // Step1: We will process each record (Convert the SinkRecord to snowflake
          // record/JSON-ify)
          // Step2: Buffer them and immediately call insertRows API after we have processed all
          // records for this partition
          partitionChannel.processAndInsertSinkRecords(sinkRecords);
        });
  }

  public String getOrInitTopicPartitionChannelName(SinkRecord record) {
    String nameIndex = getNameIndex(record.topic(), record.kafkaPartition());
    // init a new topic partition
    if (!partitionsToChannel.containsKey(nameIndex)) {
      LOGGER.warn(
          "Topic: {} Partition: {} hasn't been initialized by OPEN " + "function",
          record.topic(),
          record.kafkaPartition());
      startTask(
          Utils.tableName(record.topic(), this.topic2TableMap),
          record.topic(),
          record.kafkaPartition());
    }
    return nameIndex;
  }

  @Override
  public void insert(SinkRecord record) {
    String nameIndex = getNameIndex(record.topic(), record.kafkaPartition());
    // init a new topic partition
    if (!partitionsToChannel.containsKey(nameIndex)) {
      LOGGER.warn(
          "Topic: {} Partition: {} hasn't been initialized by OPEN " + "function",
          record.topic(),
          record.kafkaPartition());
      startTask(
          Utils.tableName(record.topic(), this.topic2TableMap),
          record.topic(),
          record.kafkaPartition());
    }

    TopicPartitionChannel channelPartition = partitionsToChannel.get(nameIndex);
    channelPartition.insertRecordToBuffer(record);

    // # of records or size based flushing
    if (channelPartition.getStreamingBuffer().getBufferSize() >= getFileSize()
        || (getRecordNumber() != 0
            && channelPartition.getStreamingBuffer().getNumOfRecord() >= getRecordNumber())) {
      LOGGER.info(
          "Either a record based flush or a size based flush(insertRow) for channel:{}",
          channelPartition.getChannelName());
      channelPartition.insertBufferedRows();
    }
  }

  @Override
  public long getOffset(TopicPartition topicPartition) {
    String name = getNameIndex(topicPartition.topic(), topicPartition.partition());
    if (partitionsToChannel.containsKey(name)) {
      return partitionsToChannel.get(name).getCommittedOffset();
    } else {
      LOGGER.warn(
          "Topic: {} Partition: {} hasn't been initialized to get offset",
          topicPartition.topic(),
          topicPartition.partition());
      return 0;
    }
  }

  @Override
  public int getPartitionCount() {
    return partitionsToChannel.size();
  }

  @Override
  public void callAllGetOffset() {
    // undefined
  }

  @Override
  public void closeAll() {
    // undefined
  }

  @Override
  public void close(Collection<TopicPartition> partitions) {
    // undefined
    partitions.forEach(
        topicPartition -> {
          String name = getNameIndex(topicPartition.topic(), topicPartition.partition());
          LOGGER.info("Closing partition channel:{}", name);
          partitionsToChannel.get(name).closeChannel();
        });

    // do we need to close the client? If I close, I will have to re init the client upon rebalance
    // in open()
    LOGGER.info("Closing Client:{}", this.streamingIngestClientName);
    try {
      streamingIngestClient.close().get();
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ExecutionException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void setIsStoppedToTrue() {}

  @Override
  public boolean isClosed() {
    return false;
  }

  @Override
  public void setRecordNumber(long num) {
    if (num < 0) {
      LOGGER.error("number of record in each file is {}, it is negative, reset to 0", num);
      this.recordNum = 0;
    } else {
      this.recordNum = num;
      LOGGER.info("set number of record limitation to {}", num);
    }
  }

  // Assume this is buffer size in bytes, since this is streaming ingestion
  @Override
  public void setFileSize(long size) {}

  @Override
  public void setTopic2TableMap(Map<String, String> topic2TableMap) {
    this.topic2TableMap = topic2TableMap;
  }

  @Override
  public void setFlushTime(long time) {
    if (time < SnowflakeSinkConnectorConfig.BUFFER_FLUSH_TIME_SEC_MIN) {
      LOGGER.error(
          "flush time is {} seconds, it is smaller than the minimum "
              + "flush time {} seconds, reset to the minimum flush time",
          time,
          SnowflakeSinkConnectorConfig.BUFFER_FLUSH_TIME_SEC_MIN);
      this.flushTime = SnowflakeSinkConnectorConfig.BUFFER_FLUSH_TIME_SEC_MIN;
    } else {
      this.flushTime = time;
      LOGGER.info("set flush time to {} seconds", time);
    }
  }

  @Override
  public void setMetadataConfig(SnowflakeMetadataConfig configMap) {
    this.recordService.setMetadataConfig(configMap);
  }

  @Override
  public long getRecordNumber() {
    return this.recordNum;
  }

  @Override
  public long getFlushTime() {
    return this.flushTime;
  }

  @Override
  public long getFileSize() {
    return this.fileSize;
  }

  @Override
  public void setBehaviorOnNullValuesConfig(
      SnowflakeSinkConnectorConfig.BehaviorOnNullValues behavior) {
    this.behaviorOnNullValues = behavior;
  }

  @Override
  public void setCustomJMXMetrics(boolean enableJMX) {
    this.enableCustomJMXMonitoring = enableJMX;
  }

  @Override
  public SnowflakeSinkConnectorConfig.BehaviorOnNullValues getBehaviorOnNullValuesConfig() {
    return this.behaviorOnNullValues;
  }

  @Override
  public Optional<MetricRegistry> getMetricRegistry(String pipeName) {
    return Optional.empty();
  }

  @VisibleForTesting
  protected static String getNameIndex(String topic, int partition) {
    return topic + "_" + partition;
  }

  // ------ Streaming Ingest Related Functions ------ //
  private OpenChannelRequest getOpenChannelRequest(
      final String channelName, final String tableName) {
    return OpenChannelRequest.builder(channelName)
        .setDBName(this.connectorConfig.get("snowflake.database.name"))
        .setSchemaName(this.connectorConfig.get("snowflake.schema.name"))
        .setTableName(tableName)
        .build();
  }

  private void createTableIfNotExists(final String tableName) {
    if (this.conn.tableExist(tableName)) {
      if (this.conn.isTableCompatible(tableName)) {
        LOGGER.info("Using existing table {}.", tableName);
      } else {
        throw SnowflakeErrors.ERROR_5003.getException("table name: " + tableName);
      }
    } else {
      LOGGER.info("Creating new table {}.", tableName);
      this.conn.createTable(tableName);
    }
  }
}
