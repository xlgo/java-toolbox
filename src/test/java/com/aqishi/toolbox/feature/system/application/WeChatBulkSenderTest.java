package com.aqishi.toolbox.feature.system.application;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.IOException;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class WeChatBulkSenderTest {
    private static class Listener implements WeChatBulkSender.Listener {
        final ArrayList<WeChatBulkSender.Result> results = new ArrayList<>();
        @Override public void progress(int index,String status) { }
        @Override public void result(WeChatBulkSender.Result result) { results.add(result); }
    }
    private static void read(String text,int count,Listener listener) throws IOException {
        WeChatBulkSender.readProtocol(new BufferedReader(new StringReader(text)),count,listener);
    }
    @Test void serverConfirmedAndUncertainResultsAreDistinct() throws Exception {
        Listener listener=new Listener();
        read("{\"type\":\"result\",\"index\":0,\"outcome\":\"acknowledged\",\"server_id\":123,\"status\":2}\n"
                +"{\"type\":\"result\",\"index\":1,\"outcome\":\"uncertain\",\"reason\":\"no record\"}\n"
                +"{\"type\":\"complete\",\"confirmed\":1,\"total\":2,\"cancelled\":false}\n",2,listener);
        assertEquals(2,listener.results.size());assertEquals("123",listener.results.get(0).serverId());
        assertEquals("uncertain",listener.results.get(1).outcome());
    }
    @Test void keyboardTriggerWithoutServerIdOrExpectedStatusIsNotConfirmation() {
        for(String event:new String[]{"{\"type\":\"result\",\"index\":0,\"outcome\":\"acknowledged\",\"status\":2}",
                "{\"type\":\"result\",\"index\":0,\"outcome\":\"acknowledged\",\"server_id\":123,\"status\":4}"})
            assertThrows(IOException.class,()->read(event,1,new Listener()));
    }
    @Test void incompleteOrDuplicateResultIsRejected() {
        String result="{\"type\":\"result\",\"index\":0,\"outcome\":\"pending\"}\n";
        assertThrows(IOException.class,()->read(result,1,new Listener()));
        assertThrows(IOException.class,()->read(result+result,1,new Listener()));
        assertThrows(IOException.class,()->read("{\"type\":\"complete\",\"confirmed\":0,\"total\":1}",1,new Listener()));
    }
    @Test void cancelledBatchAllowsUnattemptedRows() throws Exception {
        read("{\"type\":\"complete\",\"confirmed\":0,\"total\":2,\"cancelled\":true}",2,new Listener());
    }
}
