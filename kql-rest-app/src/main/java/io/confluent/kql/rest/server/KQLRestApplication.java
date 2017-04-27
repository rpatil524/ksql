/**
 * Copyright 2017 Confluent Inc.
 **/
package io.confluent.kql.rest.server;

import io.confluent.adminclient.KafkaAdminClient;
import io.confluent.kql.KQLEngine;
import io.confluent.kql.metastore.MetaStore;
import io.confluent.kql.metastore.MetaStoreImpl;
import io.confluent.kql.rest.server.computation.CommandRunner;
import io.confluent.kql.rest.server.computation.StatementExecutor;
import io.confluent.kql.rest.server.resources.KQLExceptionMapper;
import io.confluent.kql.rest.server.resources.KQLResource;
import io.confluent.kql.rest.server.resources.StatusResource;
import io.confluent.kql.rest.server.resources.streaming.StreamedQueryResource;
import io.confluent.kql.util.KQLConfig;
import io.confluent.rest.Application;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.servlet.ServletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public class KQLRestApplication extends Application<KQLRestConfig> {

  private static final Logger log = LoggerFactory.getLogger(KQLRestApplication.class);

  private final CommandRunner commandRunner;
  private final StatusResource statusResource;
  private final StreamedQueryResource streamedQueryResource;
  private final KQLResource kqlResource;
  private final boolean enableQuickstartPage;

  private final Thread commandRunnerThread;

  public KQLRestApplication(
      KQLRestConfig config,
      CommandRunner commandRunner,
      StatusResource statusResource,
      StreamedQueryResource streamedQueryResource,
      KQLResource kqlResource,
      boolean enableQuickstartPage
  ) {
    super(config);
    this.commandRunner = commandRunner;
    this.statusResource = statusResource;
    this.streamedQueryResource = streamedQueryResource;
    this.kqlResource = kqlResource;
    this.enableQuickstartPage = enableQuickstartPage;

    this.commandRunnerThread = new Thread(commandRunner);
  }

  @Override
  public void setupResources(Configurable<?> config, KQLRestConfig appConfig) {
    config.register(statusResource);
    config.register(kqlResource);
    config.register(streamedQueryResource);
    config.register(new KQLExceptionMapper());
  }

  @Override
  public ResourceCollection getStaticResources() {
    if (enableQuickstartPage) {
      return new ResourceCollection(Resource.newClassPathResource("/io/confluent/kql/rest/"));
    } else {
      return super.getStaticResources();
    }
  }

  private static Properties getProps(String propsFile) throws IOException {
    Properties result = new Properties();
    result.load(new FileInputStream(propsFile));
    return result;
  }

  @Override
  public void start() throws Exception {
    super.start();
    commandRunnerThread.start();
  }

  @Override
  public void onShutdown() {
    super.onShutdown();
    commandRunner.close();
    try {
      commandRunnerThread.join();
    } catch (InterruptedException exception) {
      log.error("Interrupted while waiting for CommandRunner thread to complete", exception);
    }
  }

  @Override
  public void configureBaseApplication(Configurable<?> config, Map<String, String> metricTags) {
    super.configureBaseApplication(config, metricTags);
    // Don't want to buffer rows when streaming JSON in a request to the query resource
    config.property(ServerProperties.OUTBOUND_CONTENT_LENGTH_BUFFER, 0);
    if (enableQuickstartPage) {
      config.property(ServletProperties.FILTER_STATIC_CONTENT_REGEX, "^/quickstart\\.html$");
    }
  }

  public static void main(String[] args) throws Exception {
    CLIOptions cliOptions = CLIOptions.parse(args);
    if (cliOptions == null) {
      return;
    }

    Properties props = getProps(cliOptions.getPropertiesFile());
    KQLRestApplication app = buildApplication(props, cliOptions.getQuickstart());
    app.start();
    app.join();
    System.err.println("Server shutting down...");
  }

  public static KQLRestApplication buildApplication(Properties props, boolean quickstart) throws Exception {
    KQLRestConfig config = new KQLRestConfig(props);

    @SuppressWarnings("unchecked")
    KafkaAdminClient client = new KafkaAdminClient((Map) props);
    TopicUtil topicUtil = new TopicUtil(client);

    // TODO: Make MetaStore class configurable, consider renaming MetaStoreImpl to MetaStoreCache
    MetaStore metaStore = new MetaStoreImpl();

    KQLConfig kqlConfig = new KQLConfig(config.getKqlStreamsProperties());
    KQLEngine kqlEngine = new KQLEngine(metaStore, kqlConfig);
    StatementParser statementParser = new StatementParser(kqlEngine);

    String commandTopic = config.getCommandTopic();
    topicUtil.ensureTopicExists(commandTopic);

    Map<String, Object> commandConsumerProperties = config.getCommandConsumerProperties();
    KafkaConsumer<String, String> commandConsumer = new KafkaConsumer<>(
        commandConsumerProperties,
        new StringDeserializer(),
        new StringDeserializer()
    );

    StatementExecutor statementExecutor = new StatementExecutor(
        topicUtil,
        kqlEngine,
        statementParser
    );

    String nodeId = config.getString(KQLRestConfig.NODE_ID_CONFIG);

    CommandRunner commandRunner = new CommandRunner(
        statementExecutor,
        commandTopic,
        nodeId,
        commandConsumer,
        new KafkaProducer<>(
            config.getCommandProducerProperties(),
            new StringSerializer(),
            new StringSerializer()
        )
    );


    StatusResource statusResource = new StatusResource(statementExecutor);
    StreamedQueryResource streamedQueryResource = new StreamedQueryResource(
        kqlEngine,
        topicUtil,
        nodeId,
        statementParser,
        config.getLong(KQLRestConfig.STREAMED_QUERY_DISCONNECT_CHECK_MS_CONFIG),
        config.getKqlStreamsProperties()
    );
    KQLResource kqlResource = new KQLResource(
        kqlEngine,
        commandRunner,
        statementExecutor
    );

    commandRunner.processPriorCommands();

    return new KQLRestApplication(
        config,
        commandRunner,
        statusResource,
        streamedQueryResource,
        kqlResource,
        quickstart
    );
  }
}

/*
        TODO: Find a good, forwards-compatible use for the root resource
 */
