// (c) Copyright [2020-2021] Micro Focus or one of its affiliates.
// Licensed under the Apache License, Version 2.0 (the "License");
// You may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.vertica.spark.datasource.core

import cats.data.Validated.{Invalid, Valid}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import com.vertica.spark.config._
import com.vertica.spark.datasource.core.factory.VerticaPipeFactoryInterface
import org.scalamock.scalatest.MockFactory
import com.vertica.spark.util.error._
import com.vertica.spark.datasource.v2.DummyReadPipe
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types._

class DSConfigSetupTest extends AnyFlatSpec with BeforeAndAfterAll with MockFactory {
  override def beforeAll(): Unit = {
  }

  override def afterAll(): Unit = {
  }


  // Parses config expecting success
  // Calling test with fail if an error is returned
  def parseCorrectInitConfig(opts : Map[String, String], dsReadConfigSetup: DSReadConfigSetup) : ReadConfig = {
    val readConfig : ReadConfig = dsReadConfigSetup.validateAndGetConfig(opts) match {
      case Invalid(_) =>
        fail
        mock[ReadConfig]
      case Valid(config) =>
        config
    }
    readConfig
  }
  def parseCorrectInitConfig(opts : Map[String, String], dsWriteConfigSetup: DSWriteConfigSetup) : WriteConfig = {
    val writeConfig : WriteConfig = dsWriteConfigSetup.validateAndGetConfig(opts) match {
      case Invalid(_) =>
        fail
        mock[WriteConfig]
      case Valid(config) =>
        config
    }
    writeConfig
  }

  // Parses config expecting an error
  // Calling test will fail if the config is parsed without error
  def parseErrorInitConfig(opts : Map[String, String], dsReadConfigSetup: DSReadConfigSetup) : Seq[ConnectorError] = {
    dsReadConfigSetup.validateAndGetConfig(opts) match {
      case Invalid(errList) => errList.toNonEmptyList.toList
      case Valid(_) => fail("The config was valid.")
    }
  }
  def parseErrorInitConfig(opts : Map[String, String], dsWriteConfigSetup: DSWriteConfigSetup) : Seq[ConnectorError] = {
    dsWriteConfigSetup.validateAndGetConfig(opts) match {
      case Invalid(errList) => errList.toNonEmptyList.toList
      case Valid(_) => fail("The config was valid.")
    }
  }


  it should "parse a valid read config" in {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("Vertica Connector Test Prototype")
      .getOrCreate()

    try {
      val opts = Map("host" -> "1.1.1.1",
        "port" -> "1234",
        "db" -> "testdb",
        "user" -> "user",
        "password" -> "password",
        "table" -> "tbl",
        "staging_fs_url" -> "hdfs://test:8020/tmp/test"
      )

      // Set mock pipe
      val mockPipe = mock[DummyReadPipe]
      (mockPipe.getMetadata _).expects().returning(Right(VerticaReadMetadata(new StructType))).once()
      val mockPipeFactory = mock[VerticaPipeFactoryInterface]
      (mockPipeFactory.getReadPipe _).expects(*).returning(mockPipe)

      val dsReadConfigSetup = new DSReadConfigSetup(mockPipeFactory)

      parseCorrectInitConfig(opts, dsReadConfigSetup) match {
        case config: DistributedFilesystemReadConfig =>
          assert(config.jdbcConfig.host == "1.1.1.1")
          assert(config.jdbcConfig.port == 1234)
          assert(config.jdbcConfig.db == "testdb")
          assert(config.tableSource.asInstanceOf[TableName].getFullTableName == "\"tbl\"")
          config.metadata match {
            case Some(metadata) => assert(metadata.schema == new StructType())
            case None => fail
          }
      }
    } finally {
      spark.close()
    }
  }

  it should "Return several parsing errors on read" in {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("Vertica Connector Test Prototype")
      .getOrCreate()

    try {
      // Should be one error from the jdbc parser for the port and one for the missing log level
      val opts = Map("host" -> "1.1.1.1",
        "db" -> "testdb",
        "port" -> "asdf",
        "user" -> "user",
        "password" -> "password",
        "table" -> "tbl",
        "staging_fs_url" -> "hdfs://test:8020/tmp/test",
        "num_partitions" -> "foo"
      )

      val dsReadConfigSetup = new DSReadConfigSetup(mock[VerticaPipeFactoryInterface])

      val errSeq = parseErrorInitConfig(opts, dsReadConfigSetup)
      assert(errSeq.size == 2)
      assert(errSeq.contains(InvalidPortError()))
      assert(errSeq.contains(InvalidPartitionCountError()))
    } finally {
      spark.close()
    }
  }

  it should "Return error when there's a problem retrieving metadata" in {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("Vertica Connector Test Prototype")
      .getOrCreate()

    try {
      val opts = Map("host" -> "1.1.1.1",
        "port" -> "1234",
        "db" -> "testdb",
        "user" -> "user",
        "password" -> "password",
        "table" -> "tbl",
        "staging_fs_url" -> "hdfs://test:8020/tmp/test"
      )

      // Set mock pipe
      val mockPipe = mock[DummyReadPipe]
      (mockPipe.getMetadata _).expects().returning(Left(SchemaDiscoveryError())).once()
      val mockPipeFactory = mock[VerticaPipeFactoryInterface]
      (mockPipeFactory.getReadPipe _).expects(*).returning(mockPipe)

      val dsReadConfigSetup = new DSReadConfigSetup(mockPipeFactory)

      val errSeq = parseErrorInitConfig(opts, dsReadConfigSetup)
      assert(errSeq.size == 1)
      assert(errSeq.map(_.getError).contains(SchemaDiscoveryError()))
    } finally {
      spark.close()
    }
  }

  it should "parse a valid write config" in {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("Vertica Connector Test Prototype")
      .getOrCreate()

    try {
      val opts = Map(
        "host" -> "1.1.1.1",
        "port" -> "1234",
        "db" -> "testdb",
        "user" -> "user",
        "password" -> "password",
        "table" -> "tbl",
        "staging_fs_url" -> "hdfs://test:8020/tmp/test"
      )

      // Set mock pipe
      val mockPipeFactory = mock[VerticaPipeFactoryInterface]

      val dsWriteConfigSetup = new DSWriteConfigSetup(Some(new StructType), mockPipeFactory)

      parseCorrectInitConfig(opts, dsWriteConfigSetup) match {
        case config: DistributedFilesystemWriteConfig =>
          assert(config.jdbcConfig.host == "1.1.1.1")
          assert(config.jdbcConfig.port == 1234)
          assert(config.jdbcConfig.db == "testdb")
          assert(config.tablename.getFullTableName == "\"tbl\"")
      }
    } finally {
      spark.close()
    }
  }

  it should "Return several parsing errors on write" in {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("Vertica Connector Test Prototype")
      .getOrCreate()

    try {
      val opts = Map(
        "host" -> "1.1.1.1",
        "db" -> "testdb",
        "port" -> "asdf",
        "user" -> "user",
        "password" -> "password",
        "table" -> "tbl",
        "failed_rows_percent_tolerance" -> "2.00",
        "staging_fs_url" -> "hdfs://test:8020/tmp/test"
      )

      // Set mock pipe
      val mockPipeFactory = mock[VerticaPipeFactoryInterface]

      val dsWriteConfigSetup = new DSWriteConfigSetup(Some(new StructType), mockPipeFactory)

      val errSeq = parseErrorInitConfig(opts, dsWriteConfigSetup)
      assert(errSeq.size == 2)
      assert(errSeq.map(_.getError).contains(InvalidPortError()))
      assert(errSeq.map(_.getError).contains(InvalidFailedRowsTolerance()))
    } finally {
      spark.close()
    }
  }

  it should "Include error for old connector option on write" in {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("Vertica Connector Test Prototype")
      .getOrCreate()

    try {
      val opts = Map(
        "host" -> "1.1.1.1",
        "db" -> "testdb",
        "port" -> "1234",
        "user" -> "user",
        "password" -> "password",
        "table" -> "tbl",
        "failed_rows_percent_tolerance" -> "2.00",
        "staging_fs_url" -> "hdfs://test:8020/tmp/test",
        "hdfs_url" -> "hdfs://test:8020/tmp/test"
      )

      // Set mock pipe
      val mockPipeFactory = mock[VerticaPipeFactoryInterface]

      val dsWriteConfigSetup = new DSWriteConfigSetup(Some(new StructType), mockPipeFactory)

      val errSeq = parseErrorInitConfig(opts, dsWriteConfigSetup)
      assert(errSeq.size == 2)
      assert(errSeq.map(_.getError).contains(InvalidFailedRowsTolerance()))
      assert(errSeq.map(_.getError).contains(V1ReplacementOption("hdfs_url","staging_fs_url")))

    } finally {
      spark.close()
    }
  }

  it should "Include error for old connector option on read" in {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("Vertica Connector Test Prototype")
      .getOrCreate()

    try {
      // Should be one error from the jdbc parser for the port and one for the missing log level
      val opts = Map("host" -> "1.1.1.1",
        "db" -> "testdb",
        "port" -> "asdf",
        "user" -> "user",
        "password" -> "password",
        "table" -> "tbl",
        "staging_fs_url" -> "hdfs://test:8020/tmp/test",
        "num_partitions" -> "foo",
        "numpartitions" -> "5"
      )

      val dsReadConfigSetup = new DSReadConfigSetup(mock[VerticaPipeFactoryInterface])

      val errSeq = parseErrorInitConfig(opts, dsReadConfigSetup)
      assert(errSeq.size == 3)
      assert(errSeq.contains(InvalidPortError()))
      assert(errSeq.contains(InvalidPartitionCountError()))
      assert(errSeq.map(_.getError).contains(V1ReplacementOption("numpartitions","num_partitions")))
    } finally {
      spark.close()
    }
  }

  it should "Don't error out with old connector options if no other errors" in {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("Vertica Connector Test Prototype")
      .getOrCreate()

    try {
      val opts = Map("host" -> "1.1.1.1",
        "port" -> "1234",
        "db" -> "testdb",
        "user" -> "user",
        "password" -> "password",
        "table" -> "tbl",
        "staging_fs_url" -> "hdfs://test:8020/tmp/test",
        "hdfs_url" -> "hdfs://test:8020/tmp/test"
      )

      // Set mock pipe
      val mockPipe = mock[DummyReadPipe]
      (mockPipe.getMetadata _).expects().returning(Right(VerticaReadMetadata(new StructType))).once()
      val mockPipeFactory = mock[VerticaPipeFactoryInterface]
      (mockPipeFactory.getReadPipe _).expects(*).returning(mockPipe)

      val dsReadConfigSetup = new DSReadConfigSetup(mockPipeFactory)

      parseCorrectInitConfig(opts, dsReadConfigSetup)
    } finally {
      spark.close()
    }
  }

  it should "get the AWS access key id, secret access key, session token, and region from environment variables" in {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("Vertica Connector Test Prototype")
      .getOrCreate()

    try {
      val opts = Map(
        "host" -> "1.1.1.1",
        "port" -> "1234",
        "db" -> "testdb",
        "user" -> "user",
        "password" -> "password",
        "table" -> "tbl",
        "staging_fs_url" -> "hdfs://test:8020/tmp/test"
      )

      // Set mock pipe
      val mockPipeFactory = mock[VerticaPipeFactoryInterface]

      val dsWriteConfigSetup = new DSWriteConfigSetup(Some(new StructType), mockPipeFactory)

      parseCorrectInitConfig(opts, dsWriteConfigSetup) match {
        case config: DistributedFilesystemWriteConfig =>
          val awsOptions = config.fileStoreConfig.awsOptions
          awsOptions.awsAuth match {
            case Some(auth) =>
              assert(auth.accessKeyId.toString == "AWSArg(EnvVar, *****)")
              assert(auth.accessKeyId.arg == "test")
              assert(auth.secretAccessKey.toString == "AWSArg(EnvVar, *****)")
              assert(auth.secretAccessKey.arg == "foo")
              awsOptions.awsSessionToken match {
                case Some(token) =>
                  assert(token.toString == "AWSArg(EnvVar, *****)")
                  assert(token.arg == "testsessiontoken")
                case None => fail("Failed to get AWS session token from the environment variables")
              }
              awsOptions.awsRegion match {
                case Some(region) =>
                  assert(region.toString == "AWSArg(EnvVar, us-west-1)")
                  assert(region.arg == "us-west-1")
                case None => fail("Failed to get AWS region from the environment variables")
              }
            case None => fail("Failed to get AWS Auth from the environment variables")
          }
      }
    } finally {
      spark.close()
    }
  }

  it should "get the AWS access key id, secret access key from Spark configuration" in {
    val spark = SparkSession.builder()
      .master("local[*]")
      .appName("Vertica Connector Test Prototype")
      .config("spark.hadoop.fs.s3a.access.key", "moo")
      .config("spark.hadoop.fs.s3a.secret.key", "cow")
      .config("spark.hadoop.fs.s3a.session.token", "asessiontoken")
      .config("spark.hadoop.fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider")
      .getOrCreate()

    try {
      val opts = Map(
        "host" -> "1.1.1.1",
        "port" -> "1234",
        "db" -> "testdb",
        "user" -> "user",
        "password" -> "password",
        "table" -> "tbl",
        "staging_fs_url" -> "hdfs://test:8020/tmp/test"
      )

      // Set mock pipe
      val mockPipeFactory = mock[VerticaPipeFactoryInterface]

      val dsWriteConfigSetup = new DSWriteConfigSetup(Some(new StructType), mockPipeFactory)

      parseCorrectInitConfig(opts, dsWriteConfigSetup) match {
        case config: DistributedFilesystemWriteConfig =>
          val awsOptions = config.fileStoreConfig.awsOptions
          awsOptions.awsAuth match {
            case Some(auth) =>
              assert(auth.accessKeyId.toString == "AWSArg(SparkConf, *****)")
              assert(auth.accessKeyId.arg == "moo")
              assert(auth.secretAccessKey.toString == "AWSArg(SparkConf, *****)")
              assert(auth.secretAccessKey.arg == "cow")
            case None => fail("Failed to get AWS Auth from the Spark configuration")
          }
          awsOptions.awsSessionToken match {
            case Some(token) =>
              assert(token.toString == "AWSArg(SparkConf, *****)")
              assert(token.arg == "asessiontoken")
            case None => fail("Failed to get AWS session token from the Spark configuration")
          }
          awsOptions.awsCredentialsProvider match {
            case Some(credentialsProvider) =>
              assert(credentialsProvider.toString ==
                "AWSArg(SparkConf, org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider)")
              assert(credentialsProvider.arg == "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider")
            case None => fail("Failed to get AWS credentials provider from the Spark configuration")
          }
      }
    } finally {
      spark.close()
    }
  }

  it should "get the AWS parameters from the connector options" in {
    val opts = Map(
      "host" -> "1.1.1.1",
      "port" -> "1234",
      "db" -> "testdb",
      "user" -> "user",
      "password" -> "password",
      "table" -> "tbl",
      "staging_fs_url" -> "hdfs://test:8020/tmp/test",
      "aws_access_key_id" -> "meow",
      "aws_secret_access_key" -> "woof",
      "aws_region" -> "us-east-1",
      "aws_session_token" -> "mysessiontoken",
      "aws_credentials_provider" -> "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider"
    )

    // Set mock pipe
    val mockPipeFactory = mock[VerticaPipeFactoryInterface]

    val dsWriteConfigSetup = new DSWriteConfigSetup(Some(new StructType), mockPipeFactory)

    parseCorrectInitConfig(opts, dsWriteConfigSetup) match {
      case config: DistributedFilesystemWriteConfig =>
        val awsOptions = config.fileStoreConfig.awsOptions
        awsOptions.awsAuth match {
          case Some(auth) =>
            assert(auth.accessKeyId.toString == "AWSArg(ConnectorOption, *****)")
            assert(auth.accessKeyId.arg == "meow")
            assert(auth.secretAccessKey.toString == "AWSArg(ConnectorOption, *****)")
            assert(auth.secretAccessKey.arg == "woof")
            awsOptions.awsRegion match {
              case Some(region) =>
                assert(region.toString == "AWSArg(ConnectorOption, us-east-1)")
                assert(region.arg == "us-east-1")
              case None => fail("Failed to get AWS region from the connector options")
            }
            awsOptions.awsSessionToken match {
              case Some(token) =>
                assert(token.toString == "AWSArg(ConnectorOption, *****)")
                assert(token.arg == "mysessiontoken")
              case None => fail("Failed to get AWS session token from the connector options")
            }
            awsOptions.awsCredentialsProvider match {
              case Some(credentialsProvider) =>
                assert(credentialsProvider.toString ==
                  "AWSArg(ConnectorOption, org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider)")
                assert(credentialsProvider.arg == "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider")
              case None => fail("Failed to get AWS credentials provider from the connector options")
            }
          case None => fail("Failed to get AWS credentials provider from the connector options")
        }
    }
  }
}
