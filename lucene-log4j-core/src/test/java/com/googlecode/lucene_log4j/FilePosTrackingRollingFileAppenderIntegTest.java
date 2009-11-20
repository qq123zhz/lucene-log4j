package com.googlecode.lucene_log4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.Properties;

import junit.framework.TestCase;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Hits;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import com.googlecode.lucene_log4j.FilePosTrackingRollingFileAppender;

/**
 * This is the integration test on {@link FilePosTrackingRollingFileAppender}.
 * 
 * @author cheng.lee@gmail.com (Cheng Lee)
 */
public class FilePosTrackingRollingFileAppenderIntegTest extends TestCase {
  /**
   * This is our Logger under test.
   */
  private Logger logger = Logger.getLogger("myLogger");

  /**
   * This is our log4j config.
   */
  private Properties log4jConfig;

  /**
   * Represents the location of our log file specified at log4j config.
   */
  private String logFile;

  /**
   * Represents the maxBackupIndex of our log file specified at log4j config.
   */
  private int maxBackupIndex;

  /**
   * Represents the hits that each lucene index should contain in order.
   */
  private int[] expectedHits = { 7, 21, 22 };

  protected void setUp() {
    log4jConfig = new Properties();
    try {
      log4jConfig.load(getClass().getResourceAsStream(
          "FilePosTrackingRollingFileAppenderIntegTest.properties"));

      // Configure the log file location
      String logFilePath = (String) log4jConfig.get("log4j.appender.A1.file");
      String tmpDir = System.getProperty("java.io.tmpdir") + File.separatorChar
          + FilePosTrackingRollingFileAppenderIntegTest.class.getName();
      File testDir = new File(tmpDir);
      testDir.deleteOnExit();
      if (!testDir.exists() && !testDir.mkdir()) {
        throw new RuntimeException("Could not create temp dir for "
            + FilePosTrackingRollingFileAppenderIntegTest.class.getName()
            + ": " + testDir);
      }
      logFile = MessageFormat.format(logFilePath, new String[] { testDir
          .getAbsolutePath()
          + File.separatorChar });
      // Put it back to the properties for later use at PropertyConfigurator
      log4jConfig.put("log4j.appender.A1.file", logFile);

      maxBackupIndex = Integer.parseInt(log4jConfig
          .getProperty("log4j.appender.A1.MaxBackupIndex"));

    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Clean up previous work
    cleanUpLogAndIndex(log4jConfig);

    // Configure Log4j
    PropertyConfigurator.configure(log4jConfig);
  }

  private void cleanUpLogAndIndex(Properties properties) {
    for (int i = 0; i < maxBackupIndex; i++) {
      String logFileName = logFile;
      String indexDirName = logFile + "_lucene";
      if (i != 0) {
        logFileName += "." + i;
        indexDirName += "." + i;
      }

      // Delete log files
      File log = new File(logFileName);
      if (log.exists()) {
        if (!log.delete()) {
          throw new RuntimeException("Could not delete log files: " + log);
        }
      }

      // Delete index dirs
      File indexDir = new File(indexDirName);
      if (indexDir.exists()) {
        deleteRecursively(indexDir);
      }
    }
  }

  private void deleteRecursively(File file) {
    if (file.isFile()) {
      file.delete();

      return;
    }

    // file argument is a directory
    File[] list = file.listFiles();
    for (int i = 0; i < list.length; i++) {
      deleteRecursively(list[i]);
    }

    // By the the directory should be empty so delete it
    file.delete();
  }

  /**
   * Search the lucene index.
   * 
   * @param directory
   *          The lucene index dir.
   * @param luceneQuery
   *          The lucene query.
   * @return The hits count matching the query
   * 
   * @throws IOException
   *           If any file operation exception occurs during
   *           searching/retrieving log file fragments.
   */
  private int doSearch(Directory directory, String luceneQuery)
      throws IOException {
    // Create index searcher
    IndexSearcher indexSearcher = new IndexSearcher(directory);

    // Run the query
    Hits hits = null;
    try {
      QueryParser queryParser = new QueryParser("uuid",
          new WhitespaceAnalyzer());
      Query query = queryParser.parse(luceneQuery);
      hits = indexSearcher.search(query);

      return hits.length();
    } catch (ParseException e) {
      throw new RuntimeException("Cannot parse query");
    } finally {
      indexSearcher.close();
    }
  }

  public void testLogger() throws Exception {
    for (int i = 0; i < 50; i++) {
      logger.error("Test Error message (line " + i + ")");
    }

    // Important, need to shutdown Appender for the most recent log index to be
    // committed, thus avoiding 0 matches on the most recent log
    LogManager.shutdown();

    // Assert logs contain the above statements
    for (int i = 0; i <= 2; i++) {
      assertLogContent(i);
    }

    // Verify that the lucene index contains the right amount of matches
    for (int i = 0; i <= 2; i++) {
      // Determine index dir and log file names
      String indexDir = determineIndexDir(i);
      String currentLogFile = determineCurrentLogFile(i);

      // Validate that index and log exist
      boolean indexDirExists = new File(indexDir).exists();
      boolean logFileExists = new File(currentLogFile).exists();

      // Open lucene index
      if (indexDirExists && logFileExists) {
        Directory directory = FSDirectory.getDirectory(indexDir, false);
        int hits = doSearch(directory, "uuid:main");
        assertEquals(expectedHits[i], hits);
        
        directory.close();
      }
    }
  }

  private void assertLogContent(int i) {
    String expectedLogFileName = determineExpectedLogFile(i);
    String actualLogFileName = determineCurrentLogFile(i);

    InputStream expectedLogInputStream = getClass().getResourceAsStream(
        expectedLogFileName);
    BufferedReader expectedReader = new BufferedReader(new InputStreamReader(expectedLogInputStream));
    InputStream actualLogInputStream;
    try {
      actualLogInputStream = new FileInputStream(actualLogFileName);
    } catch (FileNotFoundException e) {
      throw new RuntimeException("The actual log file \"" + actualLogFileName
          + "\" could not be found", e);
    }
    BufferedReader actualReader = new BufferedReader(new InputStreamReader(actualLogInputStream));

    try {
      String expected;
      String actual;
      long line = 0;
      do {
        expected = expectedReader.readLine();
        actual = actualReader.readLine();
        line++;
      } while (expected != null && actual != null && expected.equals(actual));

      // This would only be possible if expected and actual differ
      assertEquals("Log outputs differ at line: " + line, expected, actual);
    } catch (IOException e) {
      throw new RuntimeException("Could not read from stream");
    } finally {
      try {
        expectedLogInputStream.close();
        actualLogInputStream.close();
      } catch (IOException e) {
        throw new RuntimeException("Could not close stream", e);
      }
    }
  }

  private String determineCurrentLogFile(int i) {
    String currentLogFile = logFile;
    if (i != 0) {
      currentLogFile = logFile + "." + i;
    }

    return currentLogFile;
  }

  private String determineExpectedLogFile(int i) {
    String currentLogFile = "server.log";
    if (i > 0) {
      currentLogFile += "." + i;
    }

    return currentLogFile;
  }

  private String determineIndexDir(int i) {
    String indexDir = logFile + "_lucene";
    if (i > 0) {
      indexDir += "." + i;
    }

    return indexDir;
  }

  protected void tearDown() throws Exception {
    // Clean up all works
    cleanUpLogAndIndex(log4jConfig);
  }
}
