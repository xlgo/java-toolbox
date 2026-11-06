package com.aqishi.toolbox.feature.network.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class GraphQLRequestTest {

    @Test
    void buildsValidJsonPayloadWithVariablesAndOperationName() throws Exception {
        String query = "query FindUser($id: ID!) { user(id: $id) { name email } }";
        String vars = "{\"id\": 42}";
        String opName = "FindUser";

        String json = GraphQLRequest.buildJsonPayload(query, vars, opName);
        JsonNode node = Json.mapper().readTree(json);

        assertEquals(query, node.get("query").asText());
        assertEquals(42, node.get("variables").get("id").asInt());
        assertEquals("FindUser", node.get("operationName").asText());
    }

    @Test
    void rejectsEmptyQueryOrMalformedVariables() {
        assertThrows(IllegalArgumentException.class,
                () -> GraphQLRequest.buildJsonPayload("", "{}", null));
        assertThrows(IllegalArgumentException.class,
                () -> GraphQLRequest.buildJsonPayload("{ hello }", "{ not json }", null));
    }

    @Test
    void summarizesIntrospectionSchema() {
        String mockIntrospection =
                """
                {
                  "data": {
                    "__schema": {
                      "queryType": { "name": "Query" },
                      "mutationType": { "name": "Mutation" },
                      "types": [
                        {
                          "kind": "OBJECT",
                          "name": "Query",
                          "fields": [
                            {
                              "name": "user",
                              "description": "Fetch user by ID",
                              "args": [
                                {
                                  "name": "id",
                                  "type": { "kind": "NON_NULL", "ofType": { "kind": "SCALAR", "name": "ID" } }
                                }
                              ],
                              "type": { "kind": "OBJECT", "name": "User" }
                            }
                          ]
                        },
                        {
                          "kind": "OBJECT",
                          "name": "Mutation",
                          "fields": [
                            {
                              "name": "createUser",
                              "args": [],
                              "type": { "kind": "OBJECT", "name": "User" }
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;

        String summary = GraphQLRequest.summarizeIntrospection(mockIntrospection);
        assertTrue(summary.contains("Query Type: Query"), summary);
        assertTrue(summary.contains("Mutation Type: Mutation"), summary);
        assertTrue(summary.contains("user(id: ID!): User"), summary);
        assertTrue(summary.contains("createUser: User"), summary);
        assertTrue(summary.contains("Fetch user by ID"), summary);
    }
}
