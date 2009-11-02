package org.apache.log4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URL;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase;

import org.easymock.MockControl;

public class LuceLogSearchServletTest extends TestCase {

  public void testGetTxId() throws Exception {
    HttpServletRequest mock = getMockHttpServletRequest();

    LuceneLogSearchServlet luceLogSearchServlet = new LuceneLogSearchServlet();
    luceLogSearchServlet.init(getMockServletConfig());

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    HttpServletResponse resp = getHttpServletResponse(outputStream);

    luceLogSearchServlet.doGet(mock, resp);
    
    InputStream expectedLogInputStream = getClass().getResourceAsStream("luceneLogSearchServlet_expected_output.txt");

    assertLogContent(expectedLogInputStream, new ByteArrayInputStream(outputStream.toByteArray()));
  }

  private HttpServletRequest getMockHttpServletRequest() {
    MockControl control = MockControl.createControl(HttpServletRequest.class);
    HttpServletRequest mock = (HttpServletRequest) control.getMock();
    mock.getParameter("query");
    control.setReturnValue("uuid:main currentTimeMillis:1255833788437");
    mock.getParameter("debug");
    control.setReturnValue("false");

    control.replay();

    return mock;
  }

  private ServletConfig getMockServletConfig() {
    MockControl control = MockControl.createControl(ServletConfig.class);
    ServletConfig mock = (ServletConfig) control.getMock();
    
    // Set the pre-generated lucene sample we have in classpath
    mock.getInitParameter("luceneDir");
    control.setReturnValue("server.log_lucene");

    mock.getInitParameter("logFile");
    control.setReturnValue("server.log");

    mock.getInitParameter("logDir");
    URL logDirResource = getClass().getResource("");
    control.setReturnValue(logDirResource.getFile());

    mock.getInitParameter("charset");
    control.setReturnValue("UTF-8");
    mock.getInitParameter("maxBackupIndex");
    control.setReturnValue("10");
    control.replay();

    return mock;
  }

  private HttpServletResponse getHttpServletResponse(
      final ByteArrayOutputStream outputStream) {
    MockControl control = MockControl.createControl(HttpServletResponse.class);
    HttpServletResponse mock = (HttpServletResponse) control.getMock();
    try {
      mock.getWriter();
    } catch (IOException e) {
      throw new RuntimeException("This should not happen");
    }
    control.setReturnValue(new PrintWriter(outputStream), MockControl.ZERO_OR_MORE);
    mock.addHeader("Content-Type", "text/plain");
    control.replay();

    return mock;
  }

  /**
   * This method is candidate for refactoring to IOUtils.
   * 
   * @param expectedInputStream The expected input.
   * @param actualInputStream The actual input.
   */
  private void assertLogContent(InputStream expectedInputStream, InputStream actualInputStream) {
    try {
      int expected;
      int actual;
      long filepos = 0;
      do {
        expected = expectedInputStream.read();
        actual = actualInputStream.read();
        filepos++;
      } while (expected == actual && expected != -1 && actual != -1);

      if (expected != actual) {
        // This would only be possible if expected and actual differ
        fail("Found difference at pos: " + filepos);
      }
    } catch (IOException e) {
      throw new RuntimeException("Could not read from stream");
    }
  }
}