/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.rdf.impl.utils;

import java.io.Serializable;
import org.apache.commons.rdf.Iri;

import org.apache.commons.rdf.Language;
import org.apache.commons.rdf.Literal;

/**
 *
 * @author reto
 */
public class PlainLiteralImpl implements Literal, Serializable {

    private String lexicalForm;
    private Language language = null;

    public PlainLiteralImpl(String value) {
        if (value == null) {
            throw new IllegalArgumentException("The literal string cannot be null");
        }
        this.lexicalForm = value;
    }

    public PlainLiteralImpl(String value, Language language) {
        if (value == null) {
            throw new IllegalArgumentException("The literal string cannot be null");
        }
        this.lexicalForm = value;
        this.language = language;
    }

    @Override
    public String getLexicalForm() {
        return lexicalForm;
    }

    @Override
    public boolean equals(Object otherObj) {
        if (!(otherObj instanceof Literal)) {
            return false;
        }
        Literal other = (Literal) otherObj;
        if (!lexicalForm.equals(other.getLexicalForm())) {
            return false;
        }
        if (language != null) {
            return language.equals(other.getLanguage());
        }
        if (other.getLanguage() != null) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = lexicalForm.hashCode() + XSD_STRING_HASH;
        if (language != null) {
            hash += language.hashCode();
        }
        return hash;
    }

    @Override
    public Language getLanguage() {
        return language;
    }

    @Override
    public String toString() {
        StringBuffer result = new StringBuffer();
        result.append('\"').append(lexicalForm).append('\"');
        if (language != null) {
            result.append("@").append(language.toString());
        }
        return result.toString();
    }

    @Override
    public Iri getDataType() {
        return XSD_STRING;
    }
    private static final Iri XSD_STRING = new Iri("http://www.w3.org/2001/XMLSchema#string");
    private static final int XSD_STRING_HASH = XSD_STRING.hashCode();
}
