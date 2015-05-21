FilePosTrackingRollingFileAppender Configuration.

# Parameters #

The FilePosTrackingRollingFileAppender receives the same parameters than the traditional RollingFileAppender and extends it with more parameters shown below:


| Name | Description | Optional |
|:-----|:------------|:---------|
| indexFlushInterval | Milliseconds that must pass before synchronizing Lucene index to disk (though it could also be triggered by `rollover()` event. Defaults to 5000 millis | Yes      |
| analyzerClass | The fully qualified Lucene analyzer class name in case you need specialized analyzer for your problem domain. Must extend `org.apache.lucene.analysis.Analyzer` | Yes      |


# Examples #

```
log4j.appender.A1=com.googlecode.lucene_log4j.FilePosTrackingRollingFileAppender
log4j.appender.A1.file=server.log
log4j.appender.A1.layout=org.apache.log4j.PatternLayout
log4j.appender.A1.layout.ConversionPattern=%-5p [%c] - %m%n
log4j.appender.A1.MaxFileSize=1KB
log4j.appender.A1.MaxBackupIndex=4

log4j.logger.myLogger=ERROR, A1
```