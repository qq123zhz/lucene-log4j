package org.apache.log4j;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.util.Collections;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Hits;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * This servlet works closely with {@link FilePosTrackingRollingFileAppender} to
 * provide rapid search of log content with index created at
 * {@link FilePosTrackingRollingFileAppender}. You should extend
 * {@link FilePosTrackingRollingFileAppender} to add your own business behavior
 * and index the info you need, e.g. the transaction ID.<br>
 * Note: The security should be handled at {@link javax.servlet.Filter} level to
 * disallow unauthorized access to your production logs.
 * 
 * TODO Explain configuration parameters
 * 
 * @author cheng.lee@gmail.com (Cheng Lee)
 */
public class LuceneLogSearchServlet extends HttpServlet {

  /**
   * Generated serial version.
   */
  private static final long serialVersionUID = 8832008349476108670L;

  /**
   * Lucene index directory name.
   */
  private String luceneDir;

  /**
   * Log file name.
   */
  private String logFile;

  /**
   * Represents how many backup history should we look back for.
   */
  private int maxBackupIndex;

  /**
   * The character set used to write to log file AND to print the results.
   */
  private String charset;

  /**
   * The directory where logs are located. Supports absolute path or
   * placeholders with environment variables with prefix "${env." and suffix
   * "}". E.g. ${env.JBOSS_HOME}/log with JBOSS_HOME=/jboss gets expanded to
   * /jboss/log.
   */
  private String logDir;

  /**
   * Indicates that we have received a
   * {@link FilePosTrackingRollingFileAppender#rollover} event.
   */
  private static boolean isRolledOver;

  /**
   * The default buffer size used.
   */
  private static final int BUFFER_SIZE = 4096;

  /**
   * {@inheritDoc}
   */
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    // Signal flush to the appender in this JVM to flush index to disk
    // FilePosTrackingRollingFileAppender.signalFlush();

    resp.addHeader("Content-Type", "text/plain");

    String luceneQuery = req.getParameter("query");
    boolean isDebug = Boolean.valueOf(req.getParameter("debug")).booleanValue();

    // Kick off searches
    for (int i = 0; i <= maxBackupIndex; i++) {
      // Determine index dir and log file names
      String indexDir = logDir + File.separatorChar + luceneDir;
      String currentLogFile = logDir + File.separatorChar + logFile;
      if (i != 0) {
        indexDir += "." + i;
        currentLogFile += "." + i;
      }

      // Validate that index and log exist
      boolean indexDirExists = new File(indexDir).exists();
      boolean logFileExists = new File(currentLogFile).exists();

      // Open lucene index
      if (indexDirExists && logFileExists) {
        Directory directory = FSDirectory.getDirectory(indexDir, false);
        doSearch(resp, directory, luceneQuery, currentLogFile, isDebug);
      }

      // Abort if received roll over event
      if (isRolledOver) {
        PrintWriter writer = resp.getWriter();
        for (int j = 0; j < 10; j++) {
          writer.println("***************************");
        }
        writer.print("WARNING: log file has been rolled over!"
            + " Don't trust on the search results and re-run the quey");
        writer.flush();

        isRolledOver = false;

        break;
      }
    }
  }

  /**
   * Search the lucene index.
   * 
   * @param resp
   *          The {@link HttpServletResponse} to write the response to.
   * @param directory
   *          The lucene index dir.
   * @param luceneQuery
   *          The lucene query.
   * @param logFile
   *          The log file from which we want to extract fragments.
   * @param isDebug
   *          If true then also print header/footer indicating which file the
   *          result belongs.
   * 
   * @throws IOException
   *           If any file operation exceptin occurs during searching/retrieving
   *           log file fragments.
   * @throws FileNotFoundException
   *           If the log file cannot be found.
   * @throws UnsupportedEncodingException
   *           If the encoding specified at {@link #charset} turns out to be not
   *           supported.
   */
  private void doSearch(HttpServletResponse resp, Directory directory,
      String luceneQuery, String logFile, boolean isDebug) throws IOException,
      FileNotFoundException, UnsupportedEncodingException {
    // Create index searcher
    IndexSearcher indexSearcher = new IndexSearcher(directory);

    // Open log
    File log = new File(logFile);
    RandomAccessFile randomAccessFile = new RandomAccessFile(log, "r");
    randomAccessFile.getChannel().lock(0, log.length(), true).release();

    // Open response writer
    PrintWriter writer = resp.getWriter();

    // Run the query
    Hits hits = null;
    try {
      QueryParser queryParser = new QueryParser("uuid",
          new WhitespaceAnalyzer());
      Query query = queryParser.parse(luceneQuery);
      hits = indexSearcher.search(query, new Sort("currentTimeMillis"));
    } catch (RuntimeException e) {
      if (e.toString().indexOf("java.lang.RuntimeException: no terms in field") != -1) {
        // This usually means an empty index so search cannot be performed so
        // just return without writing results
        return;
      } else {
        throw e;
      }
    } catch (ParseException e) {
      resp.setStatus(500);
      writer.print("System unavailable");
      e.printStackTrace(writer);

      return;
    }

    // Print header
    int hitsLength = hits.length();
    if (hitsLength > 0 && isDebug) {
      writer.println();
      writer.println("****************** Start of File: " + log
          + " ******************");
      writer.println();
      writer.flush();
    }

    for (int i = 0; i < hitsLength; i++) {
      // Obtain start offset
      long fileOffset = getStartOffset(hits, i);

      // Obtain end offset
      long nextFileOffset = getEndOffset(indexSearcher, hits.id(i),
          indexSearcher.maxDoc());
      boolean lastRecord = false;
      if (nextFileOffset < 0) {
        // Set the offset to the EOF. It will print unmatched log statements
        // but
        // we can live with that (Just state that it's the last record)
        nextFileOffset = randomAccessFile.length();
        lastRecord = true;
      }

      // Calculate the bytes to read
      long bytesToRead = nextFileOffset - fileOffset;

      // Seek to start offset
      randomAccessFile.seek(fileOffset);

      // Prepare input/output buffers
      byte[] bytes = new byte[BUFFER_SIZE];
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

      // Read log fragment
      int accumulator = 0;
      while (accumulator < bytesToRead) {
        int count = randomAccessFile.read(bytes, 0, BUFFER_SIZE);
        if (count < 0) {
          // Can't read more bytes. Exit
          break;
        }

        byteArrayOutputStream.write(bytes, 0, count);
        accumulator += count;
      }
      String logStatement = byteArrayOutputStream.toString(charset);
      String logContent = logStatement.substring(0, (int) bytesToRead);

      // Write log fragment
      if (lastRecord) {
        writer
            .println("This is the last record of the log file so printing until EOF");
      }
      writer.print(logContent);
      writer.flush();
    }

    // Print footer
    if (hitsLength > 0 && isDebug) {
      writer.println();
      writer.println("****************** End of File: " + log
          + " ******************");
      writer.println();
      writer.flush();
    }

    // Close log file
    randomAccessFile.close();

    // Close search index
    indexSearcher.close();
  }

  private long getStartOffset(Hits hits, int i) throws IOException {
    Document doc = hits.doc(i);
    String fileOffsetString = doc.get("fileOffset");
    long fileOffset = Long.parseLong(fileOffsetString);

    return fileOffset;
  }

  private long getEndOffset(IndexSearcher indexSearcher, int i, int maxDoc)
      throws IOException {
    if (i + 1 >= maxDoc) {
      // This restrictive number will make sure that the line look cut so the
      // user will try again
      return -1;
    }

    Document nextDoc = indexSearcher.doc(i + 1);
    String nextFileOffsetString = nextDoc.get("fileOffset");
    long nextFileOffset = Long.parseLong(nextFileOffsetString);

    return nextFileOffset;
  }

  public void init(ServletConfig config) throws ServletException {
    luceneDir = config.getInitParameter("luceneDir");
    logFile = config.getInitParameter("logFile");
    logDir = getLogDir(config.getInitParameter("logDir"));
    charset = config.getInitParameter("charset");
    String maxBackupIndexString = config.getInitParameter("maxBackupIndex");
    maxBackupIndex = Integer.parseInt(maxBackupIndexString);

    // Subscribe to {@link FilePosTrackingRollingFileAppender#rollover} event
    FilePosTrackingRollingFileAppender
        .addRollOverListener(new RollOverListener() {

          public void signalRollOver() {
            isRolledOver = true;
          }
        });
  }

  private String getLogDir(String logDirectory) {
    String finalValue = new PlaceholderUtil().replace(logDirectory, "${", "}",
        Collections.EMPTY_MAP, true);
    return finalValue;
  }
}
