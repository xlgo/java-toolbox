package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.application.WeChatContactCollector;
import com.aqishi.toolbox.feature.system.application.WeChatBulkSender;
import com.aqishi.toolbox.feature.system.domain.WeChatContactReader.ContactInfo;
import com.aqishi.toolbox.util.I18n;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class WeChatPanelTest {
    @Test void deletingMultipleSortedRowsRemovesOnlySelectedMessagesAndRenumbersIds() throws Exception {
        WeChatPanel panel = new WeChatPanel();
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.getView();
                DefaultTableModel model = field(panel, "tableModel", DefaultTableModel.class);
                model.addRow(new Object[]{1, "Charlie", "C", "new", ""});
                model.addRow(new Object[]{2, "Alice", "A", "new", ""});
                model.addRow(new Object[]{3, "Dave", "D", "pending", "time"});
                model.addRow(new Object[]{4, "Bob", "B", "new", ""});
                JTable table = field(panel, "contactTable", JTable.class);
                var sorter = new javax.swing.table.TableRowSorter<>(model);
                table.setRowSorter(sorter);
                sorter.toggleSortOrder(1);
                JButton delete = field(panel, "deleteRowBtn", JButton.class);
                assertFalse(delete.isEnabled());
                table.addRowSelectionInterval(0, 0);
                table.addRowSelectionInterval(2, 2);
                assertTrue(delete.isEnabled());
                delete.doClick();
                assertEquals(2, model.getRowCount());
                assertEquals("Dave", model.getValueAt(0, 1));
                assertEquals("pending", model.getValueAt(0, 3));
                assertEquals("time", model.getValueAt(0, 4));
                assertEquals("Bob", model.getValueAt(1, 1));
                assertEquals(1, model.getValueAt(0, 0));
                assertEquals(2, model.getValueAt(1, 0));
                table.setRowSelectionInterval(0, 0);
                table.getActionMap().get("delete-send-rows").actionPerformed(null);
                assertEquals(1, model.getRowCount());
                assertEquals("Dave", model.getValueAt(0, 1));
                assertFalse(delete.isEnabled());
            });
        } finally { SwingUtilities.invokeAndWait(panel::closeResources); }
    }

    @Test void verifiedSendingCommitsEditsAndDoesNotResendPendingOrConfirmedRows() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var captured=new java.util.concurrent.atomic.AtomicReference<List<WeChatBulkSender.Request>>();
        WeChatPanel panel=new WeChatPanel() {
            @Override protected WeChatBulkSender createBulkSender() {
                return new WeChatBulkSender() {
                    @Override public void send(List<Request> jobs,int interval,Listener listener) {
                        calls.incrementAndGet();captured.set(jobs);
                        listener.result(new Result(0,"acknowledged","","123"));
                        listener.result(new Result(1,"pending","Waiting",""));
                    }
                };
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.getView();assertEquals(0,field(panel,"sendModeCombo",JComboBox.class).getSelectedIndex());
                DefaultTableModel model=field(panel,"tableModel",DefaultTableModel.class);
                model.addRow(new Object[]{1,"old_alias","Text one","new",""});
                model.addRow(new Object[]{2,"second_alias","Text two","new",""});
                JTable table=field(panel,"contactTable",JTable.class);
                assertTrue(table.editCellAt(0,1));((JTextField)table.getEditorComponent()).setText("edited_alias");
                field(panel,"startBtn",JButton.class).doClick();
            });
            await(() -> field(panel,"startBtn",JButton.class).isEnabled());
            assertEquals("edited_alias",captured.get().get(0).recipient());assertEquals(1,calls.get());
            SwingUtilities.invokeAndWait(() -> {
                DefaultTableModel model=field(panel,"tableModel",DefaultTableModel.class);
                assertEquals(I18n.get("wechat.send.confirmed"),model.getValueAt(0,3));
                assertEquals(I18n.get("wechat.send.pending"),model.getValueAt(1,3));
                field(panel,"startBtn",JButton.class).doClick();
            });
            assertEquals(1,calls.get());
        } finally {SwingUtilities.invokeAndWait(panel::closeResources);}
    }

    @Test void stopWaitsForSenderToFinishBeforeAllowingAnotherBatch() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),stop=new CountDownLatch(1),release=new CountDownLatch(1);
        WeChatPanel panel=new WeChatPanel() {
            @Override protected WeChatBulkSender createBulkSender() {
                return new WeChatBulkSender() {
                    @Override public void send(List<Request> jobs,int interval,Listener listener) throws Exception {
                        entered.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));
                        listener.result(new Result(0,"cancelled","Stopped",""));
                    }
                    @Override public void close() {stop.countDown();}
                };
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.getView();field(panel,"tableModel",DefaultTableModel.class).addRow(new Object[]{1,"alias","Text","new",""});
                field(panel,"contactTable",JTable.class).setRowSelectionInterval(0,0);
                field(panel,"startBtn",JButton.class).doClick();
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> field(panel,"stopBtn",JButton.class).doClick());
            assertTrue(stop.await(2,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(field(panel,"startBtn",JButton.class).isEnabled());
                assertFalse(field(panel,"deleteRowBtn",JButton.class).isEnabled());
                assertFalse(field(panel,"sendConfigBtn",JButton.class).isEnabled());
                field(panel,"contactTable",JTable.class).getActionMap().get("delete-send-rows").actionPerformed(null);
                assertEquals(1,field(panel,"tableModel",DefaultTableModel.class).getRowCount());
            });
            release.countDown();await(() -> field(panel,"startBtn",JButton.class).isEnabled());
        } finally {release.countDown();SwingUtilities.invokeAndWait(panel::closeResources);}
    }

    @Test void contactsCanBeFoundByNicknameRemarkAliasAndStableUsername() throws Exception {
        WeChatPanel panel = new WeChatPanel() {
            @Override protected WeChatContactCollector createContactCollector() {
                return new WeChatContactCollector() {
                    @Override public List<ContactInfo> collect(Consumer<String> progress) {
                        ContactInfo byRemark = new ContactInfo(), byAlias = new ContactInfo();
                        byRemark.username="wxid_remark";byRemark.nickname="First Nickname";
                        byRemark.remark="A Remark";byRemark.alias="first_alias";byRemark.type=3;
                        byAlias.username="wxid_alias";byAlias.nickname="Second Nickname";
                        byAlias.remark="";byAlias.alias="second_alias";byAlias.type=3;
                        return List.of(byRemark,byAlias);
                    }
                };
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {panel.getView();field(panel,"wxcAutoCollectBtn",JButton.class).doClick();});
            await(() -> field(panel,"wxcTableModel",DefaultTableModel.class).getRowCount()==2);
            SwingUtilities.invokeAndWait(() -> {
                field(panel,"wxcFilterCombo",JComboBox.class).setSelectedIndex(1);
                JTable table=field(panel,"wxcTable",JTable.class);
                JTextField search=field(panel,"wxcSearchField",JTextField.class);
                for(String query:List.of("First Nickname","A Remark","first_alias","wxid_remark")) {
                    search.setText(query);assertEquals(1,table.getRowCount(),query);
                    assertEquals("wxid_remark",table.getModel().getValueAt(table.convertRowIndexToModel(0),5));
                }
                for(String query:List.of("Second Nickname","second_alias","wxid_alias")) {
                    search.setText(query);assertEquals(1,table.getRowCount(),query);
                    assertEquals("wxid_alias",table.getModel().getValueAt(table.convertRowIndexToModel(0),5));
                }
                search.setText("");assertEquals(2,table.getRowCount());
            });
        } finally {SwingUtilities.invokeAndWait(panel::closeResources);}
    }

    @Test void oneClickCollectsWithoutParameterDialogAndInstallsWholeList() throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        WeChatPanel panel = new WeChatPanel() {
            @Override protected WeChatContactCollector createContactCollector() {
                return new WeChatContactCollector() {
                    @Override public List<ContactInfo> collect(Consumer<String> progress) {
                        count.incrementAndGet(); progress.accept("Reading");
                        ContactInfo a = new ContactInfo(), b = new ContactInfo();
                        a.username="first";b.username="second";a.nickname="Same";b.nickname="Same";
                        return List.of(a,b);
                    }
                };
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> { panel.getView(); field(panel,"wxcAutoCollectBtn",JButton.class).doClick(); });
            await(() -> field(panel,"wxcTableModel",DefaultTableModel.class).getRowCount()==2);
            assertEquals(1,count.get());
            SwingUtilities.invokeAndWait(() -> assertEquals(I18n.get("wechat.collect.start"),field(panel,"wxcAutoCollectBtn",JButton.class).getText()));
        } finally { SwingUtilities.invokeAndWait(panel::closeResources); }
    }

    @Test void cancelledOldResultDoesNotOverwriteNewCollection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var sessions = new java.util.concurrent.atomic.AtomicInteger();
        WeChatPanel panel = new WeChatPanel() {
            @Override protected WeChatContactCollector createContactCollector() {
                int session = sessions.incrementAndGet();
                return new WeChatContactCollector() {
                    @Override public List<ContactInfo> collect(Consumer<String> progress) throws Exception {
                        if (session==1) { entered.countDown(); while (release.getCount()>0) { try { release.await(10,TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {} } }
                        ContactInfo row = new ContactInfo();row.username=session==1?"old":"new";return List.of(row);
                    }
                };
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {panel.getView();field(panel,"wxcAutoCollectBtn",JButton.class).doClick();});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {field(panel,"wxcAutoCollectBtn",JButton.class).doClick();field(panel,"wxcAutoCollectBtn",JButton.class).doClick();});
            await(() -> field(panel,"wxcTableModel",DefaultTableModel.class).getRowCount()==1);
            release.countDown();
            SwingUtilities.invokeAndWait(() -> assertEquals("new",field(panel,"wxcTableModel",DefaultTableModel.class).getValueAt(0,5)));
        } finally {release.countDown();SwingUtilities.invokeAndWait(panel::closeResources);}
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);AtomicBoolean done=new AtomicBoolean();
        while(System.nanoTime()<end){SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(20);}
        fail("Contact collection did not finish");
    }
    private static <T> T field(Object target,String name,Class<T> type) {
        try {Field field=WeChatPanel.class.getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(target));}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
}
