# Introduction #

> [lucene-log4j](http://code.google.com/p/lucene-log4j/) solves a recurrent problem that production support team face whenever a live incident happens: filtering production log statements to match a session/transaction/user ID.

# Motivation #

> In production we often find distributed systems colaborate with each other in order to provide services. As messages travels through these systems, they will usually carry an unique ID that identifies the main transaction (which makes sense when a message results in several child messages to be fired, such as a distributed search). Now you can log this ID that you associated with the current thread in your server logs along with other information that you consider useful so you can later come back to the logs in order to find out what happened actually.

# The problem #

> As you are most probably aware, in a busy multi-threaded application server the log statements written in one thread quickly entangles with the ones written by other threads. So in order to filter the log to show only the log statements related to a certain ID you will need to write some tools. Some of them are:

  * **Sequential grep**: This is probably the first thing that can come up. It turns out to be a non-trivial task since you will have to consider multi-line statements. All the same, this is a **sequential operation** which is slow and put unnecessary I/O on your servers.

  * **Replicating logs to a central location and indexing them**: Log4j provides a JMS appender which allows you to send your log over the wire. Then you can store the logs in a central repository and index it according to the ID. The problem with this approach is that you need to have spare spaces on this repository for ALL your production systems which in big clusters means **lots of space and network traffic**.

# My approach #

> To solve the problems stated above (sequetial operation and space/network requirements), I came up with this in-site solution which consists on building a searchable Lucene index in the application deployed on application server. It works by extending Log4j's RollingFileAppender with Lucene indexing routines. Then with a LuceneLogSearchServlet, you get access to your log using web frontend. This solves the former problems and has the benefit of distributing the load on search. Combined with a messaging middleware, e.g. Mule ESB, it's possible to combine the search results and present it all together.

# Limitations #

  * This approach is not perfect since on corner cases where the logs rotate at the moment of the search, it will mess up the results. This is reported on the results though with the message:

```
   WARNING: log file has been rolled over! Don't trust on the search results and re-run the quey
```
  * FilePosTrackingRollingFileAppender writes index to disk every [indexFlushInterval](FilePosTrackingRollingFileAppenderConfiguration#indexFlushInterval.md). If before the next write your JVM crashes, there won't be entries in the index for the log statements that were written after the last checkpoint.
  * If you have changed your concrete implementation of FilePosTrackingRollingFileAppender#populateDocument(long, LoggingEvent, Document) then you should delete your existing Lucene index (which in Windows also means you should stop your application server to release the file locks). This might be changed in the future so as to support multiple versions of the index. This implies renaming the old index, using a new directory to store a newer version. Your index searcher application, e.g. a servlet should support this as well.

# Tips #

  * The LuceneLogSearchServlet output can be gzipped to reduce network traffic. The sample **lucene\_log4j\_sample\_webapp** project includes the setup to use [pjl-comp-filter](http://pjl-comp-filter.sourceforge.net/).