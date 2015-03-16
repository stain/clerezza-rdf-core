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

import java.util.Objects;

import com.github.commonsrdf.api.BlankNode;
import com.github.commonsrdf.api.BlankNodeOrIRI;
import com.github.commonsrdf.api.Graph;
import com.github.commonsrdf.api.IRI;
import com.github.commonsrdf.api.RDFTerm;
import com.github.commonsrdf.simple.SimpleRDFTermFactory;

/**
 *
 * @author developer
 */
class SparqlBNode implements BlankNode {
    
    private static SimpleRDFTermFactory factory = new SimpleRDFTermFactory();
	
    final static IRI internalBNodeId = factory.createIRI("urn:x-internalid:fdmpoihdfw");
    
    final Graph context;
    private final int isoDistinguisher;

    SparqlBNode(Object rawNode, Graph context, int isoDistinguisher) {
        this.isoDistinguisher = isoDistinguisher;
        final Graph contextBuider = factory.createGraph();
        context.getTriples().forEach(triple -> {
            BlankNodeOrIRI subject = triple.getSubject();
            RDFTerm object = triple.getObject();
            contextBuider.add(factory.createTriple(subject.equals(rawNode) ? internalBNodeId : subject, 
                    triple.getPredicate(), 
                    object.equals(rawNode) ? internalBNodeId : object));
        });
        this.context = contextBuider; // no immutable graph
    }

    @Override
    public int hashCode() {
        int hash = 7+isoDistinguisher;
        hash = 61 * hash + Objects.hashCode(this.context);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final SparqlBNode other = (SparqlBNode) obj;
        if (isoDistinguisher != other.isoDistinguisher) {
            return false;
        }
        return Objects.equals(this.context, other.context);
    }

	@Override
	public String ntriplesString() {
		return "_:" + internalIdentifier();
	}

	@Override
	public String internalIdentifier() {
		// FIXME: not very unique..
		return "b" + isoDistinguisher;
	}
}
