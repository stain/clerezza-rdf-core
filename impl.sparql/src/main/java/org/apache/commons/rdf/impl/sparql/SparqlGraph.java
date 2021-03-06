/*
 * Copyright 2015 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.rdf.impl.sparql;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import org.apache.commons.rdf.BlankNode;
import org.apache.commons.rdf.BlankNodeOrIri;
import org.apache.commons.rdf.Graph;
import org.apache.commons.rdf.ImmutableGraph;
import org.apache.commons.rdf.Iri;
import org.apache.commons.rdf.Literal;
import org.apache.commons.rdf.RdfTerm;
import org.apache.commons.rdf.Triple;
import org.apache.commons.rdf.impl.utils.AbstractGraph;
import org.apache.commons.rdf.impl.utils.TripleImpl;
import org.apache.commons.rdf.impl.utils.simple.SimpleGraph;

/**
 *
 * @author reto
 */
public class SparqlGraph extends AbstractGraph {

    private static final int MAX_ISOMORPHIC_BNODES = 1000;
    private static final Logger log = Logger.getLogger(SparqlGraph.class.getName());

    final SparqlClient sparqlClient;

    /**
     * Constructs a Graph representing the default graph at the specified
     * endpoint
     */
    public SparqlGraph(final String endpoint) {
        sparqlClient = new SparqlClient(endpoint);
    }

    @Override
    protected Iterator<Triple> performFilter(final BlankNodeOrIri filterSubject,
            final Iri filterPredicate, final RdfTerm filterObject) {
        try {
            String query = createQuery(filterSubject, filterPredicate, filterObject);
            final List<Map<String, RdfTerm>> sparqlResults = sparqlClient.queryResultSet(query);
            //first to triples without bnode-conversion
            //rawTriples contains the triples with the BNodes from the result set
            final Collection<Triple> rawTriples = new ArrayList<>();
            for (Map<String, RdfTerm> result : sparqlResults) {
                rawTriples.add(new TripleImpl(filterSubject != null ? filterSubject : (BlankNodeOrIri) result.get("s"),
                        filterPredicate != null ? filterPredicate : (Iri) result.get("p"),
                        filterObject != null ? filterObject : result.get("o")));

            }
            //then bnode conversion
            final Iterator<Triple> rawTriplesIter = rawTriples.iterator();
            //this is basically just wokring around the lack of (named) nested functions
            return (new Callable<Iterator<Triple>>() {

                final Map<BlankNode, SparqlBNode> nodeMap = new HashMap<>();
                final Set<ImmutableGraph> usedContext = new HashSet<>();

                private RdfTerm useSparqlNode(RdfTerm node) throws IOException {
                    if (node instanceof BlankNodeOrIri) {
                        return useSparqlNode((BlankNodeOrIri) node);
                    }
                    return node;
                }

                private BlankNodeOrIri useSparqlNode(BlankNodeOrIri node) throws IOException {
                    if (node instanceof BlankNode) {
                        if (!nodeMap.containsKey(node)) {
                            createBlankNodesForcontext((BlankNode) node);
                        }
                        if (!nodeMap.containsKey(node)) {
                            throw new RuntimeException("no Bnode created");
                        }
                        return nodeMap.get(node);
                    } else {
                        return node;
                    }
                }

                private void createBlankNodesForcontext(final BlankNode node) throws IOException {
                    final Collection<Triple> context = getContext(node);
                    final Set<BlankNode> rawNodes = new HashSet<>();
                    for (Triple triple : context) {
                        {
                            final BlankNodeOrIri subject = triple.getSubject();
                            if (subject instanceof BlankNode) {
                                rawNodes.add((BlankNode) subject);
                            }
                        }
                        {
                            final RdfTerm object = triple.getObject();
                            if (object instanceof BlankNode) {
                                rawNodes.add((BlankNode) object);
                            }
                        }
                    }
                    final Set<SparqlBNode> createdSparqlNodes = new HashSet<>();
                    //final Map<BlankNode, SparqlBNode> preliminaryNodes = new HashMap<>();
                    for (BlankNode rawNode : rawNodes) {
                        for (int i = 0; i < MAX_ISOMORPHIC_BNODES; i++) {
                            SparqlBNode sparqlBNode = new SparqlBNode(rawNode, context, i);
                            if (!createdSparqlNodes.contains(sparqlBNode)) {
                                nodeMap.put(rawNode, sparqlBNode);
                                createdSparqlNodes.add(sparqlBNode);
                                break;
                            }
                        }
                    }
                }

                private ImmutableGraph getContext(final BlankNode node) throws IOException {
                    //we need to get the cntext of the BNode
                    //if the filter was for (null, null, null) we have the whole
                    //bnode context in the reuslt set, otherwise we need to get 
                    //more triples from the endpoint,
                    //let's first handle the easy case
                    if ((filterSubject == null) && (filterPredicate == null)
                            && (filterObject == null)) {
                        return getContextInRaw(node);
                    } else {
                        final ImmutableGraph startContext = getContextInRaw(node);
                        final Set<ImmutableGraph> expandedContexts = expandContext(startContext);
                        //expand bnode context
                        //note that there might be different contexts for 
                        //a bnode as present in the current result set
                        //in this case we just haveto make sure we don't 
                        //pick the same context for different bnodes in the resultset
                        ImmutableGraph result = null;
                        for (ImmutableGraph expandedContext : expandedContexts) {
                            if (!usedContext.contains(expandedContext)) {
                                result = expandedContext;
                                break;
                            }
                        }
                        if (result == null) {
                            log.warning("he underlying sparql graph seems to contain redundant triples, this might cause unexpected results");
                            result = expandedContexts.iterator().next();
                        } else {
                            usedContext.add(result);
                        }
                        return result;
                    }

                }

                private ImmutableGraph getContextInRaw(BlankNode node) {
                    final Graph contextBuilder = new SimpleGraph();
                    for (Triple rawTriple : rawTriples) {
                        BlankNodeOrIri rawSubject = rawTriple.getSubject();
                        RdfTerm rawObject = rawTriple.getObject();
                        if (rawSubject.equals(node) || rawObject.equals(node)) {
                            contextBuilder.add(rawTriple);
                        }
                    }
                    return contextBuilder.getImmutableGraph();
                }

                @Override
                public Iterator<Triple> call() throws Exception {
                    return new Iterator<Triple>() {

                        @Override
                        public boolean hasNext() {
                            return rawTriplesIter.hasNext();
                        }

                        @Override
                        public Triple next() {
                            try {
                                Triple rawTriple = rawTriplesIter.next();
                                return new TripleImpl(useSparqlNode(rawTriple.getSubject()),
                                        rawTriple.getPredicate(),
                                        useSparqlNode(rawTriple.getObject()));
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        }
                    };
                }

                /**
                 * returns all MSGs that are supergraphs of startContext
                 *
                 * @param startContext
                 * @return
                 */
                private Set<ImmutableGraph> expandContext(Collection<Triple> startContext) throws IOException {

                    final StringBuilder queryBuilder = new StringBuilder();
                    queryBuilder.append("SELECT * WHERE {\n ");
                    Map<BlankNode, String> bNodeVarNameMap = writeTriplePattern(queryBuilder, startContext);
                    Set<BlankNode> bNodesInContext = bNodeVarNameMap.keySet();
                    for (BlankNode bNode : bNodesInContext) {
                        final String bNodeVarLabel = bNodeVarNameMap.get(bNode);
                        //looking for outgoing properties of the bnode
                        queryBuilder.append("OPTIONAL { ");
                        queryBuilder.append('?');
                        queryBuilder.append(bNodeVarLabel);
                        queryBuilder.append(' ');
                        queryBuilder.append("?po");
                        queryBuilder.append(bNodeVarLabel);
                        queryBuilder.append(" ?o");
                        queryBuilder.append(bNodeVarLabel);
                        queryBuilder.append(" } .\n");
                        //looking for incoming properties of the bnode
                        queryBuilder.append("OPTIONAL { ");
                        queryBuilder.append("?s");
                        queryBuilder.append(bNodeVarLabel);
                        queryBuilder.append(' ');
                        queryBuilder.append("?pi");
                        queryBuilder.append(bNodeVarLabel);
                        queryBuilder.append(" ?");
                        queryBuilder.append(bNodeVarLabel);
                        queryBuilder.append(" } .\n");
                    }
                    queryBuilder.append(" }");
                    final List<Map<String, RdfTerm>> expansionQueryResults = sparqlClient.queryResultSet(queryBuilder.toString());
                    Set<ImmutableGraph> expandedContexts = new HashSet<>();
                    //the query results may or may be from disjoint supergraphs
                    //we expand them all as if they are different which may lead
                    //us to the same MSG multiple times
                    RESULTS:
                    for (Map<String, RdfTerm> expansionQueryResult : expansionQueryResults) {
                        Collection<Triple> expandedContext = new HashSet<>();
                        Map<BlankNode, BlankNode> newBNodesToOldBNodes = new HashMap<>();
                        for (BlankNode oldBNode : bNodesInContext) {
                            final String bNodeVarLabel = bNodeVarNameMap.get(oldBNode);
                            final RdfTerm newNode = expansionQueryResult.get(bNodeVarLabel);
                            if (!(newNode instanceof BlankNode)) {
                                //this subgraph is't a match
                                continue RESULTS;
                            }
                            newBNodesToOldBNodes.put((BlankNode) newNode, oldBNode);
                        }
                        expandedContext.addAll(startContext);
                        boolean newBNodeIntroduced = false;
                        boolean newTripleAdded = false;
                        for (BlankNode oldBNode : bNodesInContext) {
                            final String bNodeVarLabel = bNodeVarNameMap.get(oldBNode);
                            {
                                final Iri newPredicate = (Iri) expansionQueryResult.get("po" + bNodeVarLabel);
                                if (newPredicate != null) {
                                    RdfTerm newObject = expansionQueryResult.get("o" + bNodeVarLabel);
                                    if (newObject instanceof BlankNode) {
                                        if (newBNodesToOldBNodes.containsKey(newObject)) {
                                            //point back to BNode in startContext
                                            newObject = newBNodesToOldBNodes.get(newObject);
                                        } else {
                                            newBNodeIntroduced = true;
                                        }
                                    }
                                    if (expandedContext.add(new TripleImpl(oldBNode, newPredicate, newObject))) {
                                        newTripleAdded = true;
                                    }
                                }
                            }
                            {
                                final Iri newPredicate = (Iri) expansionQueryResult.get("pi" + bNodeVarLabel);
                                if (newPredicate != null) {
                                    RdfTerm newSubject = expansionQueryResult.get("s" + bNodeVarLabel);
                                    if (newSubject instanceof BlankNode) {
                                        if (newBNodesToOldBNodes.containsKey(newSubject)) {
                                            //point back to BNode in startContext
                                            newSubject = newBNodesToOldBNodes.get(newSubject);
                                        } else {
                                            newBNodeIntroduced = true;
                                        }
                                    }
                                    if (expandedContext.add(new TripleImpl((BlankNodeOrIri) newSubject, newPredicate, oldBNode))) {
                                        newTripleAdded = true;
                                    }
                                }
                            }
                        }
                        if (newBNodeIntroduced) {
                            //we could be more efficient than this ans just expand the newly introduced bnodes
                            expandedContexts.addAll(expandContext(expandedContext));
                        } else {
                            if (newTripleAdded) {
                                //look for more results
                                expandedContexts.addAll(expandContext(expandedContext));
                                //expandedContexts.add(expandedContext);
                            }
                        }

                    }
                    if (expandedContexts.isEmpty()) {
                        expandedContexts.add(new SimpleGraph(startContext).getImmutableGraph());
                    }
                    return expandedContexts;
                }

            }).call();
        } catch (AlienBNodeException e) {
            return new Iterator<Triple>() {

                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public Triple next() {
                    throw new NoSuchElementException();
                }
            };
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private String createQuery(final BlankNodeOrIri filterSubject, final Iri filterPredicate, final RdfTerm filterObject) {
        final StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT ?s ?p ?o WHERE { ");
        if (filterSubject == null) {
            queryBuilder.append("?s");
        } else {
            if (filterSubject instanceof SparqlBNode) {
                queryBuilder.append("?sn");
            } else {
                queryBuilder.append(asSparqlTerm(filterSubject));
            }
        }
        queryBuilder.append(' ');
        if (filterPredicate == null) {
            queryBuilder.append("?p");
        } else {
            queryBuilder.append(asSparqlTerm(filterPredicate));
        }
        queryBuilder.append(' ');
        if (filterObject == null) {
            queryBuilder.append("?o");
        } else {
            if (filterObject instanceof SparqlBNode) {
                queryBuilder.append("?on");
            } else {
                queryBuilder.append(asSparqlTerm(filterObject));
            }
        }
        queryBuilder.append(" .\n");
        if (filterSubject instanceof SparqlBNode) {
            //expand bnode context
            writeTriplePattern(queryBuilder, ((SparqlBNode) filterSubject).context, "sn");
        }
        
        if (filterObject instanceof SparqlBNode) {
            //expand bnode context
            writeTriplePattern(queryBuilder, ((SparqlBNode) filterObject).context, "on");
        }

        queryBuilder.append(" }");
        return queryBuilder.toString();
    }

    @Override
    protected int performSize() {
        try {
            return sparqlClient.queryResultSet("SELECT * WHERE { ?s ?p ?o}").size();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private String asSparqlTerm(Iri iri) {
        return "<" + iri.getUnicodeString() + ">";
    }

    private String asSparqlTerm(Literal literal) {
        //TODO langauge and datatype
        return "\"" + literal.getLexicalForm() + "\"";
    }

    private String asSparqlTerm(BlankNode bnode) {
        if (!(bnode instanceof SparqlBNode)) {
            throw new AlienBNodeException();
        }
        //this requires adding additional clauses to the graph pattern
        throw new RuntimeException("SparqlBNodes should have been handled earlier");
    }

    private String asSparqlTerm(BlankNodeOrIri term) {
        if (term instanceof Iri) {
            return asSparqlTerm((Iri) term);
        } else {
            return asSparqlTerm((BlankNode) term);
        }
    }

    private String asSparqlTerm(RdfTerm term) {
        if (term instanceof BlankNodeOrIri) {
            return asSparqlTerm((BlankNodeOrIri) term);
        } else {
            return asSparqlTerm((Literal) term);
        }
    }


    private Map<BlankNode, String> writeTriplePattern(StringBuilder queryBuilder, Collection<Triple> triples) {
        return writeTriplePattern(queryBuilder, triples, null);
    }
        
    private Map<BlankNode, String> writeTriplePattern(StringBuilder queryBuilder, Collection<Triple> triples, String varLabelForInternalBNodeId) {
        final Collection<String> triplePatterns = new ArrayList<>();
        int varCounter = 0;
        final Map<BlankNode, String> bNodeVarNameMap = new HashMap<>();
        for (Triple t : triples) {
            final StringBuilder builder = new StringBuilder();
            {
                final BlankNodeOrIri s = t.getSubject();
                String varName;
                if (s instanceof BlankNode) {
                    if (bNodeVarNameMap.containsKey(s)) {
                        varName = bNodeVarNameMap.get(s);
                    } else {
                        varName = "v" + (varCounter++);
                        bNodeVarNameMap.put((BlankNode) s, varName);
                    }
                    builder.append('?');
                    builder.append(varName);
                } else {
                    if (s.equals(SparqlBNode.internalBNodeId)) {
                        builder.append('?');
                        builder.append(varLabelForInternalBNodeId);
                    } else {
                        builder.append(asSparqlTerm(s));
                    }
                    
                }
            }
            builder.append(' ');
            builder.append(asSparqlTerm(t.getPredicate()));
            builder.append(' ');
            {
                final RdfTerm o = t.getObject();
                String varName;
                if (o instanceof BlankNode) {
                    if (bNodeVarNameMap.containsKey(o)) {
                        varName = bNodeVarNameMap.get(o);
                    } else {
                        varName = "v" + (varCounter++);
                        bNodeVarNameMap.put((BlankNode) o, varName);
                    }
                    builder.append('?');
                    builder.append(varName);
                } else {
                    if (o.equals(SparqlBNode.internalBNodeId)) {
                        builder.append('?');
                        builder.append(varLabelForInternalBNodeId);
                    } else {
                        builder.append(asSparqlTerm(o));
                    }
                }
            }
            builder.append('.');
            triplePatterns.add(builder.toString());

        }
        for (String triplePattern : triplePatterns) {

            queryBuilder.append(triplePattern);
            queryBuilder.append('\n');
        }
        return bNodeVarNameMap;

    }

    private static class AlienBNodeException extends RuntimeException {

        public AlienBNodeException() {
        }
    }

}
