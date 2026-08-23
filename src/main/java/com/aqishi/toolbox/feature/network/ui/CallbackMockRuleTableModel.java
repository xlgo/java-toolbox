package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MockCondition;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRule;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Table view of an ordered callback-mock rule list. */
public final class CallbackMockRuleTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {"启用", "规则", "请求", "状态"};

    private List<MockRule> rules = Collections.emptyList();

    public void setRules(List<MockRule> newRules) {
        this.rules = newRules == null
                ? Collections.<MockRule>emptyList()
                : new ArrayList<MockRule>(newRules);
        fireTableDataChanged();
    }

    public List<MockRule> getRules() {
        return Collections.unmodifiableList(new ArrayList<MockRule>(rules));
    }

    public MockRule getRuleAt(int rowIndex) {
        return rowIndex < 0 || rowIndex >= rules.size() ? null : rules.get(rowIndex);
    }

    @Override
    public int getRowCount() {
        return rules.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == 0 ? Boolean.class : String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        MockRule rule = getRuleAt(rowIndex);
        if (rule == null) {
            return "";
        }
        switch (columnIndex) {
            case 0:
                return rule.isEnabled();
            case 1:
                return rule.getName();
            case 2:
                return requestSummary(rule);
            case 3:
                return rule.getResponse() == null ? "" : String.valueOf(
                        rule.getResponse().getStatusCode());
            default:
                return "";
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return columnIndex == 0 && getRuleAt(rowIndex) != null;
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        if (columnIndex != 0 || !(value instanceof Boolean)) {
            return;
        }
        MockRule rule = getRuleAt(rowIndex);
        if (rule == null || rule.isEnabled() == (Boolean) value) {
            return;
        }
        rules.set(rowIndex, rule.toBuilder().enabled((Boolean) value).build());
        fireTableRowsUpdated(rowIndex, rowIndex);
    }

    public String conditionSummary(MockRule rule) {
        if (rule == null || rule.getConditions().isEmpty()) {
            return "无附加条件";
        }
        StringBuilder summary = new StringBuilder();
        for (MockCondition condition : rule.getConditions()) {
            if (summary.length() > 0) {
                summary.append(" AND ");
            }
            if (condition == null) {
                summary.append("无效条件");
                continue;
            }
            summary.append(condition.getSource()).append('.').append(condition.getField())
                    .append(' ').append(condition.getOperator());
            if (condition.getExpected() != null && condition.getExpected().length() > 0) {
                summary.append(' ').append(condition.getExpected());
            }
        }
        return summary.toString();
    }

    private String requestSummary(MockRule rule) {
        String method = rule.getMethod() == null ? "" : rule.getMethod();
        String path = rule.getPath() == null ? "" : rule.getPath();
        String conditions = conditionSummary(rule);
        return conditions.length() == 0 ? method + " " + path
                : method + " " + path + "，" + conditions;
    }
}
