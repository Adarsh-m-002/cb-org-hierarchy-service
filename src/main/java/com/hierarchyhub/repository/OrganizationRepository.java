package com.hierarchyhub.repository;

import com.hierarchyhub.model.Organization;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class OrganizationRepository {

    private final SessionFactory sessionFactory;

    public OrganizationRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public Organization save(Organization organization) {
        Session session = sessionFactory.openSession();
        session.save(organization);
        return organization;
    }

    public Optional<Organization> findById(String id) {
        Session session = sessionFactory.openSession();
        return Optional.ofNullable(session.load(Organization.class, id));
    }

    public String findHierarchyByOrgId(String orgId, int maxLevel) {
        Map<String, Object> params = new HashMap<>();
        params.put("orgId", orgId);
        params.put("maxLevel", maxLevel);
        return singleColumn(
                "MATCH (root:Organization {id: $orgId}) " +
                        "CALL apoc.path.subgraphNodes(root, { " +
                        "   relationshipFilter: 'PARENT_OF', " +
                        "   bfs: true, " +
                        "   maxLevel: $maxLevel " +
                        "}) YIELD node " +

                        "OPTIONAL MATCH (node)-[:PARENT_OF]->(child) " +
                        "WITH node, COLLECT(child.id) AS childIds " +

                        "WITH COLLECT({ " +
                        "    id: node.id, " +
                        "    orgname: node.orgname, " +
                        "    properties: apoc.map.clean(properties(node), ['labels'], []), " +
                        "    parentOrgId: node.parentOrgId, " +
                        "    children: childIds " +
                        "}) AS nodes " +

                        "RETURN apoc.convert.toJson(nodes) AS hierarchy",
                params, "hierarchy");
    }

    public String findOrganizationHierarchy(String searchTerm, int maxLevel) {
        Map<String, Object> params = new HashMap<>();
        params.put("searchTerm", searchTerm);
        params.put("maxLevel", maxLevel);
        return singleColumn(
                "MATCH (matchedOrg:Organization) " +
                        "WHERE toLower(matchedOrg.orgname) CONTAINS toLower($searchTerm) " +
                        "CALL apoc.path.subgraphNodes(matchedOrg, { " +
                        "    relationshipFilter: 'PARENT_OF', " +
                        "    maxLevel: $maxLevel, " +
                        "    bfs: true " +
                        "}) YIELD node " +
                        "OPTIONAL MATCH (node)-[:PARENT_OF]->(child) " +
                        "WITH node, COLLECT(child.id) AS childIds " +
                        "WITH COLLECT({ " +
                        "    id: node.id, " +
                        "    orgname: node.orgname, " +
                        "    properties: apoc.map.clean(properties(node), ['labels'], []), " +
                        "    parentOrgId: node.parentOrgId, " +
                        "    children: childIds " +
                        "}) AS nodes " +
                        "RETURN apoc.convert.toJson(nodes) AS hierarchy",
                params, "hierarchy");
    }

    public List<Map<String, Object>> getLevel1OrganizationsWithChildrenCount() {
        Session session = sessionFactory.openSession();
        Result result = session.query(
                "MATCH (l1:Organization) " +
                        "WHERE NOT (:Organization)-[:PARENT_OF]->(l1) " +
                        "OPTIONAL MATCH (l1)-[:PARENT_OF]->(child:Organization) " +
                        "WITH l1, COUNT(child) AS childCount " +
                        "RETURN properties(l1) AS organization, childCount",
                Collections.emptyMap());
        List<Map<String, Object>> rows = new ArrayList<>();
        result.queryResults().forEach(rows::add);
        return rows;
    }

    private String singleColumn(String cypher, Map<String, Object> params, String column) {
        Session session = sessionFactory.openSession();
        Result result = session.query(cypher, params);
        for (Map<String, Object> row : result.queryResults()) {
            Object value = row.get(column);
            return value == null ? null : value.toString();
        }
        return null;
    }
}
