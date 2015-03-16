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
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.github.commonsrdf.api.BlankNode;
import com.github.commonsrdf.api.BlankNodeOrIRI;
import com.github.commonsrdf.api.Graph;
import com.github.commonsrdf.api.IRI;
import com.github.commonsrdf.api.Literal;
import com.github.commonsrdf.api.RDFTerm;
import com.github.commonsrdf.api.Triple;
import com.github.commonsrdf.simple.SimpleRDFTermFactory;

/**
 *
 * @author reto
 */
public class SparqlGraph implements Graph {

    private static final int MAX_ISOMORPHIC_BNODES = 1000;
    private static final Logger log = Logger.getLogger(SparqlGraph.class.getName());

    private static SimpleRDFTermFactory factory = new SimpleRDFTermFactory();
    
    final SparqlClient sparqlClient;

    /**
     * Constructs a Graph representing the default graph at the specified
     * endpoint
     */
    public SparqlGraph(final String endpoint) {
        sparqlClient = new SparqlClient(endpoint);
    }

    
    
    protected Iterator<Triple> performFilter(final BlankNodeOrIRI filterSubject,
            final IRI filterPredicate, final RDFTerm filterObject) {
        try {
            String query = createQuery(filterSubject, filterPredicate, filterObject);
            final List<Map<String, RDFTerm>> sparqlResults = sparqlClient.queryResultSet(query);
            //first to triples without bnode-conversion
            //rawTriples contains the triples with the BNodes from the result set
            final Collection<Triple> rawTriples = new ArrayList<>();
            for (Map<String, RDFTerm> result : sparqlResults) {
                rawTriples.add(factory.createTriple(filterSubject != null ? filterSubject : (BlankNodeOrIRI) result.get("s"),
                        filterPredicate != null ? filterPredicate : (IRI) result.get("p"),
                        filterObject != null ? filterObject : result.get("o")));

            }
            //then bnode conversion
            final Iterator<Triple> rawTriplesIter = rawTriples.iterator();
            //this is basically just wokring around the lack of (named) nested functions
            return (new Callable<Iterator<Triple>>() {

                final Map<BlankNode, SparqlBNode> nodeMap = new HashMap<>();
                final Set<Graph> usedContext = new HashSet<>();

                private RDFTerm useSparqlNode(RDFTerm node) throws IOException {
                    if (node instanceof BlankNodeOrIRI) {
                        return useSparqlNode((BlankNodeOrIRI) node);
                    }
                    return node;
                }

                private BlankNodeOrIRI useSparqlNode(BlankNodeOrIRI node) throws IOException {
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
                    final Graph context = getContext(node);
					
					Stream<BlankNodeOrIRI> subjects = context.getTriples().map(t -> t.getSubject());
					Stream<Object> objects = context.getTriples().map(t -> t.getObject());
					Stream<Object> candidates = Stream.concat(subjects, objects);
					Stream<BlankNode> bnodes = candidates.filter(n -> n instanceof BlankNode)
							.map(BlankNode.class::cast);
					
                    final Set<SparqlBNode> createdSparqlNodes = new HashSet<>();
                    //final Map<BlankNode, SparqlBNode> preliminaryNodes = new HashMap<>();
                    
                    bnodes.distinct().forEach(rawNode -> {
                        for (int i = 0; i < MAX_ISOMORPHIC_BNODES; i++) {
                            SparqlBNode sparqlBNode = new SparqlBNode(rawNode, context, i);
                            if (!createdSparqlNodes.contains(sparqlBNode)) {
                                nodeMap.put(rawNode, sparqlBNode);
                                createdSparqlNodes.add(sparqlBNode);
                                break;
                            }
                        }
                    });
                }

                private Graph getContext(final BlankNode node) throws IOException {
                    //we need to get the cntext of the BNode
                    //if the filter was for (null, null, null) we have the whole
                    //bnode context in the reuslt set, otherwise we need to get 
                    //more triples from the endpoint,
                    //let's first handle the easy case
                    if ((filterSubject == null) && (filterPredicate == null)
                            && (filterObject == null)) {
                        return getContextInRaw(node);
                    } else {
                        final Graph startContext = getContextInRaw(node);
                        final Set<Graph> expandedContexts = expandContext(startContext);
                        //expand bnode context
                        //note that there might be different contexts for 
                        //a bnode as present in the current result set
                        //in this case we just haveto make sure we don't 
                        //pick the same context for different bnodes in the resultset
                        Graph result = null;
                        for (Graph expandedContext : expandedContexts) {
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

                private Graph getContextInRaw(BlankNode node) {
                    final Graph contextBuilder = factory.createGraph();
                    for (Triple rawTriple : rawTriples) {
                        BlankNodeOrIRI rawSubject = rawTriple.getSubject();
                        RDFTerm rawObject = rawTriple.getObject();
                        if (rawSubject.equals(node) || rawObject.equals(node)) {
                            contextBuilder.add(rawTriple);
                        }
                    }
                    return contextBuilder; // FIXME: No immutable graphs
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
                                return factory.createTriple(useSparqlNode(rawTriple.getSubject()),
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
                private Set<Graph> expandContext(Graph startContext) throws IOException {

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
                    final List<Map<String, RDFTerm>> expansionQueryResults = sparqlClient.queryResultSet(queryBuilder.toString());
                    Set<Graph> expandedContexts = new HashSet<>();
                    //the query results may or may be from disjoint supergraphs
                    //we expand them all as if they are different which may lead
                    //us to the same MSG multiple times
                    RESULTS:
                    for (Map<String, RDFTerm> expansionQueryResult : expansionQueryResults) {
                        Graph expandedContext = factory.createGraph();
                        Map<BlankNode, BlankNode> newBNodesToOldBNodes = new HashMap<>();
                        for (BlankNode oldBNode : bNodesInContext) {
                            final String bNodeVarLabel = bNodeVarNameMap.get(oldBNode);
                            final RDFTerm newNode = expansionQueryResult.get(bNodeVarLabel);
                            if (!(newNode instanceof BlankNode)) {
                                //this subgraph is't a match
                                continue RESULTS;
                            }
                            newBNodesToOldBNodes.put((BlankNode) newNode, oldBNode);
                        }
                        
                        startContext.getTriples().forEach(t -> expandedContext.add(t));
                        //expandedContext.addAll(startContext);
                        boolean newBNodeIntroduced = false;
                        boolean newTripleAdded = false;
                        for (BlankNode oldBNode : bNodesInContext) {
                            final String bNodeVarLabel = bNodeVarNameMap.get(oldBNode);
                            {
                                final IRI newPredicate = (IRI) expansionQueryResult.get("po" + bNodeVarLabel);
                                if (newPredicate != null) {
                                    RDFTerm newObject = expansionQueryResult.get("o" + bNodeVarLabel);
                                    if (newObject instanceof BlankNode) {
                                        if (newBNodesToOldBNodes.containsKey(newObject)) {
                                            //point back to BNode in startContext
                                            newObject = newBNodesToOldBNodes.get(newObject);
                                        } else {
                                            newBNodeIntroduced = true;
                                        }
                                    }
                                    Triple triple = factory.createTriple(oldBNode, newPredicate, newObject);
                                    if (! expandedContext.contains(triple)) {
                                    	expandedContext.add(triple);
                                    	newTripleAdded = true;
                                    }
                                }
                            }
                            {
                                final IRI newPredicate = (IRI) expansionQueryResult.get("pi" + bNodeVarLabel);
                                if (newPredicate != null) {
                                    RDFTerm newSubject = expansionQueryResult.get("s" + bNodeVarLabel);
                                    if (newSubject instanceof BlankNode) {
                                        if (newBNodesToOldBNodes.containsKey(newSubject)) {
                                            //point back to BNode in startContext
                                            newSubject = newBNodesToOldBNodes.get(newSubject);
                                        } else {
                                            newBNodeIntroduced = true;
                                        }
                                    }
                                    Triple triple = factory.createTriple((BlankNodeOrIRI) newSubject, newPredicate, oldBNode);
                                    if (! expandedContext.contains(triple)) { 
                                    	expandedContext.add(triple);
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
                    	Graph g = factory.createGraph();
                    	startContext.getTriples().forEach(t -> g.add(t));
                        expandedContexts.add(g);
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

    private String createQuery(final BlankNodeOrIRI filterSubject, final IRI filterPredicate, final RDFTerm filterObject) {
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
    public long size() {
        try {
            return sparqlClient.queryResultSet("SELECT * WHERE { ?s ?p ?o}").size();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private String asSparqlTerm(IRI IRI) {
        return "<" + IRI.getIRIString() + ">";
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

    private String asSparqlTerm(BlankNodeOrIRI term) {
        if (term instanceof IRI) {
            return asSparqlTerm((IRI) term);
        } else {
            return asSparqlTerm((BlankNode) term);
        }
    }

    private String asSparqlTerm(RDFTerm term) {
        if (term instanceof BlankNodeOrIRI) {
            return asSparqlTerm((BlankNodeOrIRI) term);
        } else {
            return asSparqlTerm((Literal) term);
        }
    }


    private Map<BlankNode, String> writeTriplePattern(StringBuilder queryBuilder, Graph triples) {
        return writeTriplePattern(queryBuilder, triples, null);
    }
        
    private Map<BlankNode, String> writeTriplePattern(StringBuilder queryBuilder, Graph triples, String varLabelForInternalBNodeId) {
        final Collection<String> triplePatterns = new ArrayList<>();
        final AtomicLong varCounter = new AtomicLong();
        final Map<BlankNode, String> bNodeVarNameMap = new HashMap<>();
        // FIXME: To rewrite as collector
        triples.getTriples().forEach(t -> {
            final StringBuilder builder = new StringBuilder();
            {
                final BlankNodeOrIRI s = t.getSubject();
                String varName;
                if (s instanceof BlankNode) {
                    if (bNodeVarNameMap.containsKey(s)) {
                        varName = bNodeVarNameMap.get(s);
                    } else {
                        varName = "v" + (varCounter.incrementAndGet());
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
                final RDFTerm o = t.getObject();
                String varName;
                if (o instanceof BlankNode) {
                    if (bNodeVarNameMap.containsKey(o)) {
                        varName = bNodeVarNameMap.get(o);
                    } else {
                        varName = "v" + (varCounter.incrementAndGet());
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

        });
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

	@Override
	public void close() throws Exception {
		
	}



	@Override
	public void add(Triple triple) {
		throw new UnsupportedOperationException("SparqlGraph is read-only"); 
		
	}



	@Override
	public void add(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
		throw new UnsupportedOperationException("SparqlGraph is read-only");
		
	}



	@Override
	public boolean contains(Triple triple) {
		return getTriples().anyMatch(t -> t.equals(triple));
	}



	@Override
	public boolean contains(BlankNodeOrIRI subject, IRI predicate,
			RDFTerm object) {
		return getTriples(subject, predicate, object).findAny().isPresent();
	}



	@Override
	public void remove(Triple triple) {
		throw new UnsupportedOperationException("SparqlGraph is read-only");
		
	}



	@Override
	public void remove(BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
		throw new UnsupportedOperationException("SparqlGraph is read-only");
		
	}



	@Override
	public void clear() {
		throw new UnsupportedOperationException("SparqlGraph is read-only");
		
	}



	@Override
	public Stream<? extends Triple> getTriples() {
		return getTriples(null, null, null);
	}



	@Override
	public Stream<? extends Triple> getTriples(BlankNodeOrIRI subject,
			IRI predicate, RDFTerm object) {
		Iterator<Triple> it = performFilter(subject, predicate, object);
		// FIXME: What are the correct characteristics?
		int characteristics = Spliterator.DISTINCT | Spliterator.IMMUTABLE | Spliterator.NONNULL;
		Spliterator<Triple> spliterator = Spliterators.spliteratorUnknownSize(it, characteristics);
		// FIXME: Can it be parallell?
		return StreamSupport.stream(spliterator, false);
	}



	@Override
	public Stream<? extends Triple> getTriples(Predicate<Triple> filter) {
		return getTriples().filter(filter);
	}

}
