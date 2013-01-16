package org.aksw.sparqlify.core.cast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.aksw.sparqlify.algebra.sparql.transform.MethodSignature;
import org.aksw.sparqlify.algebra.sql.exprs.evaluators.SqlExprEvaluator;
import org.aksw.sparqlify.algebra.sql.exprs2.ExprSqlBridge;
import org.aksw.sparqlify.algebra.sql.exprs2.S_ColumnRef;
import org.aksw.sparqlify.algebra.sql.exprs2.S_Constant;
import org.aksw.sparqlify.algebra.sql.exprs2.S_Function;
import org.aksw.sparqlify.algebra.sql.exprs2.SqlExpr;
import org.aksw.sparqlify.algebra.sql.nodes.Projection;
import org.aksw.sparqlify.core.TypeToken;
import org.aksw.sparqlify.core.algorithms.ExprSqlRewrite;
import org.aksw.sparqlify.core.algorithms.SqlTranslatorImpl;
import org.aksw.sparqlify.core.datatypes.SparqlFunction;
import org.aksw.sparqlify.expr.util.ExprUtils;
import org.aksw.sparqlify.trash.ExprCopy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.sdb.core.Generator;
import com.hp.hpl.jena.sdb.core.Gensym;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.expr.Expr;
import com.hp.hpl.jena.sparql.expr.ExprFunction;
import com.hp.hpl.jena.sparql.expr.ExprVar;
import com.hp.hpl.jena.sparql.expr.NodeValue;



class ExprHolder {
	private Object expr;
	
	public ExprHolder(SqlExpr expr) {
		this.expr = expr;
	}
	
	public ExprHolder(Expr expr) {
		this.expr = expr;
	}
	
	public boolean isSqlExpr() {
		boolean result = this.expr instanceof SqlExpr;
		return result;
	}
	
	public boolean isExpr() {
		boolean result = this.expr instanceof Expr;
		return result;		
	}
	
	public Expr getExpr() {
		return (Expr)expr;
	}
	
	public SqlExpr getSqlExpr() {
		return (SqlExpr) expr;
	}
}


class RewriteState {
	private Generator genSym;
	private Projection projection = new Projection();

	public RewriteState() {
		this(Gensym.create("s"));
	}
	
	public RewriteState(Generator genSym) {
		super();
		this.genSym = genSym;
		this.projection = projection;
	}
	
	public Generator getGenSym() {
		return genSym;
	}
	public Projection getProjection() {
		return projection;
	}
}


/**
 * Computes the datatype of each expression node.
 * 
 * TODO: I think this class should not actually transform expressions 
 * 
 * @author Claus Stadler <cstadler@informatik.uni-leipzig.de>
 *
 */
public class TypedExprTransformerImpl
	implements TypedExprTransformer
{	
	private static final Logger logger = LoggerFactory.getLogger(SqlTranslatorImpl.class);
	
	
	//private TypeSystem typeSystem;
	private SparqlFunctionProvider functionProvider;

	public TypedExprTransformerImpl(SparqlFunctionProvider functionProvider) { //TypeSystem typeSystem) {
		//this.typeSystem = typeSystem;
		this.functionProvider = functionProvider;
	}
	

	public static List<TypeToken> getTypes(Collection<SqlExpr> sqlExprs) {

		List<TypeToken> result = new ArrayList<TypeToken>(sqlExprs.size());
		for(SqlExpr sqlExpr : sqlExprs) {
			TypeToken typeName = sqlExpr.getDatatype();
			result.add(typeName);
		}
		
		return result;
	}
	
	public static boolean containsTypeError(Iterable<SqlExpr> exprs) {
		for(SqlExpr expr : exprs) {
			if(S_Constant.TYPE_ERROR.equals(expr)) {
				return true;
			}
		}
		
		return false;
	}
	

	public ExprSqlRewrite rewrite(Expr expr, Map<String, TypeToken> typeMap) {
		RewriteState state = new RewriteState();
		
		ExprHolder rewrite = rewrite(expr, typeMap, state);
		
		Expr resultExpr;
		if(rewrite.isSqlExpr()) {
			resultExpr = new ExprSqlBridge(rewrite.getSqlExpr());
		} else {
			resultExpr = rewrite.getExpr();
		}
		
		ExprSqlRewrite result = new ExprSqlRewrite(resultExpr, state.getProjection());
		
		return result;
	}
	
	
	public ExprHolder rewrite(Expr expr, Map<String, TypeToken> typeMap, RewriteState state) {
		
		ExprHolder result;
		if(expr.isConstant()) {
			NodeValue nodeValue = expr.getConstant();
			SqlExpr sqlExpr = translate(nodeValue);
			result = new ExprHolder(sqlExpr);
		}
		else if(expr.isVariable()) {
			ExprVar var = expr.getExprVar();
			SqlExpr sqlExpr = translate(var, typeMap);
			result = new ExprHolder(sqlExpr);
		}
		else if(expr.isFunction()) {
			result = rewrite(expr.getFunction(), typeMap, state);
		} else {
			throw new RuntimeException("Should not happen: " + expr);
		}
		
		return result;
	}
	
	public ExprHolder rewrite(ExprFunction fn, Map<String, TypeToken> typeMap, RewriteState state) {

		ExprHolder result;
		
		String functionId = ExprUtils.getFunctionId(fn);

		logger.debug("Processing: " + fn);
		/*
		if(containsTypeError(evaledArgs)) {
			logger.debug("Type error in argument (" + evaledArgs + ")");
			return S_Constant.TYPE_ERROR;
		}
		*/
		
		List<ExprHolder> evaledArgs = new ArrayList<ExprHolder>();
		boolean isAllSqlExpr = true;
		for(Expr arg : fn.getArgs()) {
			
			ExprHolder evaledArg = rewrite(arg, typeMap, state);
			

			isAllSqlExpr = isAllSqlExpr && evaledArg.isSqlExpr();
			
			// If an argument evaluated to type error, return type error
			// TODO: Distinguish between null and type error. Currently we use nvNothing which actually corresponds to NULL
			// (currently represented with nvNothing - is that safe? - Rather no - see above)
			/*
			if(evaledArg.equals(NodeValue.nvNothing)) {
				return NodeValue.nvNothing;
			}
			*/
			
			evaledArgs.add(evaledArg);
		}


		// Allocate variables for all SqlExprs, pass on Exprs
		if(!isAllSqlExpr) {
			
			List<Expr> newArgs = new ArrayList<Expr>(evaledArgs.size());
			
			for(ExprHolder holder : evaledArgs) {
				
				Expr arg;
				
				if(holder.isSqlExpr()) {
					SqlExpr typedExpr = holder.getSqlExpr();
					
					String varName = state.getGenSym().next();
					Var var = Var.alloc(varName);
					arg = new ExprVar(var);
					
					state.getProjection().put(varName, typedExpr);
				}  else {
					
					arg = holder.getExpr();
					
				}
				
				newArgs.add(arg);
			}
			
			Expr expr = ExprCopy.getInstance().copy(fn, newArgs);
			
			result = new ExprHolder(expr);
		
		} else {

			// All arguments are SQL Exprs
			List<SqlExpr> newArgs = new ArrayList<SqlExpr>(evaledArgs.size());
			
			for(ExprHolder holder : evaledArgs) {
				SqlExpr sqlExpr = holder.getSqlExpr();
				newArgs.add(sqlExpr);
			}
			
			// There must be a function registered for the argument types
	
	//		String datatype = exprTypeEvaluator.evaluateType(fn);
	//		if(datatype == null) {
	//			throw new RuntimeException("No datatype could be obtained for " + fn);	
	//		}
	//		
	//		//result = S_Function.create(datatype, functionId, evaledArgs);
			
	
			SparqlFunction sparqlFunction = functionProvider.getSparqlFunction(functionId);
			if(sparqlFunction == null) {
				throw new RuntimeException("Sparql function not declared: " + functionId);
			}
				
			SqlExprEvaluator evaluator = sparqlFunction.getEvaluator();
			
			logger.debug("Evaluator for '" + functionId + "': " + evaluator);
			
			// If there is an evaluator, we can pass all arguments to it, and see if it yields a new expression
			if(evaluator != null) {
				SqlExpr tmp = evaluator.eval(newArgs);
				if(tmp != null) {
					result = new ExprHolder(tmp);
					//return tmp;
				} else {
					throw new RuntimeException("Evaluator yeld null value");
				}
			} else {
			
				// If there is no evaluator, use the default behaviour:
				MethodSignature<TypeToken> signature = sparqlFunction.getSignature();
				if(signature != null) {
					
					TypeToken returnType = signature.getReturnType();
					if(returnType != null) {
						
						SqlExpr tmp = S_Function.create(returnType, functionId, newArgs);
						result = new ExprHolder(tmp);
					} else {
						throw new RuntimeException("Return type is null: " + signature);
					}
					
				} else {
					throw new RuntimeException("Neither evaluator nor signature found for " + functionId + " in " + fn);					
				}
				
			}			
			// Check if the functionProvider has a definition for the functionId
			
		}
		
		return result;
	}

	
	/**
	 * TODO How to pass the type error to SPARQL functions,
	 * such as logical AND/OR/NOT, so they get a chance to deal with it?
	 * 
	 * Using the SPARQL level evaluator is not really possible anymore, because we already translated to the SQL level.
	 * 
	 * We could either:
	 * . have special treatment for logical and/or/not
	 *     But then we can't extend the system to our liking
	 * . have an evaluator on the SqlExpr level, rather than the expr level
	 *     Very generic, but can we avoid the duplication with Expr and SqlExpr?
	 *     Probably we can't.
	 *     The expr structure does not allow adding a custom datatype, and mapping it externally turned out to be quite a hassle.
	 *     
	 * 
	 * 
	 * @param fn
	 * @param binding
	 * @param typeMap
	 * @return
	 */
	public SqlExpr translate(ExprFunction fn, Map<String, TypeToken> typeMap) {
		
		SqlExpr result;
		
		List<SqlExpr> evaledArgs = new ArrayList<SqlExpr>();

		logger.debug("Processing: " + fn);
		/*
		if(containsTypeError(evaledArgs)) {
			logger.debug("Type error in argument (" + evaledArgs + ")");
			return S_Constant.TYPE_ERROR;
		}
		*/
		
		
		for(Expr arg : fn.getArgs()) {				
			SqlExpr evaledArg = translate(arg, typeMap);
			
			// If an argument evaluated to type error, return type error
			// TODO: Distinguish between null and type error. Currently we use nvNothing which actually corresponds to NULL
			// (currently represented with nvNothing - is that safe? - Rather no - see above)
			/*
			if(evaledArg.equals(NodeValue.nvNothing)) {
				return NodeValue.nvNothing;
			}
			*/
			
			evaledArgs.add(evaledArg);
		}

		
//		List<TypeToken> argTypes = getTypes(evaledArgs);
		
		// There must be a function registered for the argument types
		String functionId = ExprUtils.getFunctionId(fn);

//		String datatype = exprTypeEvaluator.evaluateType(fn);
//		if(datatype == null) {
//			throw new RuntimeException("No datatype could be obtained for " + fn);	
//		}
//		
//		//result = S_Function.create(datatype, functionId, evaledArgs);
		

		SparqlFunction sparqlFunction = functionProvider.getSparqlFunction(functionId);
		if(sparqlFunction == null) {
			throw new RuntimeException("Sparql function not declared: " + functionId);
		}
			
		SqlExprEvaluator evaluator = sparqlFunction.getEvaluator();
		
		logger.debug("Evaluator for '" + functionId + "': " + evaluator);
		
		// If there is an evaluator, we can pass all arguments to it, and see if it yields a new expression
		if(evaluator != null) {
			SqlExpr tmp = evaluator.eval(evaledArgs);
			if(tmp != null) {
				return tmp;
			} else {
				throw new RuntimeException("Evaluator yeld null value");
			}
		}
		
		// If there is no evaluator, use the default behaviour:
		MethodSignature<TypeToken> signature = sparqlFunction.getSignature();
		if(signature != null) {
			
			TypeToken returnType = signature.getReturnType();
			if(returnType != null) {
				
				result = S_Function.create(returnType, functionId, evaledArgs);
				
			}
			
		}
		
		// Check if the functionProvider has a definition for the functionId

		
		
		
		throw new RuntimeException("Neither evaluator nor signature found for " + fn);
		
		
		// If there was no evaluator, or if the evaluator returned null, continue here.
		
//		// TODO: New approach: There must always be an evaluator
//		
//		// If one of the arguments is a type error, we must return a type error.
//		if(containsTypeError(evaledArgs)) {
//			return S_Constant.TYPE_ERROR;
//		}
//		
//		SqlMethodCandidate castMethod = datatypeSystem.lookupMethod(functionId, argTypes);
//		
//		if(castMethod == null) {
//			//throw new RuntimeException("No method found for " + fn);
//			logger.debug("No method found for " + fn);
//			
//			return S_Constant.TYPE_ERROR;
//		}
//		
//		// TODO: Invoke the SQL method's invocable if it exists and all arguments are constants
//		
//		result = S_Method.createOrEvaluate(castMethod, evaledArgs);
//
//		logger.debug("[Result] " + result);
//		
//		return result;
	}
	
	
	public SqlExpr translate(NodeValue expr) {
		SqlExpr result = new S_Constant(expr);
		return result;
	}
	
	public SqlExpr translate(ExprVar expr, Map<String, TypeToken> typeMap) {
		
		String varName = expr.getVarName();
		TypeToken datatype = typeMap.get(varName);

		if(datatype == null) {
			throw new RuntimeException("No datatype found for " + varName);
		}
		
		SqlExpr result = new S_ColumnRef(datatype, varName);
		return result;
	}
	
	/*
	 * How to best add interceptors (callbacks with transformation) for certain functions?
	 * 
	 * e.g.: concat(foo, concat(?x...)) -> concat(foo, ?x)
	 * lang(rdfterm(2, ?x, ?y, '')) -> ?y
	 * 
	 * The main question is, whether to apply to callback before or after the arguments are evaluated.
	 * 
	 * -> After makes more sense: Then we have constant folder arguments 
	 */
	public SqlExpr translate(Expr expr, Map<String, TypeToken> typeMap) {
		
		//assert expr != null : "Null pointer exception";
		if(expr == null) {
			throw new NullPointerException();
		}
		
		//System.out.println(expr);
		
		SqlExpr result = null;
		if(expr.isConstant()) {
			
			result = translate(expr.getConstant()); 
			

		} else if(expr.isFunction()) {
			ExprFunction fn = expr.getFunction();
			
			result = translate(fn, typeMap);
			
		} else if(expr.isVariable()) {
			
			result = translate(expr.getExprVar(), typeMap);
		} else {
			throw new RuntimeException("Unknown expression type encountered: " + expr);
		}

		return result;
	}

}