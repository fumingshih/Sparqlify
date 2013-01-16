package org.aksw.sparqlify.core.cast;

import java.util.Map;

import org.aksw.sparqlify.algebra.sql.exprs2.SqlExpr;
import org.aksw.sparqlify.core.TypeToken;
import org.aksw.sparqlify.core.algorithms.ExprSqlRewrite;

import com.hp.hpl.jena.sparql.expr.Expr;

public interface TypedExprTransformer {
	SqlExpr translate(Expr sparqlExpr, Map<String, TypeToken> typeMap);
	
	ExprSqlRewrite rewrite(Expr expr, Map<String, TypeToken> typeMap);
}