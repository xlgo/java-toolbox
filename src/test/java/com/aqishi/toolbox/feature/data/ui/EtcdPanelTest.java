package com.aqishi.toolbox.feature.data.ui;

import com.aqishi.toolbox.feature.data.application.EtcdClient;
import com.aqishi.toolbox.feature.data.domain.EtcdModels.*;
import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class EtcdPanelTest {
    @Test void bothApisCaptureTheSelectedSshServerAndLockTheRouteUntilDisconnect() throws Exception {
        for (Api api : Api.values()) {
            AtomicReference<Options> captured = new AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            EtcdPanel panel = new EtcdPanel() {
                @Override protected EtcdClient openClient(Options options) {
                    captured.set(options);
                    return new EtcdClient(options, null) {
                        @Override public String connect() throws java.io.IOException {
                            entered.countDown();
                            try { if (!release.await(4, TimeUnit.SECONDS)) throw new java.io.IOException("test timed out"); }
                            catch (InterruptedException error) { throw new java.io.IOException(error); }
                            return "3.5.34";
                        }
                        @Override public Page list(byte[] prefix, int limit, byte[] start, long revision) { return new Page(List.of(), null, 1, false); }
                        @Override public void cancel() { }
                        @Override public void close() { }
                    };
                }
            };
            try {
                SwingUtilities.invokeAndWait(panel::getView);
                SwingUtilities.invokeAndWait(() -> {
                    SshConnectionConfig server = new SshConnectionConfig();
                    server.setId("selected-bastion"); server.setName("Test bastion");
                    JComboBox<SshConnectionConfig> combo = field(panel, "sshCombo", JComboBox.class);
                    combo.addItem(server); combo.setSelectedItem(server);
                    JCheckBox enabled = field(panel, "useSshCheck", JCheckBox.class);
                    assertTrue(enabled.isShowing() || SwingUtilities.isDescendingFrom(enabled, panel.getView()));
                    assertFalse(SwingUtilities.isDescendingFrom(enabled, field(panel, "settingsBody", JPanel.class)));
                    assertFalse(combo.isEnabled()); enabled.doClick(); assertTrue(combo.isEnabled());
                    field(panel, "apiCombo", JComboBox.class).setSelectedItem(api);
                    field(panel, "connectBtn", JButton.class).doClick();
                    assertFalse(enabled.isEnabled()); assertFalse(combo.isEnabled());
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals("selected-bastion", captured.get().sshConfigId());
                assertEquals(api, captured.get().api());
                release.countDown();
                await(() -> field(panel, "disconnectBtn", JButton.class).isEnabled());
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(field(panel, "useSshCheck", JCheckBox.class).isEnabled());
                    assertFalse(field(panel, "sshCombo", JComboBox.class).isEnabled());
                    assertTrue(field(panel, "statusLabel", JLabel.class).getText().contains("Test bastion"));
                    field(panel, "disconnectBtn", JButton.class).doClick();
                    assertTrue(field(panel, "useSshCheck", JCheckBox.class).isEnabled());
                    assertTrue(field(panel, "sshCombo", JComboBox.class).isEnabled());
                });
            } finally { release.countDown(); SwingUtilities.invokeAndWait(panel::closeResources); }
        }
    }

    @Test void defaultViewIsDisconnectedAndCancellationCannotApplyALateConnection() throws Exception {
        AtomicInteger closes=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        EtcdPanel panel=new EtcdPanel(){
            @Override protected EtcdClient openClient(Options options){return new EtcdClient(options,null){
                @Override public String connect(){entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return "3.5.34";}
                @Override public Page list(byte[] prefix,int limit,byte[] start,long revision){return new Page(List.of(),null,1,false);}
                @Override public void cancel(){}
                @Override public void close(){closes.incrementAndGet();}
            };}
        };
        try{
            SwingUtilities.invokeAndWait(()->{
                panel.getView();assertFalse(field(panel,"sshCombo",JComboBox.class).isEnabled());
                assertFalse(field(panel,"saveBtn",JButton.class).isEnabled());
                field(panel,"apiCombo",JComboBox.class).setSelectedItem(Api.V2);
                assertEquals("/",field(panel,"queryField",JTextField.class).getText());
                field(panel,"connectBtn",JButton.class).doClick();
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->{field(panel,"cancelBtn",JButton.class).doClick();assertFalse(field(panel,"connectBtn",JButton.class).isEnabled());});
            release.countDown();await(()->field(panel,"connectBtn",JButton.class).isEnabled());
            assertEquals(1,closes.get());assertNull(field(panel,"client",EtcdClient.class));
        }finally{release.countDown();SwingUtilities.invokeAndWait(panel::closeResources);}
    }
    @Test void binaryEntryUsesBase64AndSaveKeepsReadRevision() throws Exception {
        AtomicReference<byte[]> captured=new AtomicReference<>();AtomicLong revision=new AtomicLong();
        Entry entry=new Entry(new byte[]{'k'},new byte[]{0,(byte)255},1,7,2,0,-1,false);
        EtcdPanel panel=new EtcdPanel(){
            @Override protected EtcdClient openClient(Options options){return new EtcdClient(options,null){
                @Override public String connect(){return "3.5.34";}
                @Override public Page list(byte[] prefix,int limit,byte[] start,long rev){return new Page(List.of(entry),null,8,false);}
                @Override public void put(byte[] key,byte[] value,long expected,long ttl,boolean directory){captured.set(value);revision.set(expected);}
                @Override public Entry get(byte[] key){return entry;}
                @Override public void cancel(){}
                @Override public void close(){}
            };}
        };
        try{
            SwingUtilities.invokeAndWait(()->{panel.getView();field(panel,"connectBtn",JButton.class).doClick();});
            await(()->field(panel,"disconnectBtn",JButton.class).isEnabled());
            SwingUtilities.invokeAndWait(()->{
                field(panel,"table",JTable.class).setRowSelectionInterval(0,0);
                assertEquals(Encoding.BASE64,field(panel,"valueEncoding",JComboBox.class).getSelectedItem());
                assertEquals("AP8=",field(panel,"valueArea",JTextArea.class).getText());
                assertFalse(field(panel,"keyField",JTextField.class).isEditable());
                field(panel,"saveBtn",JButton.class).doClick();
            });
            await(()->field(panel,"saveBtn",JButton.class).isEnabled());
            assertArrayEquals(entry.value(),captured.get());assertEquals(7,revision.get());
        }finally{SwingUtilities.invokeAndWait(panel::closeResources);}
    }
    private static <T>T field(Object panel,String name,Class<T> type){try{Field field=EtcdPanel.class.getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(panel));}catch(Exception error){throw new AssertionError(error);}}
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);AtomicBoolean done=new AtomicBoolean();while(System.nanoTime()<end){SwingUtilities.invokeAndWait(()->done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(15);}fail("etcd worker did not finish");}
}
