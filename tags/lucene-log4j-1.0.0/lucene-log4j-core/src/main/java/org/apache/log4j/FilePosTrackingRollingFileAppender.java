package org.apache.log4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.log4j.helpers.CountingQuietWriter;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * FilePosTrackingRollingFileAppender extends RollingFileAppender by also
 * tracking log info in a Lucene index for rapid lookup.
 * 
 * @author Cheng Lee
 */
public class FilePosTrackingRollingFileAppender extends RollingFileAppender {

  /**
   * Represents the default flush interval for the Lucene index.
   */
  private static final int DEFAULT_INDEX_FLUSH_INTERVAL = 5000;

  /**
   * This is the suffix that will be added to the {@link #filename} set by
   * {@link #setFile(String)} to form the final directory name of the Lucene
   * index.
   */
  private static final String LUCENE_SUFFIX = "_lucene";

  /**
   * The Lucene {@link Directory} where the index will be stored.
   */
  private Directory directory;

  /**
   * The Lucene {@link IndexWriter} used for adding {@link Document}s to index.
   */
  private IndexWriter indexWriter;

  /**
   * This is the fully qualified name of the Lucene analyzer to use. It must
   * extend {@link Analyzer}.
   */
  private String analyzerClass;

  /**
   * Represents the milliseconds to wait before committing changes to Lucene
   * index.
   */
  private int indexFlushInterval = DEFAULT_INDEX_FLUSH_INTERVAL;

  /**
   * A {@link List} of {@link RollOverListener}s to notify after
   * {@link #rollOver()} event.
   */
  private static List rollOverListeners = new ArrayList();

  /**
   * Default constructor.
   */
  public FilePosTrackingRollingFileAppender() {
    Thread flushThread = new Thread(new Runnable() {

      public void run() {
        try {
          while (true) {
            Thread.sleep(indexFlushInterval);

            synchronized (FilePosTrackingRollingFileAppender.this) {
              // Flush index to disk
              closeIndex();

              init();
            }
          }
        } catch (InterruptedException e) {
          // Do nothing since close() will relase all resources
        }
      }

    });

    // Kick off flush thread
    flushThread.start();
  }

  /**
   * {@inheritDoc}
   */
  public synchronized void setFile(String fileName, boolean append,
      boolean bufferedIO, int bufferSize) throws IOException {
    super.setFile(fileName, append, bufferedIO, bufferSize);
    init();
  }

  /**
   * Initializes the {@link #indexWriter} by either reading an existing Lucene
   * index or creating a new, empty one.
   */
  private void init() {
    Analyzer analyzer = getAnalyzer();

    String path = fileName + LUCENE_SUFFIX;

    boolean shouldCreate = checkOrCreateLuceneDir(path);

    while (indexWriter == null) {
      try {
        // Create an empty index
        directory = FSDirectory.getDirectory(path, shouldCreate);

        indexWriter = new IndexWriter(directory, analyzer, shouldCreate);
      } catch (FileNotFoundException e) {
        // Check if it's related to corrupt index (segment not found)
        String message = e.getMessage();
        if (message != null && message.indexOf("segment") != -1) {
          // Force re-creation of index
          shouldCreate = true;
        }
      } catch (IOException e) {
        // Check if it's related to write lock, release if so
        String message = e.getMessage();
        if (message != null && message.indexOf("Lock obtain timed out") != -1) {
          String lockFile = getLockFile(path);
          if (!new File(lockFile).delete()) {
            LogLog
                .error(
                    "Unable to delete lucene write lock file for FilePosTrackingRollingFileAppender with name "
                        + name, e);

            return;
          }

          // Do not allow overwriting of index
          shouldCreate = false;
        }
      }
    }
  }

  /**
   * Returns the {@link Analyzer} to be used.
   * 
   * @return a {@link Analyzer}
   */
  private Analyzer getAnalyzer() {
    if (analyzerClass != null) {
      try {
        return (Analyzer) Class.forName(analyzerClass).newInstance();
      } catch (ClassNotFoundException e) {
        // Do nothing
      } catch (InstantiationException e) {
        LogLog.error("Could not use the specified analyzerClass"
            + analyzerClass);
      } catch (IllegalAccessException e) {
        LogLog.error("Could not use the specified analyzerClass"
            + analyzerClass);
      }
    }

    return new WhitespaceAnalyzer();
  }

  /**
   * Represents the fully qualified class name to be used as alternative
   * {@link Analyzer}.
   * 
   * @param clazz
   *          The fully qualified name.
   */
  public void setAnalyzerClass(String clazz) {
    analyzerClass = clazz;
  }

  /**
   * Obtains the Lucene lock file so we can delete it after a crash.
   * 
   * @param path
   *          The directory where we are storing the Lucene index.
   * 
   * @return The path to the lock file.
   */
  private String getLockFile(String path) {
    File luceneIndexDir = new File(path);
    String luceneIndexDirPath = luceneIndexDir.getAbsolutePath();

    String lockFileDir = System.getProperty("org.apache.lucene.lockdir", System
        .getProperty("java.io.tmpdir"));

    String lockFile = lockFileDir + File.separatorChar + "lucene-"
        + DigestUtils.md5Hex(luceneIndexDirPath) + "-write.lock";

    return lockFile;
  }

  /**
   * Attempts to create a directory for storing Lucene index.
   * 
   * @param path
   *          the directory name at which we want to create the index.
   * 
   * @return True if successful
   */
  private boolean checkOrCreateLuceneDir(String path) {
    File file = new File(path);

    boolean isBrandNewDir = false;
    if (!file.exists()) {
      isBrandNewDir = file.mkdir();
      if (!isBrandNewDir) {
        throw new RuntimeException("Unable to create lucene index dir at: "
            + path);
      }
    }

    return isBrandNewDir;
  }

  /**
   * This method differentiates FilePosTrackingRollingFileAppender from its
   * super class.
   */
  protected synchronized void subAppend(LoggingEvent event) {
    // Keep track of the file offset at the beginning of the log statement
    long fileLen = ((CountingQuietWriter) qw).getCount();
    // MDC.put("fileOffset", fileLen);
    writeToLucene(fileLen, event);

    // Call super class method
    super.subAppend(event);
  }

  /**
   * Extends {@link org.apache.log4j.RollingFileAppender.rollOver} by also
   * rotating the lucene index directories.
   * 
   * 
   * <p>
   * If <code>MaxBackupIndex</code> is positive, then directories {
   * <code>Dir.1</code> , ..., <code>Dir.MaxBackupIndex -1</code> are renamed to
   * { <code>Dir.2</code>, ..., <code>Dir.MaxBackupIndex</code> . Moreover,
   * <code>Dir</code> is renamed <code>Dir.1</code> and closed. A new
   * <code>Dir</code> is created to receive further log output.
   * 
   * <p>
   * If <code>MaxBackupIndex</code> is equal to zero, then the <code>File</code>
   * is truncated with no backup files created.
   */
  public// synchronization not necessary since doAppend is already synched
  void rollOver() {
    // Notify listeners to release file lock
    for (Iterator iterator = rollOverListeners.iterator(); iterator.hasNext();) {
      RollOverListener listener = (RollOverListener) iterator.next();
      listener.signalRollOver();
    }

    File target;
    File file;

    LogLog.debug("rolling over count=" + ((CountingQuietWriter) qw).getCount());
    LogLog.debug("maxBackupIndex=" + maxBackupIndex);

    // If maxBackups <= 0, then there is no file renaming to be done.
    String dirName = fileName + LUCENE_SUFFIX;
    if (maxBackupIndex > 0) {
      // Delete the oldest file, to keep Windows happy.
      file = new File(dirName + '.' + maxBackupIndex);
      if (file.exists()) {
        // Recursively delete directory content
        deleteRecursively(file);
      }

      // Map {(maxBackupIndex - 1), ..., 2, 1} to {maxBackupIndex, ..., 3, 2}
      for (int i = maxBackupIndex - 1; i >= 1; i--) {
        file = new File(dirName + "." + i);
        if (file.exists()) {
          target = new File(dirName + '.' + (i + 1));
          LogLog.debug("Renaming directory " + file + " to " + target);
          file.renameTo(target);
        }
      }

      // Rename fileName to fileName.1
      target = new File(dirName + "." + 1);

      // Close index before moving
      closeIndex();

      file = new File(dirName);
      LogLog.debug("Renaming directory " + file + " to " + target);
      file.renameTo(target);
    }

    // Create directory for Lucene
    file = new File(dirName);
    file.mkdir();

    // Create an Lucene index in the above dir
    init();

    // Call parent method
    super.rollOver();
  }

  /**
   * Delete file or directory recursively.
   * 
   * @param file
   *          The {@link File} representing the location to delete
   */
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
   * Writes to Lucene index.
   * 
   * @param fileLen
   *          The current position at log file.
   * @param event
   *          The {@link LoggingEvent} to be logged.
   */
  private void writeToLucene(long fileLen, LoggingEvent event) {
    Document doc = new Document();
    populateDocument(fileLen, event, doc);
    try {
      indexWriter.addDocument(doc);
    } catch (IOException e) {
      LogLog.error("Could not add doc to index ", e);
    }
  }

  /**
   * This is the override point. You should populate the Lucene document using
   * your own business needs. One possible way is to use {@link MDC} to pass on
   * debugging context to this appender.
   * 
   * @param fileLen
   *          This is the position where the log statement will be recorded.
   * @param event
   *          This is the {@link LoggingEvent}
   * @param doc
   *          This is the {@link Document} where you should add your fields.
   */
  public void populateDocument(long fileLen, LoggingEvent event, Document doc) {
    doc.add(Field.Keyword("uuid", "" + event.getThreadName()));
    doc.add(Field.UnIndexed("fileOffset", "" + fileLen));
    doc
        .add(Field
            .Keyword("currentTimeMillis", "" + System.currentTimeMillis()));
  }

  /**
   * Close any previously opened file and call the parent's <code>reset</code>.
   */
  protected void reset() {
    closeIndex();

    super.reset();
  }

  /**
   * Closes Lucene index.
   */
  private void closeIndex() {
    try {
      if (indexWriter != null) {
        indexWriter.close();
        indexWriter = null;
      }
    } catch (IOException e) {
      // Exceptionally, it does not make sense to delegate to an
      // ErrorHandler. Since a closed appender is basically dead.
      LogLog.error("Could not close " + indexWriter, e);
    }
  }

  /**
   * Adds a {@link RollOverListener} to be notified of {@link #rollOver()}
   * events.
   * 
   * @param listener
   *          a {@link RollOverListener}.
   */
  public static void addRollOverListener(RollOverListener listener) {
    rollOverListeners.add(listener);
  }

  /**
   * This is used to signal a flush of Lucene index to disk so searchers in
   * other VMs or systems can see the changes.
   */
  public synchronized static void signalFlush() {
    // FilePosTrackingRollingFileAppender instance =
    // (FilePosTrackingRollingFileAppender) instanceStaticRef.get();
    //    
    // // We only close Lucene indexes (no need to close log file)
    // instance.closeIndex();
  }

  /**
   * Sets the {@link #indexFlushInterval} which represents the milliseconds to
   * wait before committing changes to Lucene index.
   * 
   * @param indexFlushInterval The time in milliseconds
   */
  public void setIndexFlushInterval(int indexFlushInterval) {
    this.indexFlushInterval = indexFlushInterval;
  }
}
