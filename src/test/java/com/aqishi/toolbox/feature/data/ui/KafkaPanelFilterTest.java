package com.aqishi.toolbox.feature.data.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultListModel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KafkaPanelFilterTest {

    @Test
    @DisplayName("Kafka 消费组支持通配符模式及恢复全量")
    void consumerGroupFilteringSupportsWildcards() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                KafkaPanel kafka = new KafkaPanel();
                kafka.getView();

                JTextField searchField = getField(kafka, "groupSearchField", JTextField.class);
                DefaultListModel<String> listModel = getField(kafka, "groupListModel", DefaultListModel.class);
                @SuppressWarnings("unchecked")
                List<String> allGroups = getField(kafka, "allGroupsList", List.class);
                Method filterGroupsMethod = KafkaPanel.class.getDeclaredMethod("filterGroups");
                filterGroupsMethod.setAccessible(true);

                allGroups.clear();
                allGroups.addAll(List.of(
                        "order-service-group",
                        "user-service-group",
                        "order-dev",
                        "payment-consumer"
                ));

                // 1. 前缀通配符 order*
                searchField.setText("order*");
                filterGroupsMethod.invoke(kafka);
                assertEquals(List.of("order-service-group", "order-dev"), getListElements(listModel));

                // 2. 后缀通配符 *consumer
                searchField.setText("*consumer");
                filterGroupsMethod.invoke(kafka);
                assertEquals(List.of("payment-consumer"), getListElements(listModel));

                // 3. 中间通配符 order*group
                searchField.setText("order*group");
                filterGroupsMethod.invoke(kafka);
                assertEquals(List.of("order-service-group"), getListElements(listModel));

                // 4. 清空恢复全量
                searchField.setText("");
                filterGroupsMethod.invoke(kafka);
                assertEquals(4, listModel.size());

                // 5. 普通模糊包含
                searchField.setText("service");
                filterGroupsMethod.invoke(kafka);
                assertEquals(List.of("order-service-group", "user-service-group"), getListElements(listModel));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        assertNull(failure.get(), "Filtering groups in KafkaPanel should succeed");
    }

    private static List<String> getListElements(DefaultListModel<String> model) {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            result.add(model.getElementAt(i));
        }
        return result;
    }

    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
