package org.aksw.sparqlify.core.cast;



import com.hp.hpl.jena.sparql.expr.NodeValue;

/**
 * Pendant to Jena's RDFDatatype:
 * 
 * 
 * 
 * 
 * @author Claus Stadler <cstadler@informatik.uni-leipzig.de>
 *
 */
public interface SqlDatatype {
	//Class<?> getJavaClass();
	SqlValue toSqlValue(NodeValue nodeValue);
}

