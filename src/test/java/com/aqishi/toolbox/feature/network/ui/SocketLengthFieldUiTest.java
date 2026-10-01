package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.SocketSession;
import com.aqishi.toolbox.feature.network.domain.SocketFrameSplitter;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;

class SocketLengthFieldUiTest {
    @Test void formBuildsLengthConfigurationForClientAndServer() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            for(var kind:new SocketSession.Kind[]{SocketSession.Kind.TCP_CLIENT,SocketSession.Kind.TCP_SERVER}){
                var form=new SocketConnectionForm(kind);form.frameCombo.setSelectedIndex(4);form.lengthOffset.setValue(2);
                form.lengthWidth.setSelectedItem(4);form.lengthOrder.setSelectedIndex(1);form.headerSize.setValue(8);form.frameLimit.setValue(1024);
                form.stripHeader.setSelected(true);form.lengthIncludesHeader.setSelected(true);
                var config=form.frameConfig();assertEquals(SocketFrameSplitter.Mode.LENGTH_FIELD,config.getMode());
                assertEquals(new SocketFrameSplitter.LengthField(2,4,true,8,true,true),config.getLengthField());assertEquals(1024,config.getMaxFrameSize());
            }
        });
    }
}
