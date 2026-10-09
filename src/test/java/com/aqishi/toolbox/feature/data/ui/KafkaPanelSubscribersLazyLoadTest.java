package com.aqishi.toolbox.feature.data.ui;

import com.aqishi.toolbox.feature.data.domain.KafkaSubscriberAnalysis;
import com.aqishi.toolbox.infra.secrets.InMemoryPreferences;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.util.I18n;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaPanelSubscribersLazyLoadTest {

    @Test
    @DisplayName("非主题订阅者页签下选择主题不触发查询并重置状态")
    void selectingTopicInOtherTabsDoesNotTriggerSubscribersLoad() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                KafkaPanel kafka = new KafkaPanel(SecretStore.disabled(), new InMemoryPreferences());
                kafka.getView();

                JTabbedPane rightTabs = getField(kafka, "rightTabbedPane", JTabbedPane.class);
                DefaultTableModel groupModel = getField(kafka, "subscriberGroupTableModel", DefaultTableModel.class);
                JLabel statusLabel = getField(kafka, "subscribersStatusLabel", JLabel.class);

                // 处于消息查看页签（Tab 1）
                rightTabs.setSelectedIndex(1);

                // 预置一些旧数据模拟之前的主题
                groupModel.addRow(new Object[]{"old-group", "STABLE", "Active", 1});
                setField(kafka, "loadedSubscriberTopic", "old-topic");

                Method onTopicSelectedMethod = KafkaPanel.class.getDeclaredMethod("onTopicSelected", String.class);
                onTopicSelectedMethod.setAccessible(true);

                // 切换主题为 topic-x
                onTopicSelectedMethod.invoke(kafka, "topic-x");

                // 验证：不会发起后台任务，表格被重置清空，已加载主题被置空，显示提示文案
                SwingWorker<?, ?> worker = getField(kafka, "subscribersWorker", SwingWorker.class);
                assertNull(worker, "非订阅者页签下选择主题不得启动查询任务");
                assertNull(getField(kafka, "loadedSubscriberTopic", String.class), "已加载主题必须置空");
                assertEquals(0, groupModel.getRowCount(), "订阅者表格必须被清空");
                assertEquals(I18n.get("tool.kafka.subscribers.hint"), statusLabel.getText());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        assertNull(failure.get(), "Lazy load assertion failed");
    }

    @Test
    @DisplayName("切换主题时立即取消正在运行的前序订阅者查询任务")
    void switchingTopicCancelsRunningSubscribersWorker() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch finishWorker = new CountDownLatch(1);

        SwingWorker<KafkaSubscriberAnalysis.Result, Void> dummyWorker =
                new SwingWorker<>() {
                    @Override
                    protected KafkaSubscriberAnalysis.Result doInBackground() throws Exception {
                        workerStarted.countDown();
                        finishWorker.await(5, TimeUnit.SECONDS);
                        return null;
                    }
                };

        SwingUtilities.invokeAndWait(() -> {
            try {
                KafkaPanel kafka = new KafkaPanel(SecretStore.disabled(), new InMemoryPreferences());
                kafka.getView();

                dummyWorker.execute();
                setField(kafka, "subscribersWorker", dummyWorker);

                Method cancelMethod = KafkaPanel.class.getDeclaredMethod("cancelSubscribersWorker");
                cancelMethod.setAccessible(true);
                cancelMethod.invoke(kafka);

                assertTrue(dummyWorker.isCancelled(), "前序任务必须被标记为已取消");
                assertNull(getField(kafka, "subscribersWorker", SwingWorker.class), "任务引用必须被置空");
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                finishWorker.countDown();
            }
        });
        assertNull(failure.get(), "Cancellation assertion failed");
    }

    @Test
    @DisplayName("切到主题订阅者页签时检测未加载主题并触发提示或加载")
    void tabSwitchTriggersSubscribersBehavior() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                KafkaPanel kafka = new KafkaPanel(SecretStore.disabled(), new InMemoryPreferences());
                kafka.getView();

                JTabbedPane rightTabs = getField(kafka, "rightTabbedPane", JTabbedPane.class);
                JLabel statusLabel = getField(kafka, "subscribersStatusLabel", JLabel.class);

                // 未选主题时切到 Tab 3
                rightTabs.setSelectedIndex(3);
                assertEquals(I18n.get("tool.kafka.subscribers.hint"), statusLabel.getText());

                // 模拟已选择主题但未连接，切到 Tab 3 时安全不崩溃
                setField(kafka, "selectedTopic", "my-topic");
                setField(kafka, "loadedSubscriberTopic", null);
                rightTabs.setSelectedIndex(1);
                rightTabs.setSelectedIndex(3);
                assertNull(getField(kafka, "subscribersWorker", SwingWorker.class), "未连接时不启动任务");
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        assertNull(failure.get(), "Tab switch behavior failed");
    }

    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
