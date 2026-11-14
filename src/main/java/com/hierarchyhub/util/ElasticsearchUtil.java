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

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> scrollSearch(String indexAlias, Map<String, Object> termFilters, int pageSize, int scrollKeepAliveMinutes) {
        String scrollKeepAlive = scrollKeepAliveMinutes + Constants.SCROLL_KEEP_ALIVE_UNIT_MINUTES;
        String scrollId = null;
        try {
            JsonNode page = search(indexAlias, termFilters, pageSize, scrollKeepAlive);
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
            log.warn("Elasticsearch scroll search failed: index={}, termFilters={}, error={}", indexAlias, termFilters, e.getMessage());
            return Collections.emptyList();
        } finally {
            clearScrollQuietly(scrollId);
        }
    }

    private JsonNode search(String indexAlias, Map<String, Object> termFilters, int pageSize, String scrollKeepAlive) throws IOException {
        Request request = new Request(Constants.HTTP_METHOD_POST, "/" + indexAlias + Constants.ES_SEARCH_PATH);
        request.addParameter(Constants.SCROLL, scrollKeepAlive);
        request.setJsonEntity(queryBody(termFilters, pageSize).toString());
        return executeAndReadBody(request);
    }

    private static ObjectNode queryBody(Map<String, Object> termFilters, int size) {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put(Constants.SIZE, size);
        ArrayNode must = body.putObject(Constants.QUERY).putObject(Constants.BOOL).putArray(Constants.MUST);
        termFilters.forEach((field, value) -> must.addObject().putObject(Constants.TERM).putPOJO(field, value));
        return body;
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
