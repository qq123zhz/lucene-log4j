package com.googlecode.lucene_log4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;
import org.apache.log4j.PropertyConfigurator;

/**
 * This servlet just logs what comes in.
 * 
 * @author cheng.lee@gmail.com (Cheng Lee)
 */
public class LogServlet extends HttpServlet {

  /**
   * Generated serial ID.
   */
  private static final long serialVersionUID = 2658267381420890004L;

  private Logger logger = Logger.getLogger("myLogger");
  
  /**
   * {@inheritDoc}
   */
  protected void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    BufferedReader reader = req.getReader();
    String line;
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
    while ((line = reader.readLine()) != null) {
      writer.append(line);
    }
    writer.close();
    
    String sessionId = req.getSession().getId();
    MDC.put("JSESSION", sessionId);
    logger.info(outputStream);
    
    resp.addHeader("Content-Type", "text/plain");

    resp.getWriter().write(sessionId + ": OK\n" + outputStream);
  }
  public void init() throws ServletException {
    Properties originalProperties = new Properties();
    try {
      originalProperties.load(getClass().getResourceAsStream("/log4j.properties"));
    } catch (IOException e) {
      throw new ServletException("Error loading Log4j properties", e);
    }
    
    Properties properties = new Properties();
    
    Set entrySet = originalProperties.entrySet();
    for (Iterator iterator = entrySet.iterator(); iterator.hasNext();) {
      Map.Entry entry = (Map.Entry) iterator.next();
      String newValue = replacePlaceholderValues((String) entry.getValue());
      properties.put(entry.getKey(), newValue);
    }

    PropertyConfigurator.configure(properties);
  }

  private String replacePlaceholderValues(String logDirectory) {
    String finalValue = new PlaceholderUtil().replace(logDirectory, "${", "}", Collections.EMPTY_MAP, true);
    return finalValue;
  }

  public void destroy() {
    LogManager.shutdown();
  }
}
