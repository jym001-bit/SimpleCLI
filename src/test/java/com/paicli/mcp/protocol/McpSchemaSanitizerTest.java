package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpSchemaSanitizerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void removesUnsupportedJsonSchemaFields() throws Exception {
        JsonNode raw = MAPPER.readTree("""
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "$id": "tool",
                  "$ref": "#/$defs/X",
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "$ref": "#/$defs/Path"}
                  }
                }
                """);

        JsonNode cleaned = McpSchemaSanitizer.sanitize(raw);

        assertFalse(cleaned.has("$schema"));
        assertFalse(cleaned.has("$id"));
        assertFalse(cleaned.has("$ref"));
        assertFalse(cleaned.path("properties").path("path").has("$ref"));
    }

    @Test
    void collapsesOptionalAnyOfToSingleType() throws Exception {
        JsonNode raw = MAPPER.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "name": {
                      "anyOf": [{"type": "string"}, {"type": "null"}],
                      "default": null,
                      "title": "Name"
                    }
                  }
                }
                """);

        JsonNode name = McpSchemaSanitizer.sanitize(raw).path("properties").path("name");

        assertFalse(name.has("anyOf"));
        assertEquals("string", name.path("type").asText());
        assertEquals("Name", name.path("title").asText());
    }

    @Test
    void mergesSingleComplexOptionIntoNode() throws Exception {
        JsonNode raw = MAPPER.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "tags": {
                      "anyOf": [
                        {"type": "array", "items": {"anyOf": [{"type": "string"}, {"type": "null"}]}},
                        {"type": "null"}
                      ],
                      "description": "tag list"
                    }
                  }
                }
                """);

        JsonNode tags = McpSchemaSanitizer.sanitize(raw).path("properties").path("tags");

        assertEquals("array", tags.path("type").asText());
        assertEquals("tag list", tags.path("description").asText());
        assertEquals("string", tags.path("items").path("type").asText());
        assertFalse(tags.path("items").has("anyOf"));
    }

    @Test
    void collapsesSimpleOneOfToTypeArray() throws Exception {
        JsonNode raw = MAPPER.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "id": {"oneOf": [{"type": "integer"}, {"type": "string"}], "description": "record id"}
                  }
                }
                """);

        JsonNode id = McpSchemaSanitizer.sanitize(raw).path("properties").path("id");

        assertFalse(id.has("oneOf"));
        assertTrue(id.path("type").isArray());
        assertEquals("[\"integer\",\"string\"]", id.path("type").toString());
        assertEquals("record id", id.path("description").asText());
    }

    @Test
    void complexUnionBecomesPermissiveWithDescription() throws Exception {
        JsonNode raw = MAPPER.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "target": {
                      "anyOf": [
                        {"type": "object", "properties": {"path": {"type": "string"}}},
                        {"type": "object", "properties": {"url": {"type": "string"}}},
                        {"type": "null"}
                      ],
                      "description": "where to read"
                    }
                  }
                }
                """);

        JsonNode target = McpSchemaSanitizer.sanitize(raw).path("properties").path("target");

        assertFalse(target.has("anyOf"));
        assertFalse(target.has("type"), "形状不同的选项不能硬写成某一种 type");
        String description = target.path("description").asText();
        assertTrue(description.startsWith("where to read"), description);
        assertTrue(description.contains("object(path)"), description);
        assertTrue(description.contains("object(url)"), description);
    }

    @Test
    void keepsDeclaredTypeAndDescribesUnion() throws Exception {
        JsonNode raw = MAPPER.readTree("""
                {
                  "type": "object",
                  "anyOf": [{"type": "string"}, {"type": "number"}],
                  "description": "value"
                }
                """);

        JsonNode cleaned = McpSchemaSanitizer.sanitize(raw);

        assertEquals("object", cleaned.path("type").asText());
        assertTrue(cleaned.path("description").asText().contains("anyOf options: string, number"));
    }

    @Test
    void truncatesLongDescriptions() throws Exception {
        String longText = "x".repeat(1200);
        JsonNode raw = MAPPER.readTree("""
                {"type":"object","description":"%s","properties":{}}
                """.formatted(longText));

        JsonNode cleaned = McpSchemaSanitizer.sanitize(raw);

        assertTrue(cleaned.path("description").asText().endsWith("..."));
        assertTrue(cleaned.path("description").asText().length() < longText.length());
    }
}
