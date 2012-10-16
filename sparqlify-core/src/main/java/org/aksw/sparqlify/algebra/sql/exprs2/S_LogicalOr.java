package org.aksw.sparqlify.algebra.sql.exprs2;

import org.aksw.sparqlify.core.TypeToken;
import org.openjena.atlas.io.IndentedWriter;

public class S_LogicalOr
	extends SqlExpr2
{
	public S_LogicalOr(SqlExpr left, SqlExpr right) {
		super(TypeToken.Boolean, "logicalOr", left, right);
	}
	
	@Override
	public void asString(IndentedWriter writer) {
		writer.print("LogicalOr");
		writeArgs(writer);
	}

	
	public static S_LogicalOr create(SqlExpr a, SqlExpr b) {
		return new S_LogicalOr(a, b);
	}
}