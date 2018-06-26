package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import javax.servlet.http.HttpServletRequest;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import de.ofahrt.catfish.HttpParserTest;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.api.MalformedRequestException;
import de.ofahrt.catfish.client.HttpConnection;

public class SslHttpParserIntegrationTest extends HttpParserTest {

  private static LocalCatfishServer localServer;

  @BeforeClass
  public static void startServer() throws Exception {
    localServer = new LocalCatfishServer();
    localServer.setStartSsl(true);
    localServer.start();
  }

  @AfterClass
  public static void stopServer() throws Exception {
    localServer.shutdown();
  }

  @After
  public void tearDown() {
    localServer.waitForNoOpenConnections();
  }

  @Override
  public HttpServletRequest parse(byte[] data) throws Exception {
    HttpConnection connection = localServer.connect(true);
    connection.write(data);
    HttpResponse response = connection.readResponse();
    connection.close();
    assertNotNull(response);
    if (response.getStatusCode() != 200) {
      throw new MalformedRequestException(null); //response.getReasonPhrase());
    }
    try (InputStream in = new ByteArrayInputStream(response.getBody())) {
      if (in.available() == 0) {
        return null;
      }
      return SerializableHttpServletRequest.parse(in);
    }
  }

  @Override
  public int getPort() {
    return LocalCatfishServer.HTTPS_PORT;
  }

  @Test
  public void isSecure() throws Exception {
    HttpServletRequest request = parse("GET / HTTP/1.0\n\n");
    assertTrue(request.isSecure());
  }

  @Test
  public void getRequestUrlReturnsAbsoluteUrl() throws Exception {
    assertEquals("https://127.0.0.1:" + getPort() + "/",
        parse("GET / HTTP/1.0\n\n").getRequestURL().toString());
    assertEquals("https://127.0.0.1/",
        parse("GET http://127.0.0.1/ HTTP/1.0\n\n").getRequestURL().toString());
  }
}
