
package org.srs.datacatalog.search;

import com.google.common.collect.Multiset;
import java.util.HashMap;
import org.freehep.commons.lang.AST;
import org.zerorm.core.Column;
import org.zerorm.core.Expr;
import org.zerorm.core.Op;
import org.zerorm.core.Param;
import org.zerorm.core.Select;
import org.zerorm.core.interfaces.MaybeHasAlias;
import org.zerorm.core.interfaces.MaybeHasParams;
import org.zerorm.core.interfaces.SimpleTable;
import org.srs.datacatalog.search.plugins.DatacatPlugin;
import org.srs.datacatalog.search.tables.MetajoinedStatement;

/**
 *
 * @author bvan
 */
public class DatacatSearchContext implements SearchContext {

    public static class PluginScope {
        HashMap<String, DatacatPlugin> pluginMap;

        public PluginScope(HashMap<String, DatacatPlugin> pluginMap){
            this.pluginMap = pluginMap;
        }

        public boolean contains(String ident){
            if(ident.contains( "." )){
                String[] ns_plugin = ident.split( "\\." );
                if(pluginMap.containsKey( ns_plugin[0] )){
                    return pluginMap.get( ns_plugin[0] ).containsKey( ns_plugin[1] );
                }
            }
            return false;
        }
        
        public DatacatPlugin getPlugin(String ident){
            if(ident.contains( "." )){
                String[] ns_plugin = ident.split( "\\." );
                if(pluginMap.containsKey( ns_plugin[0] )){
                    return pluginMap.get( ns_plugin[0] );
                }
            }
            return null;
        }
    }
    
    public static class MetavalueScope {
        MetanameContext context;

        public MetavalueScope(MetanameContext context){
            this.context = context;
        }

        public boolean contains(String ident){
            return context.contains( ident );
        }
    }
    
    final MetajoinedStatement dsv;
    final PluginScope pluginScope;
    final MetanameContext metanameContext;
    
    public DatacatSearchContext(MetajoinedStatement dsv, HashMap<String, DatacatPlugin> pluginMap, 
            MetanameContext context){
        this.dsv = dsv;
        this.pluginScope = new PluginScope( pluginMap );
        this.metanameContext = context;
    }
    
    @Override
    public boolean inSelectionScope(String ident){
        for(MaybeHasAlias selection: getStatement().getAvailableSelections()){
            if(selection.canonical().equals( ident ) && selection instanceof MaybeHasAlias){
                return true;
            }
        }
        return false;
    }
    
    @Override
    public void assertIdentsValid(AST ast){
        Multiset<String> exprIdents = (Multiset<String>) ast.getRoot().getMetadata( "idents" );
        for(String s: exprIdents.elementSet()){
            if(!inScope( s )){
                throw new IllegalArgumentException(
                        "Unable to resolve '" + s + "' in '" + SearchUtils.getErrorString( ast, s ) + "'" );
            }
        }
    }

    @Override
    public boolean inPluginScope(String ident){
        return pluginScope.contains( ident );
    }

    @Override
    public boolean inMetanameScope(String ident){
        return metanameContext.contains( ident );
    }
    
    @Override
    public boolean inScope(String ident){
        return inSelectionScope(ident) || inPluginScope(ident) || inMetanameScope(ident);
    }
    
    @Override
    public Select getStatement(){
        return dsv;
    }

    @Override
    public Expr evaluateNode(AST.Node node){
        Object tLeft = getTokenOrExpression( node.getLeft() );
        Object tRight = getTokenOrExpression( node.getRight() );
        Op tOper = node.getValue() != null ? Op.valueOf( node.getValue().toString() ) : null;
        
        if(tLeft != null || tOper != null || tRight != null){
            
            if( tOper == Op.AND || tOper == Op.OR){
                return tOper.apply( (Expr) tLeft, (Expr) tRight );
            }
            return preEvaluateExpression( (MetajoinedStatement)dsv, node, tLeft, tOper, tRight);
        }
        return null;
    }
    
    public Expr evaluateNode(AST.Node node, MetajoinedStatement statement){
        Object tLeft = getTokenOrExpression( node.getLeft(), statement );
        Object tRight = getTokenOrExpression( node.getRight(), statement );
        Op tOper = null;
        if(node.getValue() != null){
            String opName = node.getValue().toString();
            if("MATCHES".equalsIgnoreCase( opName )){
                opName = "LIKE";
            }
            tOper = Op.valueOf(opName);
        }
        
        if(tLeft != null || tOper != null || tRight != null){
            
            if( tOper == Op.AND || tOper == Op.OR){
                return tOper.apply( (Expr) tLeft, (Expr) tRight );
            }
            return preEvaluateExpression(statement, node, tLeft, tOper, tRight);
        }
        return null;
    }
    
    private Object getTokenOrExpression(AST.Node node){
        if(node == null) {
            return null;
        }
        Object ret = null;
        ret = getValueNode(node);
        return ret != null ? ret : evaluateNode(node);
    }
    
    private Object getTokenOrExpression(AST.Node node, MetajoinedStatement statement){
        if(node == null) {
            return null;
        }
        Object ret = null;
        ret = getValueNode(node);
        return ret != null ? ret : evaluateNode(node, statement);
    }
    
    private Object getValueNode(AST.Node node){
        if(node.getLeft() == null && node.getRight() == null){
            Object nVal = node.getValue();
            if(nVal instanceof String){
                String strVal = (String) nVal;
                if(inSelectionScope( strVal )){
                    return getColumnFromSelectionScope( strVal );
                }

                if(pluginScope.contains( strVal )){
                    // Optionally, Join plugin/ foreign table here
                }
            }
            return nVal;
        }
        return null;
    }
    
    private Expr preEvaluateExpression(MetajoinedStatement statement, AST.Node leftNode, Object tLeft, Op tOper, Object tRight){
        String ident = leftNode.getLeft().getValue().toString();
        
        if( tLeft instanceof Column){
            Column c = (Column) tLeft;
            if( !(tRight instanceof MaybeHasParams) ){
                tRight = c.checkedParam( c.getName(), tRight);
            }
            return tOper.apply( (MaybeHasAlias) tLeft,  tRight );
        }
        
        if( pluginScope.contains( ident ) ){
            DatacatPlugin plugin = pluginScope.getPlugin( (String) tLeft );
            String fIdent = ((String) tLeft).split( "\\.")[1];
            SimpleTable t = plugin.joinToStatement( statement );
            Column c = null;
            for(Object o: t.getColumns()){
                if(o instanceof Column){
                    Column cc = (Column) o;
                    if( cc.canonical().equals( fIdent ) ){
                        c = cc;
                        break;
                    }
                } 
            }
            //TODO: 
            if( !(tRight instanceof MaybeHasParams)){
                Param r = c.checkedParam( c.getName(), tRight);
                tRight = r;
            }
            return tOper.apply( c, tRight );
        }
        return statement.getMetadataExpression( tLeft, tOper, tRight);
    }
   
    private Column getColumnFromSelectionScope(String ident){
        for(MaybeHasAlias selection: getStatement().getAvailableSelections()){
            if(selection.canonical().equals( ident ) && selection instanceof Column){
                getStatement().selection( selection );
                return (Column) selection;
            }
        }
        return null;
    }

}
