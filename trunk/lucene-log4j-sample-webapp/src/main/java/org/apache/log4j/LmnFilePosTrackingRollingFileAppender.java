package org.apache.log4j;

import org.apache.log4j.spi.LoggingEvent;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;

public class LmnFilePosTrackingRollingFileAppender extends
    FilePosTrackingRollingFileAppender {

  public void populateDocument(long fileLen, LoggingEvent event, Document doc) {
//    String uuid = ((UIDSupportingThread) Thread.currentThread()).getUIDs().get("RUID");
//    doc.add(Field.Keyword("uuid", uuid));
    String uuid = (String) MDC.get("JSESSION");
    doc.add(Field.Keyword("uuid", uuid));
    doc.add(Field.UnIndexed("fileOffset", "" + fileLen));
    doc
        .add(Field
            .Keyword("currentTimeMillis", "" + System.currentTimeMillis()));
    doc.add(Field.Text("message", event.getRenderedMessage()));
  }

}
