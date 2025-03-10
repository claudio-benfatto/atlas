/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.glossary;

import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.bulkimport.BulkImportResponse;
import org.apache.atlas.bulkimport.BulkImportResponse.ImportInfo;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.glossary.AtlasGlossary;
import org.apache.atlas.model.glossary.AtlasGlossaryTerm;
import org.apache.atlas.model.glossary.relations.AtlasGlossaryHeader;
import org.apache.atlas.model.glossary.relations.AtlasRelatedTermHeader;
import org.apache.atlas.model.glossary.relations.AtlasTermCategorizationHeader;
import org.apache.atlas.model.instance.AtlasObjectId;
import org.apache.atlas.model.instance.AtlasRelatedObjectId;
import org.apache.atlas.model.instance.AtlasRelationship;
import org.apache.atlas.model.instance.AtlasStruct;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.repository.ogm.DataAccess;
import org.apache.atlas.repository.store.graph.AtlasRelationshipStore;
import org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2;
import org.apache.atlas.type.AtlasRelationshipType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.atlas.util.FileUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.atlas.bulkimport.BulkImportResponse.ImportStatus.FAILED;

public class GlossaryTermUtils extends GlossaryUtils {
    private static final Logger  LOG           = LoggerFactory.getLogger(GlossaryTermUtils.class);
    private static final boolean DEBUG_ENABLED = LOG.isDebugEnabled();

    Map<String, String> glossaryNameGuidCacheForImport = new HashMap<>();

    protected GlossaryTermUtils(AtlasRelationshipStore relationshipStore, AtlasTypeRegistry typeRegistry, DataAccess dataAccess) {
        super(relationshipStore, typeRegistry, dataAccess);
    }

    public void processTermRelations(AtlasGlossaryTerm storeObject, AtlasGlossaryTerm updatedTerm, RelationshipOperation op) throws AtlasBaseException {
        if (DEBUG_ENABLED) {
            LOG.debug("==> GlossaryTermUtils.processTermRelations({}, {}, {})", storeObject, updatedTerm, op);
        }

        processTermAnchor(storeObject, updatedTerm, op);
        processRelatedTerms(storeObject, updatedTerm, op);
        processAssociatedCategories(storeObject, updatedTerm, op);

        if (DEBUG_ENABLED) {
            LOG.debug("<== GlossaryTermUtils.processTermRelations()");
        }
    }

    public void processTermAssignments(AtlasGlossaryTerm glossaryTerm, Collection<AtlasRelatedObjectId> relatedObjectIds) throws AtlasBaseException {
        if (DEBUG_ENABLED) {
            LOG.debug("==> GlossaryTermUtils.processTermAssignments({}, {})", glossaryTerm, relatedObjectIds);
        }

        Objects.requireNonNull(glossaryTerm);

        Set<AtlasRelatedObjectId> assignedEntities = glossaryTerm.getAssignedEntities();
        for (AtlasRelatedObjectId objectId : relatedObjectIds) {
            if (CollectionUtils.isNotEmpty(assignedEntities) && assignedEntities.contains(objectId)) {
                if (DEBUG_ENABLED) {
                    LOG.debug("Skipping already assigned entity {}", objectId);
                    continue;
                }
            }

            if (DEBUG_ENABLED) {
                LOG.debug("Assigning term guid={}, to entity guid = {}", glossaryTerm.getGuid(), objectId.getGuid());
            }
            createRelationship(defineTermAssignment(glossaryTerm.getGuid(), objectId));
        }

        if (DEBUG_ENABLED) {
            LOG.debug("<== GlossaryTermUtils.processTermAssignments()");
        }
    }

    public void processTermDissociation(AtlasGlossaryTerm glossaryTerm, Collection<AtlasRelatedObjectId> relatedObjectIds) throws AtlasBaseException {
        if (DEBUG_ENABLED) {
            LOG.debug("==> GlossaryTermUtils.processTermDissociation({}, {}, {})", glossaryTerm.getGuid(), relatedObjectIds, glossaryTerm);
        }

        Objects.requireNonNull(glossaryTerm);
        Set<AtlasRelatedObjectId> assignedEntities = glossaryTerm.getAssignedEntities();
        Map<String, AtlasRelatedObjectId> assignedEntityMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(assignedEntities)) {
            for (AtlasRelatedObjectId relatedObjectId : assignedEntities) {
                assignedEntityMap.put(relatedObjectId.getGuid(), relatedObjectId);
            }
        }

        if (CollectionUtils.isNotEmpty(relatedObjectIds)) {
            for (AtlasRelatedObjectId relatedObjectId : relatedObjectIds) {
                if (DEBUG_ENABLED) {
                    LOG.debug("Removing term guid={}, from entity guid = {}", glossaryTerm.getGuid(), relatedObjectId.getGuid());
                }
                if (Objects.isNull(relatedObjectId.getRelationshipGuid())) {
                    throw new AtlasBaseException(AtlasErrorCode.TERM_DISSOCIATION_MISSING_RELATION_GUID);
                }
                AtlasRelatedObjectId existingTermRelation = assignedEntityMap.get(relatedObjectId.getGuid());
                if (CollectionUtils.isNotEmpty(assignedEntities) && isRelationshipGuidSame(existingTermRelation, relatedObjectId)) {
                    relationshipStore.deleteById(relatedObjectId.getRelationshipGuid(), true);
                } else {
                    throw new AtlasBaseException(AtlasErrorCode.INVALID_TERM_DISSOCIATION, relatedObjectId.getRelationshipGuid(), glossaryTerm.getGuid(), relatedObjectId.getGuid());
                }
            }
        }

        if (DEBUG_ENABLED) {
            LOG.debug("<== GlossaryTermUtils.processTermDissociation()");
        }
    }

    private boolean isRelationshipGuidSame(AtlasRelatedObjectId storeObject, AtlasRelatedObjectId relatedObjectId) {
        return StringUtils.equals(relatedObjectId.getRelationshipGuid(), storeObject.getRelationshipGuid());
    }

    private void processTermAnchor(AtlasGlossaryTerm storeObject, AtlasGlossaryTerm updatedTerm, RelationshipOperation op) throws AtlasBaseException {
        if (Objects.isNull(updatedTerm.getAnchor()) && op != RelationshipOperation.DELETE) {
            throw new AtlasBaseException(AtlasErrorCode.MISSING_MANDATORY_ANCHOR);
        }

        AtlasGlossaryHeader existingAnchor    = storeObject.getAnchor();
        AtlasGlossaryHeader updatedTermAnchor = updatedTerm.getAnchor();

        switch (op) {
            case CREATE:
                if (Objects.isNull(updatedTermAnchor.getGlossaryGuid())) {
                    throw new AtlasBaseException(AtlasErrorCode.INVALID_NEW_ANCHOR_GUID);
                } else {
                    if (DEBUG_ENABLED) {
                        LOG.debug("Creating new term anchor, category = {}, glossary = {}", storeObject.getGuid(), updatedTerm.getAnchor().getGlossaryGuid());
                    }

                    createRelationship(defineTermAnchorRelation(updatedTermAnchor.getGlossaryGuid(), storeObject.getGuid()));
                }
                break;
            case UPDATE:
                if (!Objects.equals(updatedTermAnchor, existingAnchor)) {
                    if (Objects.isNull(updatedTermAnchor.getGlossaryGuid())) {
                        throw new AtlasBaseException(AtlasErrorCode.INVALID_NEW_ANCHOR_GUID);
                    }

                    if (DEBUG_ENABLED) {
                        LOG.debug("Updating term anchor, currAnchor = {}, newAnchor = {} and term = {}",
                                  existingAnchor.getGlossaryGuid(),
                                  updatedTermAnchor.getGlossaryGuid(),
                                  storeObject.getName());
                    }
                    relationshipStore.deleteById(existingAnchor.getRelationGuid(), true);

                    // Derive the qualifiedName when anchor changes
                    String        anchorGlossaryGuid = updatedTermAnchor.getGlossaryGuid();
                    AtlasGlossary glossary           = dataAccess.load(getGlossarySkeleton(anchorGlossaryGuid));
                    storeObject.setQualifiedName(storeObject.getName() + "@" + glossary.getQualifiedName());

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Derived qualifiedName = {}", storeObject.getQualifiedName());
                    }

                    createRelationship(defineTermAnchorRelation(updatedTermAnchor.getGlossaryGuid(), storeObject.getGuid()));
                }
                break;
            case DELETE:
                if (Objects.nonNull(existingAnchor)) {
                    if (DEBUG_ENABLED) {
                        LOG.debug("Deleting term anchor");
                    }
                    relationshipStore.deleteById(existingAnchor.getRelationGuid(), true);
                }
                break;
        }
    }

    private void processRelatedTerms(AtlasGlossaryTerm storeObject, AtlasGlossaryTerm updatedTerm, RelationshipOperation op) throws AtlasBaseException {
        Map<AtlasGlossaryTerm.Relation, Set<AtlasRelatedTermHeader>> newRelatedTerms      = updatedTerm.getRelatedTerms();
        Map<AtlasGlossaryTerm.Relation, Set<AtlasRelatedTermHeader>> existingRelatedTerms = storeObject.getRelatedTerms();
        switch (op) {
            case CREATE:
                for (Map.Entry<AtlasGlossaryTerm.Relation, Set<AtlasRelatedTermHeader>> entry : newRelatedTerms.entrySet()) {
                    AtlasGlossaryTerm.Relation  relation = entry.getKey();
                    Set<AtlasRelatedTermHeader> terms    = entry.getValue();
                    if (Objects.nonNull(terms)) {
                        if (DEBUG_ENABLED) {
                            LOG.debug("{} relation {} for term = {}", op, relation, storeObject.getGuid());
                            LOG.debug("Related Term count = {}", terms.size());
                        }
                        createTermRelationships(storeObject, relation, terms);
                    }
                }
                break;
            case UPDATE:
                for (AtlasGlossaryTerm.Relation relation : AtlasGlossaryTerm.Relation.values()) {
                    Map<String, AtlasRelatedTermHeader> existingTermHeaders = getRelatedTermHeaders(existingRelatedTerms, relation);
                    Map<String, AtlasRelatedTermHeader> newTermHeaders      = getRelatedTermHeaders(newRelatedTerms, relation);

                    // No existing term relations, create all
                    if (MapUtils.isEmpty(existingTermHeaders)) {
                        if (DEBUG_ENABLED) {
                            LOG.debug("Creating new term relations, relation = {}, terms = {}", relation,
                                      Objects.nonNull(newTermHeaders) ? newTermHeaders.size() : "none");
                        }
                        createTermRelationships(storeObject, relation, newTermHeaders.values());
                        continue;
                    }

                    // Existing term relations but nothing in updated object, remove all
                    if (MapUtils.isEmpty(newTermHeaders)) {
                        if (DEBUG_ENABLED) {
                            LOG.debug("Deleting existing term relations, relation = {}, terms = {}", relation, existingTermHeaders.size());
                        }
                        deleteTermRelationships(relation, existingTermHeaders.values());
                        continue;
                    }
                    // Determine what to update, delete or create
                    Set<AtlasRelatedTermHeader> toCreate = newTermHeaders
                                                                   .values()
                                                                   .stream()
                                                                   .filter(t -> !existingTermHeaders.containsKey(t.getTermGuid()))
                                                                   .collect(Collectors.toSet());
                    Set<AtlasRelatedTermHeader> toUpdate = newTermHeaders
                                                                   .values()
                                                                   .stream()
                                                                   .filter(t -> updatedExistingTermRelation(existingTermHeaders, t))
                                                                   .collect(Collectors.toSet());
                    Set<AtlasRelatedTermHeader> toDelete = existingTermHeaders
                                                                   .values()
                                                                   .stream()
                                                                   .filter(t -> !newTermHeaders.containsKey(t.getTermGuid()))
                                                                   .collect(Collectors.toSet());

                    createTermRelationships(storeObject, relation, toCreate);
                    updateTermRelationships(relation, toUpdate);
                    deleteTermRelationships(relation, toDelete);

                }
                break;
            case DELETE:
                for (AtlasGlossaryTerm.Relation relation : AtlasGlossaryTerm.Relation.values()) {
                    // No existing term relations, create all
                    Set<AtlasRelatedTermHeader> existingTermHeaders = existingRelatedTerms.get(relation);
                    deleteTermRelationships(relation, existingTermHeaders);
                }
                break;
        }
    }

    private Map<String, AtlasRelatedTermHeader> getRelatedTermHeaders(Map<AtlasGlossaryTerm.Relation, Set<AtlasRelatedTermHeader>> relatedTerms, AtlasGlossaryTerm.Relation relation) {
        if (Objects.nonNull(relatedTerms.get(relation))) {
            Map<String, AtlasRelatedTermHeader> map = new HashMap<>();
            for (AtlasRelatedTermHeader t : relatedTerms.get(relation)) {
                AtlasRelatedTermHeader header = map.get(t.getTermGuid());
                if (header == null || (StringUtils.isEmpty(header.getRelationGuid()) && StringUtils.isNotEmpty(t.getRelationGuid()))) {
                    map.put(t.getTermGuid(), t);
                }
            }
            return map;
        } else {
            return Collections.emptyMap();
        }
    }

    private boolean updatedExistingTermRelation(Map<String, AtlasRelatedTermHeader> existingTermHeaders, AtlasRelatedTermHeader header) {
        return Objects.nonNull(header.getRelationGuid()) && !header.equals(existingTermHeaders.get(header.getTermGuid()));
    }

    private void processAssociatedCategories(AtlasGlossaryTerm storeObject, AtlasGlossaryTerm updatedTerm, RelationshipOperation op) throws AtlasBaseException {
        Map<String, AtlasTermCategorizationHeader> newCategories      = getAssociatedCategories(updatedTerm);
        Map<String, AtlasTermCategorizationHeader> existingCategories = getAssociatedCategories(storeObject);
        switch (op) {
            case CREATE:
                if (Objects.nonNull(newCategories)) {
                    if (DEBUG_ENABLED) {
                        LOG.debug("Creating new term categorization, term = {}, categories = {}", storeObject.getGuid(), newCategories.size());
                    }
                    createTermCategorizationRelationships(storeObject, newCategories.values());
                }
                break;
            case UPDATE:
                // If no existing categories are present then create all existing ones
                if (MapUtils.isEmpty(existingCategories)) {
                    if (DEBUG_ENABLED) {
                        LOG.debug("Creating new term categorization, term = {}, categories = {}", storeObject.getGuid(),
                                  Objects.nonNull(newCategories) ? newCategories.size() : "none");
                    }
                    createTermCategorizationRelationships(storeObject, newCategories.values());
                    break;
                }

                // If no new categories are present then delete all existing ones
                if (MapUtils.isEmpty(newCategories)) {
                    if (DEBUG_ENABLED) {
                        LOG.debug("Deleting term categorization, term = {}, categories = {}", storeObject.getGuid(), existingCategories.size());
                    }
                    deleteCategorizationRelationship(existingCategories.values());
                    break;
                }

                Set<AtlasTermCategorizationHeader> toCreate = newCategories
                                                                      .values()
                                                                      .stream()
                                                                      .filter(c -> !existingCategories.containsKey(c.getCategoryGuid()))
                                                                      .collect(Collectors.toSet());
                createTermCategorizationRelationships(storeObject, toCreate);
                Set<AtlasTermCategorizationHeader> toUpdate = newCategories
                                                                      .values()
                                                                      .stream()
                                                                      .filter(c -> updatedExistingCategorizationRelation(existingCategories, c))
                                                                      .collect(Collectors.toSet());
                updateTermCategorizationRelationships(storeObject, toUpdate);
                Set<AtlasTermCategorizationHeader> toDelete = existingCategories
                                                                      .values()
                                                                      .stream()
                                                                      .filter(c -> !newCategories.containsKey(c.getCategoryGuid()))
                                                                      .collect(Collectors.toSet());
                deleteCategorizationRelationship(toDelete);
                break;
            case DELETE:
                deleteCategorizationRelationship(existingCategories.values());
                break;
        }
    }

    private boolean updatedExistingCategorizationRelation(Map<String, AtlasTermCategorizationHeader> existingCategories, AtlasTermCategorizationHeader header) {
        return Objects.nonNull(header.getRelationGuid()) && !header.equals(existingCategories.get(header.getCategoryGuid()));
    }

    private Map<String, AtlasTermCategorizationHeader> getAssociatedCategories(AtlasGlossaryTerm term) {
        if (Objects.nonNull(term.getCategories())) {
            Map<String, AtlasTermCategorizationHeader> map = new HashMap<>();
            for (AtlasTermCategorizationHeader c : term.getCategories()) {
                AtlasTermCategorizationHeader header = map.get(c.getCategoryGuid());
                if (header == null || (StringUtils.isEmpty(header.getRelationGuid()) && StringUtils.isNotEmpty(c.getRelationGuid()))) {
                    map.put(c.getCategoryGuid(), c);
                }
            }
            return map;
        } else {
            return Collections.emptyMap();
        }
    }

    private void createTermCategorizationRelationships(AtlasGlossaryTerm storeObject, Collection<AtlasTermCategorizationHeader> categories) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(categories)) {
            Set<AtlasTermCategorizationHeader> existingCategories = storeObject.getCategories();
            for (AtlasTermCategorizationHeader categorizationHeader : categories) {
                if (Objects.nonNull(existingCategories) && existingCategories.contains(categorizationHeader)) {
                    if (DEBUG_ENABLED) {
                        LOG.debug("Skipping existing category guid={}", categorizationHeader.getCategoryGuid());
                    }
                    continue;
                }
                if (DEBUG_ENABLED) {
                    LOG.debug("Creating relation between term = {} and category = {}", storeObject.getGuid(), categorizationHeader.getDisplayText());
                }
                createRelationship(defineCategorizedTerm(categorizationHeader, storeObject.getGuid()));
            }
        }
    }

    private void updateTermCategorizationRelationships(AtlasGlossaryTerm storeObject, Collection<AtlasTermCategorizationHeader> toUpdate) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(toUpdate)) {
            for (AtlasTermCategorizationHeader categorizationHeader : toUpdate) {
                if (DEBUG_ENABLED) {
                    LOG.debug("Updating relation between term = {} and category = {}", storeObject.getGuid(), categorizationHeader.getDisplayText());
                }
                AtlasRelationship relationship = relationshipStore.getById(categorizationHeader.getRelationGuid());
                updateRelationshipAttributes(relationship, categorizationHeader);
                relationshipStore.update(relationship);
            }
        }
    }

    private void deleteCategorizationRelationship(Collection<AtlasTermCategorizationHeader> existingCategories) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(existingCategories)) {
            for (AtlasTermCategorizationHeader categorizationHeader : existingCategories) {
                if (DEBUG_ENABLED) {
                    LOG.debug("Deleting relation guid = {}, text = {}", categorizationHeader.getRelationGuid(), categorizationHeader.getDisplayText());
                }
                relationshipStore.deleteById(categorizationHeader.getRelationGuid(), true);
            }
        }
    }

    private void createTermRelationships(AtlasGlossaryTerm storeObject, AtlasGlossaryTerm.Relation relation, Collection<AtlasRelatedTermHeader> terms) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(terms)) {
            Map<String, AtlasRelatedTermHeader> existingRelations;
            if (Objects.nonNull(storeObject.getRelatedTerms()) && Objects.nonNull(storeObject.getRelatedTerms().get(relation))) {
                Map<String, AtlasRelatedTermHeader> map = new HashMap<>();
                for (AtlasRelatedTermHeader t : storeObject.getRelatedTerms().get(relation)) {
                    AtlasRelatedTermHeader header = map.get(t.getTermGuid());
                    if (header == null || (StringUtils.isEmpty(header.getRelationGuid()) && StringUtils.isNotEmpty(t.getRelationGuid()))) {
                        map.put(t.getTermGuid(), t);
                    }
                }
                existingRelations = map;
            } else {
                existingRelations = Collections.emptyMap();
            }
            for (AtlasRelatedTermHeader term : terms) {
                if (existingRelations.containsKey(term.getTermGuid())) {
                    if (DEBUG_ENABLED) {
                        LOG.debug("Skipping existing term relation termGuid={}", term.getTermGuid());
                    }
                    continue;
                }

                if (storeObject.getGuid().equals(term.getTermGuid())) {
                    throw new AtlasBaseException(AtlasErrorCode.INVALID_TERM_RELATION_TO_SELF);
                }

                if (DEBUG_ENABLED) {
                    LOG.debug("Creating new term relation = {}, terms = {}", relation, term.getDisplayText());
                }

                createRelationship(defineTermRelation(relation, storeObject.getGuid(), term));
            }
        }
    }

    private void updateTermRelationships(AtlasGlossaryTerm.Relation relation, Collection<AtlasRelatedTermHeader> terms) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(terms)) {
            for (AtlasRelatedTermHeader term : terms) {
                if (DEBUG_ENABLED) {
                    LOG.debug("Updating term relation = {}, terms = {}", relation, term.getDisplayText());
                }
                AtlasRelationship relationship = relationshipStore.getById(term.getRelationGuid());
                updateRelationshipAttributes(relationship, term);
                relationshipStore.update(relationship);
            }
        }
    }

    private void deleteTermRelationships(AtlasGlossaryTerm.Relation relation, Collection<AtlasRelatedTermHeader> terms) throws AtlasBaseException {
        if (CollectionUtils.isNotEmpty(terms)) {
            for (AtlasRelatedTermHeader termHeader : terms) {
                if (DEBUG_ENABLED) {
                    LOG.debug("Deleting term relation = {}, terms = {}", relation, termHeader.getDisplayText());
                }
                relationshipStore.deleteById(termHeader.getRelationGuid(), true);
            }
        }
    }

    private AtlasRelationship defineTermAnchorRelation(String glossaryGuid, String termGuid) {
        AtlasRelationshipType relationshipType = typeRegistry.getRelationshipTypeByName(TERM_ANCHOR);
        AtlasStruct           defaultAttrs     = relationshipType.createDefaultValue();

        return new AtlasRelationship(TERM_ANCHOR, new AtlasObjectId(glossaryGuid), new AtlasObjectId(termGuid), defaultAttrs.getAttributes());
    }

    private AtlasRelationship defineTermRelation(AtlasGlossaryTerm.Relation relation, String end1TermGuid, AtlasRelatedTermHeader end2RelatedTerm) {
        AtlasRelationshipType relationshipType = typeRegistry.getRelationshipTypeByName(relation.getName());
        AtlasStruct           defaultAttrs     = relationshipType.createDefaultValue();

        AtlasRelationship relationship;
        // End1 and End2 ObjectIds depend on the attribute
        if (relation.isEnd2Attr()) {
            relationship = new AtlasRelationship(relation.getName(), new AtlasObjectId(end2RelatedTerm.getTermGuid()), new AtlasObjectId(end1TermGuid), defaultAttrs.getAttributes());
        } else {
            relationship = new AtlasRelationship(relation.getName(), new AtlasObjectId(end1TermGuid), new AtlasObjectId(end2RelatedTerm.getTermGuid()), defaultAttrs.getAttributes());
        }

        updateRelationshipAttributes(relationship, end2RelatedTerm);
        return relationship;
    }

    private AtlasRelationship defineCategorizedTerm(AtlasTermCategorizationHeader relatedCategoryId, String termId) {
        AtlasRelationshipType relationshipType = typeRegistry.getRelationshipTypeByName(TERM_CATEGORIZATION);
        AtlasStruct           defaultAttrs     = relationshipType.createDefaultValue();

        AtlasRelationship relationship = new AtlasRelationship(TERM_CATEGORIZATION, new AtlasObjectId(relatedCategoryId.getCategoryGuid()), new AtlasObjectId(termId), defaultAttrs.getAttributes());
        updateRelationshipAttributes(relationship, relatedCategoryId);
        return relationship;
    }

    private AtlasRelationship defineTermAssignment(String termGuid, AtlasRelatedObjectId relatedObjectId) {
        AtlasRelationshipType relationshipType = typeRegistry.getRelationshipTypeByName(TERM_ASSIGNMENT);
        AtlasStruct           defaultAttrs     = relationshipType.createDefaultValue();

        AtlasObjectId     end1         = new AtlasObjectId(termGuid);
        AtlasRelationship relationship = new AtlasRelationship(TERM_ASSIGNMENT, end1, relatedObjectId, defaultAttrs.getAttributes());
        updateRelationshipAttributes(relationship, relatedObjectId);
        return relationship;
    }

    private void updateRelationshipAttributes(AtlasRelationship relationship, AtlasTermCategorizationHeader categorizationHeader) {
        if (Objects.nonNull(relationship)) {
            relationship.setAttribute(TERM_RELATION_ATTR_DESCRIPTION, categorizationHeader.getDescription());
            if (Objects.nonNull(categorizationHeader.getStatus())) {
                relationship.setAttribute(TERM_RELATION_ATTR_STATUS, categorizationHeader.getStatus().name());
            }
        }
    }

    private void updateRelationshipAttributes(AtlasRelationship relationship, AtlasRelatedObjectId relatedObjectId) {
        AtlasStruct relationshipAttributes = relatedObjectId.getRelationshipAttributes();
        if (Objects.nonNull(relationshipAttributes)) {
            for (Map.Entry<String, Object> attrEntry : relationshipAttributes.getAttributes().entrySet()) {
                relationship.setAttribute(attrEntry.getKey(), attrEntry.getValue());
            }
        }
    }

    protected List<AtlasGlossaryTerm> getGlossaryTermDataList(List<String[]> fileData, BulkImportResponse bulkImportResponse) throws AtlasBaseException {
        return getGlossaryTermDataList(fileData, bulkImportResponse, false);
    }

    protected List<AtlasGlossaryTerm> getGlossaryTermDataList(List<String[]> fileData, BulkImportResponse bulkImportResponse, boolean processRelations) throws AtlasBaseException {
        List<AtlasGlossaryTerm> glossaryTerms = new ArrayList<>();
        int                     rowCount      = 1;

        for (String[] record : fileData) {
            List<String>      failedTermMsgs = new ArrayList<>();
            AtlasGlossaryTerm glossaryTerm   = new AtlasGlossaryTerm();
            String            glossaryName   = StringUtils.EMPTY;

            if ((record.length < 1) || StringUtils.isBlank(record[0])) {
                LOG.error("The GlossaryName is blank for the record : ", Arrays.toString(record));
                failedTermMsgs.add("The GlossaryName is blank for the record : " + Arrays.toString(record));
            } else {
                String glossaryGuid;
                glossaryName = record[0];

                if (glossaryNameGuidCacheForImport.get(glossaryName) != null) {
                    glossaryGuid = glossaryNameGuidCacheForImport.get(glossaryName);

                } else {
                    AtlasVertex vertex = AtlasGraphUtilsV2.findByTypeAndUniquePropertyName(GlossaryUtils.ATLAS_GLOSSARY_TYPENAME, GlossaryUtils.ATLAS_GLOSSARY_TYPENAME + "." + QUALIFIED_NAME_ATTR, glossaryName);

                    glossaryGuid = (vertex != null) ? AtlasGraphUtilsV2.getIdFromVertex(vertex) : null;
                }

                if (glossaryGuid == null) {
                    if (GlossaryService.isNameInvalid(glossaryName)) {
                        LOG.error("The provided Glossary Name is invalid : " + glossaryName);
                        failedTermMsgs.add("The provided Glossary Name is invalid : " + glossaryName);
                    } else {
                        AtlasGlossary glossary = new AtlasGlossary();
                        glossary.setQualifiedName(glossaryName);
                        glossary.setName(glossaryName);

                        glossary      = dataAccess.save(glossary);
                        glossaryGuid  = glossary.getGuid();
                    }
                }

                if (glossaryGuid != null) {
                    glossaryNameGuidCacheForImport.put(glossaryName, glossaryGuid);

                    glossaryTerm = populateGlossaryTermObject(failedTermMsgs, record, glossaryGuid, processRelations);

                    glossaryTerm.setQualifiedName(getGlossaryTermQualifiedName(glossaryTerm.getName(), glossaryGuid));

                    glossaryTerms.add(glossaryTerm);
                }
            }

            if (failedTermMsgs.size() > 0) {
                String failedTermMsg    = StringUtils.join(failedTermMsgs, "\n");
                String glossaryTermName = glossaryTerm.getName();

                bulkImportResponse.addToFailedImportInfoList(new ImportInfo(glossaryName, glossaryTermName, FAILED, failedTermMsg, rowCount));
            }

            rowCount++;
        }

        return glossaryTerms;
    }

    public static String getGlossaryTermHeaders() {
        List<String> ret = new ArrayList<>();

        ret.add("GlossaryName");
        ret.add("TermName");
        ret.add("ShortDescription");
        ret.add("LongDescription");
        ret.add("Examples");
        ret.add("Abbreviation");
        ret.add("Usage");
        ret.add("AdditionalAttributes");
        ret.add("TranslationTerms");
        ret.add("ValidValuesFor");
        ret.add("Synonyms");
        ret.add("ReplacedBy");
        ret.add("ValidValues");
        ret.add("ReplacementTerms");
        ret.add("SeeAlso");
        ret.add("TranslatedTerms");
        ret.add("IsA");
        ret.add("Antonyms");
        ret.add("Classifies");
        ret.add("PreferredToTerms");
        ret.add("PreferredTerms");

        return String.join(", ", ret);
    }

    protected Map getMapValue(String csvRecord, List<String> failedTermMsgs) {
        Map ret = null;

        if (StringUtils.isNotBlank(csvRecord)) {
            ret                     = new HashMap<>();
            String csvRecordArray[] = csvRecord.split(FileUtils.ESCAPE_CHARACTER + FileUtils.PIPE_CHARACTER);
            String recordArray[];

            for (String record : csvRecordArray) {
                recordArray = record.split(FileUtils.COLON_CHARACTER);

                if ((recordArray.length % 2) == 0) {
                    ret.put(recordArray[0], recordArray[1]);
                } else {
                    failedTermMsgs.add("\n" + "The Data in the uploaded file is incorrectly specified  : " + csvRecord
                            + "\n" + "AdditionalAttributes needs to be a key:value pair");
                }
            }
        }

        return ret;
    }

    protected List getListValue(String csvRecord) {
        List ret = null;

        if (StringUtils.isNotBlank(csvRecord)) {
            ret =  Arrays.asList(csvRecord.split(FileUtils.ESCAPE_CHARACTER + FileUtils.PIPE_CHARACTER));
        }

        return ret;
    }

    protected Set getAtlasRelatedTermHeaderSet(String csvRecord, String termName, String glossaryName, List<String> failedTermMsgs) {
        Set ret = null;

        if (StringUtils.isNotBlank(csvRecord)) {
            ret                                     = new HashSet();
            String                 csvRecordArray[] = csvRecord.split(FileUtils.ESCAPE_CHARACTER + FileUtils.PIPE_CHARACTER);
            AtlasRelatedTermHeader relatedTermHeader;

            for (String data : csvRecordArray) {
                AtlasVertex vertex      = null;
                String      dataArray[] = data.split(FileUtils.ESCAPE_CHARACTER + FileUtils.COLON_CHARACTER);

                if ((dataArray.length % 2) == 0) {
                    vertex = AtlasGraphUtilsV2.findByTypeAndUniquePropertyName(GlossaryUtils.ATLAS_GLOSSARY_TERM_TYPENAME,
                            GlossaryUtils.ATLAS_GLOSSARY_TERM_TYPENAME + invalidNameChars[1] + QUALIFIED_NAME_ATTR, dataArray[1] + invalidNameChars[0] + dataArray[0]);
                } else {
                    failedTermMsgs.add("\n" + "Either incorrect data specified for Term or Term does not exist : " +termName);
                }

                if (vertex != null) {
                    String glossaryTermGuid = AtlasGraphUtilsV2.getIdFromVertex(vertex);
                    relatedTermHeader       = new AtlasRelatedTermHeader();
                    relatedTermHeader.setTermGuid(glossaryTermGuid);
                    ret.add(relatedTermHeader);
                } else {
                    failedTermMsgs.add(System.lineSeparator() + "The provided Reference Glossary and TermName does not exist in the system " +
                            dataArray[1] + FileUtils.COLON_CHARACTER + dataArray[0] + " for record with TermName  : " + termName + " and GlossaryName : " + glossaryName);
                }
            }
        }

        return ret;
    }

    protected AtlasGlossaryTerm populateGlossaryTermObject(List<String> failedTermMsgList, String[] record, String glossaryGuid, boolean populateRelations) {
        AtlasGlossaryTerm ret    = new AtlasGlossaryTerm();
        int               i      = 0;
        int               length = record.length;

        ret.setName((length > ++i) ? record[i] : null);

        if (!StringUtils.isNotBlank(ret.getName())) {
            failedTermMsgList.add("\n" + "The TermName is blank for provided record: " + Arrays.toString(record));
        } else {
            ret.setShortDescription((length > ++i) ? record[i] : null);

            ret.setLongDescription((length > ++i) ? record[i] : null);

            ret.setExamples((length > ++i) ? (List<String>) getListValue(record[i]) : null);

            ret.setAbbreviation((length > ++i) ? record[i] : null);

            ret.setUsage((length > ++i) ? record[i] : null);

            ret.setAdditionalAttributes(((length > ++i) ? (Map<String, Object>) getMapValue(record[i], failedTermMsgList) : null));

            ret.setAnchor(new AtlasGlossaryHeader(glossaryGuid));

            if (populateRelations) {
                ret.setTranslationTerms((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setValidValuesFor((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setSynonyms((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setReplacedBy((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setValidValues((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setReplacementTerms((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setSeeAlso((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setTranslatedTerms((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setIsA((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setAntonyms((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setClassifies((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setPreferredToTerms((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);

                ret.setPreferredTerms((length > ++i) ? (Set<AtlasRelatedTermHeader>) getAtlasRelatedTermHeaderSet(record[i], ret.getName(), record[0], failedTermMsgList) : null);
            }
        }

        return ret;
    }

    private String getGlossaryTermQualifiedName(String glossaryTermName, String glossaryGuid) throws AtlasBaseException {
        AtlasGlossary glossary = dataAccess.load(getGlossarySkeleton(glossaryGuid));

        return glossaryTermName + "@" + glossary.getQualifiedName();
    }
}
