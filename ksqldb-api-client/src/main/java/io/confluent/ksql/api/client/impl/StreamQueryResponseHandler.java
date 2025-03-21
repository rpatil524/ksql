/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.api.client.impl;

import io.confluent.ksql.api.client.Row;
import io.confluent.ksql.api.client.StreamedQueryResult;
import io.confluent.ksql.api.client.exception.KsqlException;
import io.confluent.ksql.api.client.util.RowUtil;
import io.confluent.ksql.rest.entity.QueryResponseMetadata;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.parsetools.RecordParser;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class StreamQueryResponseHandler
    extends QueryResponseHandler<CompletableFuture<StreamedQueryResult>> {
  private static final Logger LOG = LogManager.getLogger(StreamQueryResponseHandler.class);

  private StreamedQueryResultImpl queryResult;
  private Map<String, Integer> columnNameToIndex;
  private boolean paused;
  private AtomicReference<String> serializedConsistencyVector;
  private AtomicReference<String> continuationToken;
  private String sql;
  private Map<String, Object> properties;
  private ClientImpl client;

  StreamQueryResponseHandler(
      final Context context,
      final RecordParser recordParser,
      final CompletableFuture<StreamedQueryResult> cf,
      final AtomicReference<String> serializedCV,
      final AtomicReference<String> continuationToken,
      final String sql,
      final Map<String, Object> properties,
      final ClientImpl client
  ) {
    super(context, recordParser, cf);
    this.serializedConsistencyVector = Objects.requireNonNull(serializedCV, "serializedCV");
    this.continuationToken = Objects.requireNonNull(continuationToken, "continuationToken");
    this.sql = Objects.requireNonNull(sql, "sql");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  protected void handleMetadata(final QueryResponseMetadata queryResponseMetadata) {
    this.queryResult = new StreamedQueryResultImpl(
        context,
        queryResponseMetadata.queryId,
        queryResponseMetadata.columnNames,
        RowUtil.columnTypesFromStrings(queryResponseMetadata.columnTypes),
        continuationToken,
        sql,
        properties,
        client
    );
    this.columnNameToIndex = RowUtil.valueToIndexMap(queryResponseMetadata.columnNames);
    cf.complete(queryResult);
  }

  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  @Override
  protected void handleRow(final Buffer buff) {
    if (queryResult == null) {
      throw new IllegalStateException("handleRow called before metadata processed");
    }

    final Row row;
    final JsonObject jsonObject = buff.toJsonObject();

    if (!jsonObject.containsKey("finalMessage")) {
      if (jsonObject.containsKey("row")) {
        row = new RowImpl(
            queryResult.columnNames(),
            queryResult.columnTypes(),
            new JsonArray((List)((Map<?, ?>) jsonObject.getMap().get("row")).get("columns")),
            columnNameToIndex
        );
        final boolean full = queryResult.accept(row);
        if (full && !paused) {
          recordParser.pause();
          queryResult.drainHandler(this::publisherReceptive);
          paused = true;
        }
      } else if (jsonObject.containsKey("continuationToken")) {
        LOG.info("Response contains continuation token " + jsonObject);
        continuationToken.set(
            (String) ((Map<?, ?>) jsonObject.getMap()
                .get("continuationToken"))
                .get("continuationToken"));
      } else if (jsonObject.containsKey("errorMessage")) {
        queryResult.handleError(new KsqlException(
            (String) ((Map<?, ?>) jsonObject.getMap().get("errorMessage")).get("message")));
      } else {
        throw new RuntimeException("Could not decode JSON: " + jsonObject);
      }
    }
  }

  @Override
  protected void doHandleBodyEnd() {
    queryResult.complete();
  }

  @Override
  public void handleExceptionAfterFutureCompleted(final Throwable t) {
    queryResult.handleError(new Exception(t));
  }

  private void publisherReceptive() {
    checkContext();

    paused = false;
    recordParser.resume();
  }
}
