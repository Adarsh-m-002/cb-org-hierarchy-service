package com.hierarchyhub.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hierarchyhub.dto.Constants;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class ElasticsearchUtil {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchUtil.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient elasticsearchClient;

    public ElasticsearchUtil(@Qualifier(Constants.ORG_INDEX_ELASTICSEARCH_CLIENT_BEAN) RestClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    /** Convenience overload for the common case: every filter is a plain term-equality AND'd together - see {@link #scrollSearch(String, ObjectNode, int, int)} for an arbitrary query. */
    public List<Map<String, Object>> scrollSearch(String indexAlias, Map<String, Object> termFilters, int pageSize, int scrollKeepAliveMinutes) {
        return scrollSearch(indexAlias, flatTermsQuery(termFilters), pageSize, scrollKeepAliveMinutes);
    }

    /**
     * Scrolls every hit matching an arbitrary, caller-built Elasticsearch {@code query} clause (a plain term-AND, a
     * full {@code bool} with {@code must}/{@code must_not}/{@code should}, a {@code match_phrase_prefix}, ...) -
     * this method knows nothing about what the query means, only how to page through its results via the scroll
     * API. Scroll, not {@code from}/{@code size}, because the caller may need every matching row regardless of
     * count (a large ministry/state's full org list), not one bounded page.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> scrollSearch(String indexAlias, ObjectNode query, int pageSize, int scrollKeepAliveMinutes) {
        String scrollKeepAlive = scrollKeepAliveMinutes + Constants.SCROLL_KEEP_ALIVE_UNIT_MINUTES;
        String scrollId = null;
        try {
            JsonNode page = search(indexAlias, query, pageSize, scrollKeepAlive);
            List<Map<String, Object>> rows = new ArrayList<>();
            scrollId = textOrNull(page.get(Constants.SCROLL_ID));
            JsonNode hits = page.path(Constants.HITS).path(Constants.HITS);

            while (hits.size() > 0) {
                for (JsonNode hit : hits) {
                    rows.add(OBJECT_MAPPER.convertValue(hit.path(Constants.SOURCE), Map.class));
                }
                page = scroll(scrollId, scrollKeepAlive);
                scrollId = textOrNull(page.get(Constants.SCROLL_ID));
                hits = page.path(Constants.HITS).path(Constants.HITS);
            }
            return rows;
        } catch (Exception e) {
            log.warn("Elasticsearch scroll search failed: index={}, query={}, error={}", indexAlias, query, e.getMessage());
            return Collections.emptyList();
        } finally {
            clearScrollQuietly(scrollId);
        }
    }

    private JsonNode search(String indexAlias, ObjectNode query, int pageSize, String scrollKeepAlive) throws IOException {
        Request request = new Request(Constants.HTTP_METHOD_POST, "/" + indexAlias + Constants.ES_SEARCH_PATH);
        request.addParameter(Constants.SCROLL, scrollKeepAlive);
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put(Constants.SIZE, pageSize);
        body.set(Constants.QUERY, query);
        request.setJsonEntity(body.toString());
        return executeAndReadBody(request);
    }

    private static ObjectNode flatTermsQuery(Map<String, Object> termFilters) {
        List<ObjectNode> must = new ArrayList<>();
        termFilters.forEach((field, value) -> must.add(term(field, value)));
        return boolQuery(must, List.of());
    }

    /** {@code {"term": {field: value}}}. */
    public static ObjectNode term(String field, Object value) {
        ObjectNode clause = OBJECT_MAPPER.createObjectNode();
        clause.putObject(Constants.TERM).putPOJO(field, value);
        return clause;
    }

    /** {@code {"match_phrase_prefix": {field: value}}}. */
    public static ObjectNode matchPhrasePrefix(String field, String value) {
        ObjectNode clause = OBJECT_MAPPER.createObjectNode();
        clause.putObject(Constants.MATCH_PHRASE_PREFIX).put(field, value);
        return clause;
    }

    /** {@code {"exists": {"field": field}}}. */
    public static ObjectNode exists(String field) {
        ObjectNode clause = OBJECT_MAPPER.createObjectNode();
        clause.putObject(Constants.EXISTS).put(Constants.FIELD, field);
        return clause;
    }

    /** {@code {"bool": {"must": must, "must_not": mustNot}}} - either list may be empty. */
    public static ObjectNode boolQuery(List<ObjectNode> must, List<ObjectNode> mustNot) {
        ObjectNode query = OBJECT_MAPPER.createObjectNode();
        ObjectNode bool = query.putObject(Constants.BOOL);
        bool.set(Constants.MUST, toArray(must));
        bool.set(Constants.MUST_NOT, toArray(mustNot));
        return query;
    }

    /** {@code {"bool": {"should": should, "minimum_should_match": 1}}} - at least one of {@code should} must match. */
    public static ObjectNode anyOf(List<ObjectNode> should) {
        ObjectNode clause = OBJECT_MAPPER.createObjectNode();
        ObjectNode bool = clause.putObject(Constants.BOOL);
        bool.set(Constants.SHOULD, toArray(should));
        bool.put(Constants.MINIMUM_SHOULD_MATCH, 1);
        return clause;
    }

    private static ArrayNode toArray(List<ObjectNode> nodes) {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        array.addAll(nodes);
        return array;
    }

    private JsonNode scroll(String scrollId, String scrollKeepAlive) throws IOException {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put(Constants.SCROLL, scrollKeepAlive);
        body.put(Constants.SCROLL_ID_PARAM, scrollId);

        Request request = new Request(Constants.HTTP_METHOD_POST, Constants.ES_SEARCH_SCROLL_PATH);
        request.setJsonEntity(body.toString());
        return executeAndReadBody(request);
    }

    private JsonNode executeAndReadBody(Request request) throws IOException {
        Response response = elasticsearchClient.performRequest(request);
        return OBJECT_MAPPER.readTree(response.getEntity().getContent());
    }

    private void clearScrollQuietly(String scrollId) {
        if (scrollId == null) {
            return;
        }
        try {
            ObjectNode body = OBJECT_MAPPER.createObjectNode();
            body.putArray(Constants.SCROLL_ID_PARAM).add(scrollId);
            Request request = new Request(Constants.HTTP_METHOD_DELETE, Constants.ES_SEARCH_SCROLL_PATH);
            request.setJsonEntity(body.toString());
            elasticsearchClient.performRequest(request);
        } catch (Exception e) {
            log.warn("Could not clear Elasticsearch scroll context: {}", e.getMessage());
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
