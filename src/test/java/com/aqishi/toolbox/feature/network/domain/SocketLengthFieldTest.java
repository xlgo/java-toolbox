package com.aqishi.toolbox.feature.network.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SocketLengthFieldTest {
    private SocketFrameSplitter splitter(int width,boolean little,boolean includes,boolean strip){
        return new SocketFrameSplitter(SocketFrameSplitter.Config.lengthField(new SocketFrameSplitter.LengthField(1,width,little,width+2,includes,strip),128));
    }
    private byte[] frame(int width,boolean little,boolean includes,byte[] payload){
        byte[] bytes=new byte[width+2+payload.length];bytes[0]=0x55;bytes[width+1]=0x33;
        int length=includes?bytes.length:payload.length;
        for(int i=0;i<width;i++)bytes[1+i]=(byte)(length>>>(8*(little?i:width-1-i)));
        System.arraycopy(payload,0,bytes,width+2,payload.length);return bytes;
    }
    @ParameterizedTest @ValueSource(ints={1,2,4})
    void arbitraryChunkBoundariesBothEndiannessesAndHeaderModes(int width){
        for(boolean little:new boolean[]{false,true})for(boolean includes:new boolean[]{false,true})for(boolean strip:new boolean[]{false,true}){
            byte[] input=frame(width,little,includes,new byte[]{1,2,3,4});
            for(int cut=1;cut<input.length;cut++){
                var splitter=splitter(width,little,includes,strip);
                assertTrue(splitter.feed(input,0,cut,0).isEmpty());
                var frames=splitter.feed(input,cut,input.length-cut,1);assertEquals(1,frames.size());
                assertArrayEquals(strip?new byte[]{1,2,3,4}:input,frames.get(0));assertEquals(0,splitter.pending());
            }
        }
    }
    @Test void coalescedFramesAndEmptyPayloadArePreserved(){
        var splitter=splitter(1,false,false,true);
        var data=new java.io.ByteArrayOutputStream();data.writeBytes(frame(1,false,false,new byte[0]));data.writeBytes(frame(1,false,false,new byte[]{9}));
        var frames=splitter.feed(data.toByteArray(),0);assertEquals(2,frames.size());assertArrayEquals(new byte[0],frames.get(0));assertArrayEquals(new byte[]{9},frames.get(1));
    }
    @Test void oversizedUnsignedLengthFailsBeforeBufferingPayload(){
        var splitter=splitter(4,false,false,false);
        assertThrows(IllegalArgumentException.class,()->splitter.feed(new byte[]{0, -1,-1,-1,-1},0));assertEquals(0,splitter.pending());
    }
    @Test void totalLengthCannotBeSmallerThanHeader(){
        assertThrows(IllegalArgumentException.class,()->splitter(1,false,true,false).feed(new byte[]{0,1},0));
    }
    @Test void validatesLayoutAndFrameLimit(){
        assertThrows(IllegalArgumentException.class,()->new SocketFrameSplitter.LengthField(0,3,false,3,false,false));
        assertThrows(IllegalArgumentException.class,()->new SocketFrameSplitter.LengthField(2,4,false,5,false,false));
        var config=SocketFrameSplitter.Config.lengthField(new SocketFrameSplitter.LengthField(0,2,false,2,false,false),128);
        assertThrows(IllegalArgumentException.class,()->config.withMaxFrameSize(1));
        assertEquals(config.getLengthField(),config.withMaxFrameSize(256).getLengthField());
    }
}
