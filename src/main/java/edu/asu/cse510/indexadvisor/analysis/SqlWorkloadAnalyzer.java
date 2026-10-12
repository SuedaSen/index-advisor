// TODO: workload analizi için ek metrikler hesaplanacak
// FIXME: NULL filtrelerde hata oluşabilir
// BUG: join cardinality hesabı yanlış olabilir
// NOTE: bu kısım benchmark sonucunu etkiliyor
package edu.asu.cse510.indexadvisor.analysis;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

/** We parse SQL structurally and extract filter, join, and ordering columns. */
public final class SqlWorkloadAnalyzer {
    public QueryFeatures analyze(String sql) {
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect body = select.getPlainSelect();
            if (body == null) throw new IllegalArgumentException("Only plain SELECT is supported");
            Map<String, String> aliases = new HashMap<>();
            register(body.getFromItem(), aliases);
            if (body.getJoins() != null) body.getJoins().forEach(join -> register(join.getRightItem(), aliases));
            String sole = aliases.values().stream().distinct().count() == 1 ? aliases.values().iterator().next() : "";
            SortedSet<ColumnReference> filters = collect(body.getWhere(), aliases, sole);
            SortedSet<ColumnReference> joins = new TreeSet<>();
            if (body.getJoins() != null) for (Join join : body.getJoins())
                for (Expression on : join.getOnExpressions()) joins.addAll(collect(on, aliases, sole));
            SortedSet<ColumnReference> orders = new TreeSet<>();
            if (body.getOrderByElements() != null) body.getOrderByElements()
                    .forEach(item -> orders.addAll(collect(item.getExpression(), aliases, sole)));
            return new QueryFeatures(filters, joins, orders);
        } catch (Exception exception) { throw new IllegalArgumentException("Invalid SQL", exception); }
    }
    private void register(Object item, Map<String, String> aliases) {
        if (item instanceof Table table) {
            String name = table.getFullyQualifiedName().toLowerCase();
            aliases.put(table.getName().toLowerCase(), name); aliases.put(name, name);
            if (table.getAlias() != null) aliases.put(table.getAlias().getName().toLowerCase(), name);
        }
    }
    private SortedSet<ColumnReference> collect(Expression expression, Map<String, String> aliases, String sole) {
        SortedSet<ColumnReference> output = new TreeSet<>(); if (expression == null) return output;
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override public <S> Void visit(Column column, S context) {
                String qualifier = column.getTableName();
                String table = qualifier == null || qualifier.isBlank() ? sole
                        : aliases.getOrDefault(qualifier.toLowerCase(), qualifier.toLowerCase());
                output.add(new ColumnReference(table, column.getColumnName().toLowerCase())); return null;
            }
        }, null); return output;
    }
}
