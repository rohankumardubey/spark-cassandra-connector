object Versions {

  val CommonsExec     = "1.3"
  val CommonsIO       = "2.6"
  val CommonsLang3    = "3.5"
  val Paranamer       = "2.8"
  val ScalaLogging    = "3.5.0"

  val DataStaxJavaDriver = "4.5.0"
  val ReactiveStreams = "1.0.2"

  val ScalaCheck      = "1.14.0"
  val ScalaTest       = "3.0.8"
  val JUnit           = "4.12"
  val JUnitInterface  = "0.11"
  val Mockito         = "1.10.19"
  val Slf4j           = "1.6.1"//1.7.7"

  // Spark version can be specified as:
  // - regular version which is present in some public Maven repository
  // - a release tag in https://github.com/apache/spark
  // - one of main branches, like master or branch-x.y, followed by "-SNAPSHOT" suffix
  // The last two cases trigger the build to clone the given revision of Spark from GitHub, build it
  // and install in a local Maven repository. This is all done automatically, however it will work
  // only on Unix/OSX operating system. Windows users have to build and install Spark manually if the
  // desired version is not yet published into a public Maven repository.
  val ApacheSpark     = "3.0.0"
  val SparkJetty      = "9.3.24.v20180605"
  val SolrJ           = "8.3.0"

  /*
  val status = (versionInReapply: String, binaryInReapply: String) =>
    println(s"""
               |  Scala: $versionInReapply
               |  Scala Binary: $binaryInReapply
               |  Java: target=$JDK user=${Properties.javaVersion}
               |  Cassandra version for testing: ${Testing.cassandraTestVersion} [can be overridden by specifying '-Dtest.cassandra.version=<version>']
        """.stripMargin)

   */
}
