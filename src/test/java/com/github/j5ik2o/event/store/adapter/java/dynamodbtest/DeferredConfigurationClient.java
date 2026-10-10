package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;

/** A controllable SDK completion for the Scala asynchronous factory boundary. */
public final class DeferredConfigurationClient {
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final CompletableFuture<BatchGetItemResponse> response = new CompletableFuture<>();
  private final DynamoDbAsyncClient client =
      (DynamoDbAsyncClient)
          Proxy.newProxyInstance(
              DynamoDbAsyncClient.class.getClassLoader(),
              new Class<?>[] {DynamoDbAsyncClient.class},
              (proxy, method, arguments) -> {
                if (method.getName().equals("batchGetItem")) {
                  requests.incrementAndGet();
                  return response;
                }
                if (method.getName().equals("close")) {
                  closed.set(true);
                  return null;
                }
                throw new UnsupportedOperationException(method.getName());
              });

  public DynamoDbAsyncClient client() {
    return client;
  }

  public int requests() {
    return requests.get();
  }

  public boolean closed() {
    return closed.get();
  }

  public void succeed() {
    Map<String, AttributeValue> identity =
        Map.of(
            "aid", AttributeValue.fromS("__config__"),
            "store_id", AttributeValue.fromS("deferred-store"),
            "layout_version", AttributeValue.fromN("1"));
    java.util.HashMap<String, AttributeValue> journal = new java.util.HashMap<>(identity);
    journal.put("seq_nr", AttributeValue.fromN("0"));
    java.util.HashMap<String, AttributeValue> snapshot = new java.util.HashMap<>(identity);
    snapshot.put("skey", AttributeValue.fromN("0"));
    response.complete(
        BatchGetItemResponse.builder()
            .responses(Map.of("journal", List.of(journal), "snapshot", List.of(snapshot), "head", List.of(identity)))
            .build());
  }

  public void fail(Throwable failure) {
    response.completeExceptionally(failure);
  }
}
