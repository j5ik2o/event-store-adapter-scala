package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DynamoDbRequestScopeTest {
  private static final Set<String> APIS =
      Set.of(
          "GetItem",
          "BatchGetItem",
          "Query",
          "TransactWriteItems",
          "BatchWriteItem",
          "UpdateItem");
  private static final String AID = "Account-1";

  private static JsonNode json(String text) {
    return DynamoDbJson.read(text.getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> violations(String api, String body) {
    return DynamoDbRequestScope.violations(APIS, AID, api, json(body));
  }

  private static JsonNode scope() {
    return json("{\"apis\":[\"GetItem\",\"Query\"],\"aid\":\"aggregate_id\"}");
  }

  @Test
  void allowedApisWithOnlyTheTargetAggregateAreAccepted() {
    assertTrue(violations("GetItem", "{\"Key\":{\"aid\":{\"S\":\"Account-1\"}}}").isEmpty());
    assertTrue(violations("UpdateItem", "{\"Key\":{\"aid\":{\"S\":\"Account-1\"}}}").isEmpty());
    assertTrue(
        violations(
                "BatchGetItem",
                "{\"RequestItems\":{\"t\":{\"Keys\":[{\"aid\":{\"S\":\"Account-1\"}}]}}}")
            .isEmpty());
    assertTrue(
        violations(
                "TransactWriteItems",
                "{\"TransactItems\":[{\"Put\":{\"Item\":{\"aid\":{\"S\":\"Account-1\"}}}},"
                    + "{\"Update\":{\"Key\":{\"aid\":{\"S\":\"Account-1\"}}}}]}")
            .isEmpty());
    assertTrue(
        violations(
                "BatchWriteItem",
                "{\"RequestItems\":{\"t\":[{\"PutRequest\":{\"Item\":{\"aid\":{\"S\":\"Account-1\"}}}},"
                    + "{\"DeleteRequest\":{\"Key\":{\"aid\":{\"S\":\"Account-1\"}}}}]}}")
            .isEmpty());
  }

  @Test
  void queryWithEqualityOnTheTargetAidIsAccepted() {
    assertTrue(
        violations(
                "Query",
                "{\"KeyConditionExpression\":\"#a = :a AND #s >= :s\","
                    + "\"ExpressionAttributeNames\":{\"#a\":\"aid\",\"#s\":\"seq_nr\"},"
                    + "\"ExpressionAttributeValues\":{\":a\":{\"S\":\"Account-1\"},\":s\":{\"N\":\"1\"}}}")
            .isEmpty());
  }

  @Test
  void queryExclusiveStartKeyIsNotCountedAsAnAid() {
    assertTrue(
        violations(
                "Query",
                "{\"KeyConditionExpression\":\"#a = :a\","
                    + "\"ExpressionAttributeNames\":{\"#a\":\"aid\"},"
                    + "\"ExpressionAttributeValues\":{\":a\":{\"S\":\"Account-1\"}},"
                    + "\"ExclusiveStartKey\":{\"aid\":{\"S\":\"Other-9\"}}}")
            .isEmpty());
  }

  @Test
  void scanIsAViolation() {
    assertFalse(violations("Scan", "{\"TableName\":\"t\"}").isEmpty());
  }

  @Test
  void queryOnAnotherAidIsAViolation() {
    assertFalse(
        violations(
                "Query",
                "{\"KeyConditionExpression\":\"#a = :a\","
                    + "\"ExpressionAttributeNames\":{\"#a\":\"aid\"},"
                    + "\"ExpressionAttributeValues\":{\":a\":{\"S\":\"Other-9\"}}}")
            .isEmpty());
  }

  @Test
  void queryWithoutKeyConditionIsAViolation() {
    assertFalse(violations("Query", "{\"TableName\":\"t\"}").isEmpty());
  }

  @Test
  void queryWithoutAidEqualityIsAViolation() {
    assertFalse(
        violations(
                "Query",
                "{\"KeyConditionExpression\":\"#s >= :s\","
                    + "\"ExpressionAttributeNames\":{\"#s\":\"seq_nr\"},"
                    + "\"ExpressionAttributeValues\":{\":s\":{\"N\":\"1\"}}}")
            .isEmpty());
  }

  @Test
  void keyApisOnAnotherAidAreViolations() {
    assertFalse(violations("GetItem", "{\"Key\":{\"aid\":{\"S\":\"Other-9\"}}}").isEmpty());
    assertFalse(violations("UpdateItem", "{\"Key\":{\"aid\":{\"S\":\"Other-9\"}}}").isEmpty());
    assertFalse(
        violations(
                "BatchGetItem",
                "{\"RequestItems\":{\"t\":{\"Keys\":[{\"aid\":{\"S\":\"Account-1\"}},"
                    + "{\"aid\":{\"S\":\"Other-9\"}}]}}}")
            .isEmpty());
    assertFalse(
        violations(
                "BatchWriteItem",
                "{\"RequestItems\":{\"t\":[{\"DeleteRequest\":{\"Key\":{\"aid\":{\"S\":\"Other-9\"}}}}]}}")
            .isEmpty());
  }

  @Test
  void oneForeignItemInsideATransactionIsAViolation() {
    assertFalse(
        violations(
                "TransactWriteItems",
                "{\"TransactItems\":[{\"Put\":{\"Item\":{\"aid\":{\"S\":\"Account-1\"}}}},"
                    + "{\"Delete\":{\"Key\":{\"aid\":{\"S\":\"Other-9\"}}}}]}")
            .isEmpty());
  }

  @Test
  void requestWithNoCheckableAidIsAViolation() {
    assertFalse(violations("GetItem", "{}").isEmpty());
  }

  @Test
  void noRecordedRequestsIsAViolation() {
    assertThrows(AssertionError.class, () -> DynamoDbRequestScope.check(scope(), AID, List.of()));
  }
}
