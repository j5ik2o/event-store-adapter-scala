package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** DY-19: 操作の間に送った全要求が、許可した API だけで操作の集約の aid だけを読み書きすることを判定する。 */
final class DynamoDbRequestScope {
  private DynamoDbRequestScope() {}

  static void check(
      JsonNode scope, String aid, List<DynamoDbRequestRecorder.Request> requests) {
    Set<String> apis = new HashSet<>();
    scope.path("apis").forEach(api -> apis.add(api.asText()));
    List<String> all = new ArrayList<>();
    if (requests.isEmpty()) all.add("no requests were recorded for request_scope");
    for (DynamoDbRequestRecorder.Request request : requests) {
      for (String violation : violations(apis, aid, request.api, request.marshalled)) {
        all.add(request.api + ": " + violation);
      }
    }
    assertTrue(all.isEmpty(), "request_scope violations: " + all);
  }

  static List<String> violations(Set<String> apis, String aid, String api, JsonNode body) {
    List<String> violations = new ArrayList<>();
    if (!apis.contains(api)) {
      violations.add("API is not allowed: " + api);
      return violations;
    }
    if (api.equals("Query")) {
      queryViolations(aid, body, violations);
      return violations;
    }
    List<JsonNode> keyed = new ArrayList<>();
    switch (api) {
      case "GetItem":
      case "UpdateItem":
        keyed.add(body.path("Key"));
        break;
      case "BatchGetItem":
        body.path("RequestItems")
            .elements()
            .forEachRemaining(table -> table.path("Keys").forEach(keyed::add));
        break;
      case "TransactWriteItems":
        for (JsonNode action : body.path("TransactItems")) {
          JsonNode put = action.path("Put");
          if (!put.isMissingNode()) keyed.add(put.path("Item"));
          for (String kind : new String[] {"Update", "Delete", "ConditionCheck"}) {
            JsonNode other = action.path(kind);
            if (!other.isMissingNode()) keyed.add(other.path("Key"));
          }
        }
        break;
      case "BatchWriteItem":
        Iterator<JsonNode> tables = body.path("RequestItems").elements();
        while (tables.hasNext()) {
          for (JsonNode write : tables.next()) {
            JsonNode put = write.path("PutRequest");
            if (!put.isMissingNode()) keyed.add(put.path("Item"));
            JsonNode delete = write.path("DeleteRequest");
            if (!delete.isMissingNode()) keyed.add(delete.path("Key"));
          }
        }
        break;
      default:
        violations.add("API has no aid extraction rule: " + api);
        return violations;
    }
    if (keyed.isEmpty()) violations.add(api + " has no item to check");
    for (JsonNode item : keyed) {
      JsonNode value = item.path("aid").path("S");
      if (!value.isTextual() || !value.asText().equals(aid)) {
        violations.add("aid is not the operation aggregate: " + item.path("aid"));
      }
    }
    return violations;
  }

  private static void queryViolations(String aid, JsonNode body, List<String> violations) {
    JsonNode all;
    try {
      all = DynamoDbRequestStructure.parse(body).path("key_condition").path("all");
    } catch (IllegalArgumentException error) {
      violations.add("Query key condition is not parseable: " + error.getMessage());
      return;
    }
    if (!all.isArray() || all.isEmpty()) {
      violations.add("Query has no KeyConditionExpression");
      return;
    }
    int equalities = 0;
    for (JsonNode term : all) {
      if (term.path("attribute").asText().equals("aid")
          && term.path("operator").asText().equals("eq")) {
        equalities++;
        JsonNode value = term.path("argument").path("S");
        if (!value.isTextual() || !value.asText().equals(aid)) {
          violations.add("Query aid is not the operation aggregate: " + term.path("argument"));
        }
      }
    }
    if (equalities == 0) violations.add("Query has no equality condition on aid");
  }
}
