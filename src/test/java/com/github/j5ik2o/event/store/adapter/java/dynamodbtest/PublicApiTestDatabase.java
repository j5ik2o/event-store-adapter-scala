package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig;
import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Resource ownership for the Scala domain examples, using the accepted table definitions. */
public final class PublicApiTestDatabase implements AutoCloseable {
  private final GenericContainer<?> container =
      new GenericContainer<>(DockerImageName.parse(DynamoDbLocalExtension.IMAGE))
          .withExposedPorts(8000)
          .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory");
  private final DynamoDbTestContext context;

  public PublicApiTestDatabase() {
    DynamoDbTestContext acquired = null;
    try {
      container.start();
      acquired =
          new DynamoDbTestContext(
              URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8000)));
      DynamoDbConfigurationTables.create(acquired, RetentionPolicy.none());
      context = acquired;
    } catch (Throwable failure) {
      if (acquired != null) {
        try {
          acquired.close();
        } catch (Throwable cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      try {
        container.stop();
      } catch (Throwable cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  public DynamoDbClient client() {
    return context.admin;
  }

  public DynamoDbAsyncClient asyncClient() {
    return context.adminAsync;
  }

  public DynamoDbTableConfig tables() {
    return DynamoDbConfigurationTables.config(context).build();
  }

  @Override
  public void close() {
    try {
      DynamoDbConfigurationFixture.close(context);
    } finally {
      container.stop();
      if (container.isRunning()) throw new AssertionError("DynamoDB Local did not stop");
    }
  }
}
