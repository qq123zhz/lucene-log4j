package com.googlecode.lucene_log4j;

import org.apache.log4j.MDC;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;

import com.googlecode.lucene_log4j.FilePosTrackingRollingFileAppender;

public class LmnFilePosTrackingRollingFileAppender extends
    FilePosTrackingRollingFileAppender {

  public boolean populateDocument(long fileLen, LoggingEvent event, Document doc) {
    String uuid = (String) MDC.get("JSESSION");
    doc.add(Field.Keyword("uuid", uuid));
    doc.add(Field.UnIndexed("fileOffset", "" + fileLen));
    doc
        .add(Field
            .Keyword("currentTimeMillis", "" + System.currentTimeMillis()));
    doc.add(Field.Text("message", event.getRenderedMessage()));
    
    return true;
  }

}
