package com.aqishi.toolbox.misc.ssh.session;

import com.jcraft.jsch.ChannelShell;
import com.jediterm.terminal.Questioner;
import com.jediterm.terminal.TtyConnector;

import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 桥接 JediTerm 终端模拟器与 JSch SSH ChannelShell 的 TtyConnector
 */
public class SshTtyConnector implements TtyConnector {

    private final ChannelShell channel;
    private final InputStreamReader reader;
    private final OutputStream out;
    private volatile boolean closed = false;

    public SshTtyConnector(ChannelShell channel, InputStream in, OutputStream out) {
        this.channel = channel;
        this.reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        this.out = out;
    }

    @Override
    public boolean init(Questioner questioner) {
        return true;
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (closed || out == null) return;
        try {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            close();
            throw new IOException(e);
        }
    }

    @Override
    public void write(String string) throws IOException {
        if (string != null) {
            write(string.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        if (closed || reader == null) return -1;
        try {
            int count = reader.read(buf, offset, length);
            if (count < 0) {
                close();
            }
            return count;
        } catch (Exception e) {
            close();
            return -1;
        }
    }

    @Override
    public boolean isConnected() {
        return !closed && channel != null && channel.isConnected();
    }

    @Override
    public boolean ready() {
        try {
            return !closed && reader != null && reader.ready();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "SSH Shell";
    }

    @Override
    public int waitFor() throws InterruptedException {
        while (isConnected()) {
            Thread.sleep(100);
        }
        return 0;
    }

    @Override
    public void resize(Dimension winSize) {
        if (channel != null && channel.isConnected() && winSize != null) {
            try {
                channel.setPtySize(winSize.width, winSize.height, winSize.width * 8, winSize.height * 16);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void resize(Dimension winSize, Dimension pixelSize) {
        resize(winSize);
    }

    @Override
    public void resize(com.jediterm.core.util.TermSize termSize) {
        if (termSize != null) {
            resize(new Dimension(termSize.getColumns(), termSize.getRows()));
        }
    }
}
