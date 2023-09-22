package net.juniper.netconf;

import com.google.common.base.Charsets;
import com.jcraft.jsch.Channel;
import lombok.extern.slf4j.Slf4j;
import net.juniper.netconf.element.RpcError;
import net.juniper.netconf.element.RpcReply;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlunit.assertj.XmlAssert;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
@Category(Test.class)
public class NetconfSessionTest {

    public static final int CONNECTION_TIMEOUT = 2000;
    public static final int COMMAND_TIMEOUT = 5000;

    private static final String FAKE_HELLO = "fake hello";

    private static final String DEVICE_PROMPT = "]]>]]>";
    private static final byte[] DEVICE_PROMPT_BYTE = DEVICE_PROMPT.getBytes();
    private static final String FAKE_RPC_REPLY = "<rpc>fakedata</rpc>";
    private static final String NETCONF_SYNTAX_ERROR_MSG_FROM_DEVICE = "netconf error: syntax error";

    @Mock
    private NetconfSession mockNetconfSession;
    @Mock
    private DocumentBuilder builder;
    @Mock
    private Channel mockChannel;

    private BufferedOutputStream out;
    private PipedOutputStream outPipe;
    private PipedInputStream inPipe;

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);

        inPipe = new PipedInputStream(8096);
        outPipe = new PipedOutputStream(inPipe);
        PipedInputStream pipeInput = new PipedInputStream(1024);
        out = new BufferedOutputStream(new PipedOutputStream(pipeInput));

        when(mockChannel.getInputStream()).thenReturn(inPipe);
        when(mockChannel.getOutputStream()).thenReturn(out);
    }


    @Test
    public void GIVEN_createSession_WHEN_timeoutExceeded_THEN_throwSocketTimeoutException() {
        Thread thread = new Thread(() -> {
            try {
                outPipe.write(FAKE_RPC_REPLY.getBytes());
                for (int i = 0; i < 7; i++) {
                    outPipe.write(FAKE_RPC_REPLY.getBytes());
                    Thread.sleep(200);
                    outPipe.flush();
                }
                Thread.sleep(200);
                outPipe.close();
            } catch (IOException | InterruptedException e) {
                log.error("error =", e);
            }
        });
        thread.start();

        assertThatThrownBy(() -> createNetconfSession(1000))
                .isInstanceOf(SocketTimeoutException.class)
                .hasMessage("Command timeout limit was exceeded: 1000");
    }

    @Test
    public void GIVEN_createSession_WHEN_connectionClose_THEN_throwSocketTimeoutException() {
        Thread thread = new Thread(() -> {
            try {
                outPipe.write(FAKE_RPC_REPLY.getBytes());
                Thread.sleep(200);
                outPipe.flush();
                Thread.sleep(200);
                outPipe.close();
            } catch (IOException | InterruptedException e) {
                log.error("error =", e);
            }
        });
        thread.start();

        assertThatThrownBy(() -> createNetconfSession(COMMAND_TIMEOUT))
                .isInstanceOf(NetconfException.class)
                .hasMessage("Input Stream has been closed during reading.");
    }

    @Test
    public void GIVEN_executeRPC_WHEN_lldpRequest_THEN_correctResponse() throws Exception {
        byte[] lldpResponse = Files.readAllBytes(TestHelper.getSampleFile("responses/lldpResponse.xml").toPath());
        String expectedResponse = new String(lldpResponse, Charsets.UTF_8)
                .replaceAll(NetconfConstants.CR, NetconfConstants.EMPTY_LINE) + NetconfConstants.LF;

        Thread thread = new Thread(() -> {
            try {
                outPipe.write(FAKE_RPC_REPLY.getBytes());
                outPipe.write(DEVICE_PROMPT_BYTE);
                outPipe.flush();
                Thread.sleep(800);
                outPipe.write(lldpResponse);
                outPipe.flush();
                Thread.sleep(700);
                outPipe.write(DEVICE_PROMPT_BYTE);
                outPipe.flush();
                Thread.sleep(1900);
                outPipe.close();
            } catch (IOException | InterruptedException e) {
                log.error("error =", e);
            }
        });
        thread.start();

        NetconfSession netconfSession = createNetconfSession(COMMAND_TIMEOUT);
        Thread.sleep(200);
        String deviceResponse = netconfSession.executeRPC(TestConstants.LLDP_REQUEST).toString();

        XmlAssert.assertThat(deviceResponse)
            .and(expectedResponse)
            .ignoreWhitespace()
            .areIdentical();
    }
    private NetconfSession createNetconfSession(int commandTimeout) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new NetconfException(String.format("Error creating XML Parser: %s", e.getMessage()));
        }

        return new NetconfSession(mockChannel, CONNECTION_TIMEOUT, commandTimeout, FAKE_HELLO, builder);
    }

    private static void mockResponse(final InputStream is, final String message) throws IOException {
        final String messageWithTerminator = message + NetconfConstants.DEVICE_PROMPT;
        doAnswer(invocationOnMock -> {
            final byte[] buffer = (byte[])invocationOnMock.getArguments()[0];
            final int offset = (int)invocationOnMock.getArguments()[1];
            final int bufferLength = (int)invocationOnMock.getArguments()[2];
            final byte[] messageBytes = messageWithTerminator.getBytes(StandardCharsets.UTF_8);
            if(messageBytes.length > bufferLength ) {
                throw new IllegalArgumentException("Requires more work for long messages");
            }
            System.arraycopy(messageBytes, 0, buffer, offset, messageBytes.length);
            return messageBytes.length;
        }).when(is).read(any(), anyInt(), anyInt());
    }
}