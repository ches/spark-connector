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

package com.vertica.spark.functests

import java.sql.{Connection, Date}

import org.apache.log4j.Logger
import org.apache.spark.sql.types.{ArrayType, BinaryType, BooleanType, ByteType, DateType, Decimal, DecimalType, DoubleType, FloatType, IntegerType, LongType, StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

class EndToEndTests(readOpts: Map[String, String], writeOpts: Map[String, String]) extends AnyFlatSpec with BeforeAndAfterAll {
  val conn: Connection = TestUtils.getJDBCConnection(readOpts("host"), db = readOpts("db"), user = readOpts("user"), password = readOpts("password"))

  val numSparkPartitions = 4

  private val spark = SparkSession.builder()
    .master("local[*]")
    .appName("Vertica Connector Test Prototype")
    .getOrCreate()

  override def afterAll(): Unit = {
    spark.close()
    conn.close()
  }

  it should "read data from Vertica" in {
    val tableName1 = "dftest1"
    val stmt = conn.createStatement
    val n = 1
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a int)")

    val insert = "insert into "+ tableName1 + " values(2)"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    assert(df.count() == 1)
    df.rdd.foreach(row => assert(row.getAs[Long](0) == 2))
  }

  it should "read 20 rows of data from Vertica" in {
    val tableName1 = "dftest1"
    val stmt = conn.createStatement
    val n = 20
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a int)")

    val insert = "insert into "+ tableName1 + " values(2)"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    assert(df.count() == 20)
    df.rdd.foreach(row => assert(row.getAs[Long](0) == 2))
  }

  it should "support data frame schema" in {

    val tableName1 = "dftest1"
    val stmt = conn.createStatement
    val n = 1
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a int, b float)")

    val insert = "insert into "+ tableName1 + " values(1, 2.2)"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val df = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    val schema = df.schema
    info("table schema: " + schema)
    val sc = StructType(Array(StructField("a",LongType,nullable = true), StructField("b",DoubleType,nullable = true)))

    assert(schema.toString equals sc.toString)
  }

  it should "support data frame projection" in {
    val tableName1 = "dftest1"
    val stmt = conn.createStatement
    val n = 3
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a int, b float)")
    val insert = "insert into "+ tableName1 + " values(1, 2.2)"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    val filtered = df.select(df("a"))
    val count = filtered.count()

    assert(count == n)
    assert(filtered.columns.mkString equals Array("a").mkString)
  }

  it should "support data frame filter" in {

    val tableName1 = "dftest1"

    val stmt = conn.createStatement
    val n = 3
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a int, b float)")
    val insert = "insert into "+ tableName1 + " values(1, 2.2)"
    TestUtils.populateTableBySQL(stmt, insert, n)
    val insert2 = "insert into "+ tableName1 + " values(3, 4.4)"
    TestUtils.populateTableBySQL(stmt, insert2, n)
    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()
    val filtered = df.filter(df("a")> 2).where(df("b") > 3.3)
    val count = filtered.count()

    assert(count == n)
  }

  it should "load data from Vertica table that is [SEGMENTED] on [ALL] nodes" in {
    val tableName1 = "dftest1"

    val n = 40
    TestUtils.createTable(conn, tableName1, numOfRows = n)

    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)
  }

  it should "load data from Vertica table that is [UNSEGMENTED] on [ALL] nodes" in {
    val tableName1 = "dftest1"

    val n = 40

    TestUtils.createTable(conn, tableName1, isSegmented = false, numOfRows = n)
    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)
  }

  it should "load data from Vertica table that is [SEGMENTED] on [SOME] nodes for [arbitrary partition number]" in {
    val tableName1 = "t1"

    val n = 40
    val nodes = TestUtils.getNodeNames(conn)
    TestUtils.createTablePartialNodes(conn, tableName1, isSegmented = true, numOfRows = n, nodes.splitAt(3)._1)

    for (p <- 1 until numSparkPartitions) {
      info("Number of Partition : " + p)
      val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))
      assert(r == n)
    }
  }

  it should "load data from Vertica table that is [SEGMENTED] on [Some] nodes" in {
    val tableName1 = "dftest1"

    val n = 40
    val nodes = TestUtils.getNodeNames(conn)
    TestUtils.createTablePartialNodes(conn, tableName1, isSegmented = true, numOfRows = n, nodes.splitAt(3)._1)
    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)
  }

  it should "load data from Vertica table that is [UNSEGMENTED] on [Some] nodes" in {
    val tableName1 = "dftest1"

    val n = 10
    val nodes = TestUtils.getNodeNames(conn)
    TestUtils.createTablePartialNodes(conn, tableName1, isSegmented = true, numOfRows = n, nodes.splitAt(3)._1)
    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)
  }

  it should "load data from Vertica table that is [UNSEGMENTED] on [One] nodes for [arbitrary partition number]" in {
    val tableName1 = "t1"

    val n = 40
    val nodes = TestUtils.getNodeNames(conn)
    val stmt = conn.createStatement()
    stmt.execute("SELECT MARK_DESIGN_KSAFE(0);")
    TestUtils.createTablePartialNodes(conn, tableName1, isSegmented = false, numOfRows = n, nodes.splitAt(3)._1)

    for (p <- 1 until numSparkPartitions) {
      info("Number of Partition : " + p)
      val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))
      assert(r == n)
    }
    stmt.execute("drop table " + tableName1)
    stmt.execute("SELECT MARK_DESIGN_KSAFE(1);")
  }

  it should "load data from Vertica for [all Binary data types] of Vertica" in {
    val tableName1 = "t1"

    val n = 40
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a binary, b varbinary, c long varbinary, d bytea, e raw)")
    val insert = "insert into "+ tableName1 + " values(hex_to_binary('0xff'), HEX_TO_BINARY('0xFFFF'), HEX_TO_BINARY('0xF00F'), HEX_TO_BINARY('0xF00F'), HEX_TO_BINARY('0xF00F'))"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)

    stmt.execute("drop table " + tableName1)
  }

  it should "load data from Vertica for [all Boolean data types] of Vertica" in {
    val tableName1 = "t1"

    val n = 40
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a boolean, b boolean)")
    val insert = "insert into "+ tableName1 + " values('t',0)"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)

    stmt.execute("drop table " + tableName1)
  }

  it should "load data from Vertica for [all Character data types] of Vertica" in {
    val tableName1 = "t1"

    val n = 40
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a CHARACTER , b CHARACTER(10), c VARCHAR (20), d  CHARACTER VARYING(30) )")
    val insert = "insert into "+ tableName1 + " values('a', 'efghi', 'jklm', 'nopqrst')"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)

    stmt.execute("drop table " + tableName1)
  }

  it should "load data from Vertica for [all Date/Time data types] of Vertica" in {
    val tableName1 = "t1"

    val n = 40
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a DATE , b TIME (10), c TIMETZ (20), d  TIMESTAMP, e TIMESTAMPTZ , f INTERVAL DAY TO SECOND, g  INTERVAL YEAR TO MONTH  )")
    val insert = "insert into "+ tableName1 + " values('1/8/1999', '2004-10-19 10:23:54', '23:59:59.999999-14', '2004-10-19 10:23:54', '2004-10-19 10:23:54+02', '1 day 6 hours', '1 year 6 months')"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)

    stmt.execute("drop table " + tableName1)
  }

  it should "load data from Vertica for [all Long data types] of Vertica" in {
    val tableName1 = "t1"

    val n = 40
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a LONG VARBINARY(100) , b LONG VARCHAR  (120)  )")
    val insert = "insert into "+ tableName1 + " values('abcde', 'fghijk')"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)

    stmt.execute("drop table " + tableName1)
  }

  it should "load data from Vertica for [Int data types] of Vertica" in {
    val tableName1 = "t1"

    val n = 10
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a INTEGER  , b SMALLINT , c BIGINT, d INT8     )")
    val insert = "insert into "+ tableName1 + " values(1,2,3,4)"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)

    stmt.execute("drop table " + tableName1)
  }

  it should "load data from Vertica for [Double data types] of Vertica" in {
    val tableName1 = "t1"

    val n = 10
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a DOUBLE PRECISION, b FLOAT, c FLOAT(20), d FLOAT8, e REAL    )")
    val insert = "insert into "+ tableName1 + " values(1.1, 2.2, 3.3, 4.4, 5.5)"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)

    stmt.execute("drop table " + tableName1)
  }

  it should "load data from Vertica for [Numeric data types] of Vertica" in {
    val tableName1 = "t1"

    val n = 10
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a NUMERIC(5,2), b DECIMAL, c NUMBER, d MONEY(6,3)     )")
    val insert = "insert into "+ tableName1 + " values(1.1, 2.2, 3.3, 4.4)"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val r = TestUtils.doCount(spark, readOpts + ("table" -> tableName1))

    assert(r == n)

    stmt.execute("drop table " + tableName1)
  }

  it should "load data from Vertica with a DATE-type pushdown filter" in {
    val tableName1 = "dftest1"
    val stmt = conn.createStatement()
    val n = 3

    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a DATE, b float)")

    var insert = "insert into "+ tableName1 + " values('1977-02-01', 2.2)"
    TestUtils.populateTableBySQL(stmt, insert, n)
    insert = "insert into "+ tableName1 + " values('2077-02-01', 2.2)"
    TestUtils.populateTableBySQL(stmt, insert, n + 1)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    val dfFiltered1 = df.filter("a < cast('2001-01-01' as DATE)")
    val dfFiltered2 = df.filter("a > cast('2001-01-01' as DATE)")

    val r = dfFiltered1.count
    val r2 = dfFiltered2.count

    assert(!dfFiltered1
      .queryExecution
      .executedPlan
      .toString()
      .contains("Filter"))

    assert(!dfFiltered2
      .queryExecution
      .executedPlan
      .toString()
      .contains("Filter"))

    assert(r == n)
    assert(r2 == (n + 1))

    stmt.execute("drop table " + tableName1)
  }

  it should "load data from Vertica with a String-type pushdown filter" in {
    val tableName1 = "dftest1"
    val stmt = conn.createStatement()
    val n = 3

    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a varchar(10), b float)")

    var insert = "insert into "+ tableName1 + " values('abc', 2.2)"
    TestUtils.populateTableBySQL(stmt, insert, n)
    insert = "insert into "+ tableName1 + " values('cde', 2.2)"
    TestUtils.populateTableBySQL(stmt, insert, n + 1)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    val dfFiltered1 = df.filter("a = 'abc'")
    val dfFiltered2 = df.filter("a = 'cde'")

    val r = dfFiltered1.count
    val r2 = dfFiltered2.count

    assert(!dfFiltered1
      .queryExecution
      .executedPlan
      .toString()
      .contains("Filter"))

    assert(!dfFiltered2
      .queryExecution
      .executedPlan
      .toString()
      .contains("Filter"))

    assert(r == n)
    assert(r2 == (n + 1))

    stmt.execute("drop table " + tableName1)
  }

  it should "load data from Vertica with a TIMESTAMP-type pushdown filter" in {
    val tableName1 = "dftest1"
    val stmt = conn.createStatement()
    val n = 3

    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a TIMESTAMP, b float)")

    var insert = "insert into "+ tableName1 + " values(TIMESTAMP '2010-03-25 12:47:32.62', 2.2)"
    TestUtils.populateTableBySQL(stmt, insert, n)
    insert = "insert into "+ tableName1 + " values(TIMESTAMP '2010-03-25 12:55:49.123456', 2.2)"
    TestUtils.populateTableBySQL(stmt, insert, n + 1)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    val dr = df.filter("a = cast('2010-03-25 12:55:49.123456' AS TIMESTAMP)")
    val r = dr.count

    assert(!dr
      .queryExecution
      .executedPlan
      .toString()
      .contains("Filter"))

    assert(r == n + 1)

    dr.show
    stmt.execute("drop table " + tableName1)
  }

  it should "fetch the correct results when startsWith and endsWith functions are used" in {
    val tableName1 = "dftest1"

    val n = 10
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a varchar, b integer)")
    var insert = "insert into "+ tableName1 + " values('christmas', 5)"
    TestUtils.populateTableBySQL(stmt, insert, n/2)

    insert = "insert into "+ tableName1 + " values('hannukah', 10)"
    TestUtils.populateTableBySQL(stmt, insert, n/2)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    val r = df.filter(df("a").startsWith("chr")).count

    assert(r == n/2)

    val s = df.filter(df("a").endsWith("kah")).count
    assert(s == n/2)

    stmt.execute("drop table " + tableName1)
  }


  it should "fetch the correct results when custom, non-integer segmentation is used" in {
    val tableName1 = "custom_segexpr_table"

    val n = 10
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a varchar, b integer) segmented by mod(b, 3) all nodes")
    var insert = "insert into "+ tableName1 + " values('christmas', NULL)"
    TestUtils.populateTableBySQL(stmt, insert, 1)

    insert = "insert into "+ tableName1 + " values('hannukah', 10)"
    TestUtils.populateTableBySQL(stmt, insert, n-1)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    val r = df.cache.count

    assert(r == n)

    val s = df.filter("b is NULL").count

    assert(s == 1)

    stmt.execute("drop table " + tableName1)
  }

  it should "work when using isin or in" in {
    val tableName1 = "test_in_clause"

    val (i,j,n) = (2,3,5)
    val stmt = conn.createStatement
    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (a varchar, b integer)")
    var insert = "insert into "+ tableName1 + " values('christmas', 5)"
    TestUtils.populateTableBySQL(stmt, insert, i)

    insert = "insert into "+ tableName1 + " values('hannukah', 10)"
    TestUtils.populateTableBySQL(stmt, insert, j)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    val r = df.filter("a in ('christmas','hannukah','foo')").count
    val u = df.filter(df.col("a").isin("christmas","hannukah","foo")).count

    assert(r == n)
    assert(u == n)

    val s = df.filter("b in (3,4,5)").count
    val t = df.filter("b in (2,6,10)").count

    assert(s == i)
    assert(t == j)

    stmt.execute("drop table " + tableName1)
  }

  it should "be able to handle interval types" in {
    val tableName1 = "dftest"
    val stmt = conn.createStatement()
    val n = 1

    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (f INTERVAL DAY TO SECOND, g INTERVAL YEAR TO MONTH)")

    val insert = "insert into "+ tableName1 + " values('1 day 6 hours', '1 year 6 months')"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    assert(df.cache.count == n)
    stmt.execute("drop table " + tableName1)
  }

  it should "be able to handle the UUID type" in {
    val tableName1 = "dftest"
    val stmt = conn.createStatement()
    val n = 1

    TestUtils.createTableBySQL(conn, tableName1, "create table " + tableName1 + " (f uuid)")

    val insert = "insert into " + tableName1 + " values('6bbf0744-74b4-46b9-bb05-53905d4538e7')"
    TestUtils.populateTableBySQL(stmt, insert, n)

    val df: DataFrame = spark.read.format("com.vertica.spark.datasource.VerticaSource").options(readOpts + ("table" -> tableName1)).load()

    assert(df.cache.count == n)
    stmt.execute("drop table " + tableName1)
  }

  it should "write data to Vertica" in {
    val tableName = "basicWriteTest"
    val schema = new StructType(Array(StructField("col1", IntegerType)))

    val data = Seq(Row(77))
    val df = spark.createDataFrame(spark.sparkContext.parallelize(data), schema).coalesce(1)
    println(df.toString())
    val mode = SaveMode.Overwrite

    df.write.format("com.vertica.spark.datasource.VerticaSource").options(writeOpts + ("table" -> tableName)).mode(mode).save()

    val stmt = conn.createStatement()
    val query = "SELECT * FROM " + tableName
    try {
      val rs = stmt.executeQuery(query)
      assert (rs.next)
      assert (rs.getInt(1) ==  77)
    }
    catch{
      case err : Exception => fail(err)
    }
    finally {
      stmt.close()
    }

    TestUtils.dropTable(conn, tableName)
  }

  it should "write int and string rows to Vertica" in {
    val tableName = "basicWriteTest"
    val schema = new StructType(Array(StructField("col1", IntegerType),
      StructField("col2", IntegerType),
      StructField("col3", StringType)
    ))

    val data = Seq(Row(77, 77, "hello"), Row(88, 0, "goodbye"))
    val df = spark.createDataFrame(spark.sparkContext.parallelize(data), schema).coalesce(2)
    val mode = SaveMode.Overwrite

    df.write.format("com.vertica.spark.datasource.VerticaSource").options(writeOpts + ("table" -> tableName)).mode(mode).save()

    val stmt = conn.createStatement()
    val query = "SELECT * FROM " + tableName
    try {
      val rs = stmt.executeQuery(query)
      assert (rs.next)
      val first = rs.getInt(1)
      if(first == 77) {
        assert(rs.getInt(2) == 77)
        assert(rs.getString(3) == "hello")
      }
      if(first == 88) {
        assert(rs.getInt(2) == 0)
        assert(rs.getString(3) == "goodbye")
      }
    }
    catch{
      case err : Exception => fail(err)
    }
    finally {
      stmt.close()
    }

    TestUtils.dropTable(conn, tableName)
  }

  it should "create a dataframe and load all 100 rows successfully for SaveMode.Overwrite" in {
    val tableName = "s2vdevtest01"
    TestUtils.dropTable(conn, tableName)

    // else local file path within this project.
    val datafile = "src/main/resources/datafile-100cols-100rows.csv"
    val testdata = spark.sparkContext.textFile(datafile)

    val schema = TestUtils.getKmeans100colFloatSchema()
    val rowRDD = TestUtils.getKmeans100colFloatRowRDD(testdata)
    val df = spark.createDataFrame(rowRDD,schema)
    val numDfRows = df.count()

    val start = System.currentTimeMillis()
    val mode = SaveMode.Overwrite
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(writeOpts + ("table" -> tableName)).mode(mode).save()
    val end = System.currentTimeMillis()
    println("Save time=" + (end-start)/1000.0 + " seconds.")

    var rowsLoaded = 0
    val stmt = conn.createStatement()
    val query = "SELECT COUNT(*) AS count FROM " + tableName
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) { rowsLoaded = rs.getInt("count") }
      }
    finally {
      stmt.close()
    }
    assert ( rowsLoaded == numDfRows )

    TestUtils.dropTable(conn, tableName)
  }

  it should "create a dataframe and load all 100 rows successfully for SaveMode.Append" in {
    val tableName = "s2vdevtest02"

    // else local file path within this project.
    val datafile = "src/main/resources/datafile-100cols-100rows.csv"
    val testdata = spark.sparkContext.textFile(datafile)

    val options = writeOpts + ("table" -> tableName)

    val schema = TestUtils.getKmeans100colFloatSchema()
    val rowRDD = TestUtils.getKmeans100colFloatRowRDD(testdata)
    val df = spark.createDataFrame(rowRDD,schema)
    val numDfRows = df.count()

    var rowsExisting = 0
    var stmt = conn.createStatement()
    var query = "SELECT COUNT(*) AS count FROM " + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsExisting = rs.getInt("count")
      }
    }
    catch {
      case e: Exception => rowsExisting = 0
    }
    finally {
      stmt.close()
    }

    val mode = SaveMode.Append
    val start = System.currentTimeMillis()
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    val end = System.currentTimeMillis()
    println("Save time=" + (end-start)/1000.0 + " seconds.")

    var totalRows = 0
    stmt = conn.createStatement()
    try {
      query = "SELECT COUNT(*) AS count FROM " + options("table")
      val rs = stmt.executeQuery(query)
      if (rs.next) { totalRows = rs.getInt("count") }
    }
    finally {
      stmt.close()
    }
    assert (totalRows == (numDfRows + rowsExisting))

    TestUtils.dropTable(conn, tableName)
  }

  it should "create a dataframe with different types and Overwrite mode" in {
    val tableName = "s2vdevtest03"
    TestUtils.dropTable(conn, tableName)

    val diffTypesText = spark.sparkContext.textFile("src/main/resources/diffTypesORC.txt")
    val rowRDD = diffTypesText.map(_.split(",")).map(p => Row(p(0), p(1).toInt, p(2).toBoolean, p(3).toFloat))
    val schema = StructType(Array(
      StructField("txt",StringType,nullable=true),
      StructField("a",IntegerType,nullable=true),
      StructField("b",BooleanType,nullable=true),
      StructField("float",FloatType,nullable=false)
    ))

    val options = writeOpts + ("table" -> tableName)

    val df = spark.createDataFrame(rowRDD,schema)
    val numDfRows = df.count()
    df.show

    // ALL save modes should work
    // SaveMode.Overwrite, SaveMode.Append, SaveMode.ErrorIfExists
    val log = Logger.getLogger(getClass.getName)
    log.info(s"Test options:" + options.toString)

    val start = System.currentTimeMillis()
    val mode = SaveMode.Overwrite
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    val end = System.currentTimeMillis()
    println("Save time=" + (end-start)/1000.0 + " seconds.")

    var rowsLoaded = 0
    val stmt = conn.createStatement()
    val query = "SELECT COUNT(*) AS count FROM " + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert ( rowsLoaded == numDfRows)

    TestUtils.dropTable(conn, tableName)
  }

  it should "create a dataframe with different types and Append mode" in {
    var stmt = conn.createStatement()

    val tableName = "s2vdevtest03"

    TestUtils.createTableBySQL(conn, tableName, "create table " + tableName + " (txt VARCHAR(1024), a INTEGER, b BOOLEAN, float FLOAT)")

    val diffTypesText = spark.sparkContext.textFile("src/main/resources/diffTypesORC.txt")
    val rowRDD = diffTypesText.map(_.split(",")).map(p => Row(p(0), p(1).toInt, p(2).toBoolean, p(3).toFloat))
    val schema = StructType(Array(
      StructField("txt",StringType,nullable=true),
      StructField("a",IntegerType,nullable=true),
      StructField("b",BooleanType,nullable=true),
      StructField("float",FloatType,nullable=false)
    ))
    val df = spark.createDataFrame(rowRDD,schema)
    val numDfRows = df.count()
    df.show

    val options = writeOpts + ("table" -> tableName)
    val mode = SaveMode.Append

    var rows_exist = 0
    if (mode == SaveMode.Append) {
      stmt = conn.createStatement()
      val query = "SELECT COUNT(*) AS count FROM " + options("table")
      try {
        val rs = stmt.executeQuery(query)
        if (rs.next) {
          rows_exist = rs.getInt("count")
        }
      }
      finally {
        stmt.close()
      }
    }

    val start = System.currentTimeMillis()
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    val end = System.currentTimeMillis()
    println("Save time=" + (end-start)/1000.0 + " seconds.")

    var rowsLoaded = 0
    stmt = conn.createStatement()
    try {
      val query = "SELECT COUNT(*) AS count FROM " + options("table")
      val rs = stmt.executeQuery(query)
      if (rs.next) { rowsLoaded = rs.getInt("count") }
    }
    finally {
      stmt.close()
    }
    assert ( rowsLoaded == (rows_exist + numDfRows) )

    TestUtils.dropTable(conn, tableName)
  }

  it should "save a dataframe under specified schema in Overwrite mode" in {
    var stmt = conn.createStatement()

    val tableName = "s2vdevtest05"
    val dbschema = "S2VTestSchema"
    stmt.executeUpdate("DROP SCHEMA IF EXISTS " + dbschema + " CASCADE")
    stmt.executeUpdate("CREATE SCHEMA " + dbschema)

    val diffTypesText = spark.sparkContext.textFile("src/main/resources/diffTypesORC.txt")
    val rowRDD = diffTypesText.map(_.split(",")).map(p => Row(p(0), p(1).toInt, p(2).toBoolean, p(3).toFloat))
    val schema = StructType(Array(
      StructField("txt",StringType,nullable=true),
      StructField("a",IntegerType,nullable=true),
      StructField("b",BooleanType,nullable=true),
      StructField("float",FloatType,nullable=false)
    ))
    val df = spark.createDataFrame(rowRDD,schema)
    val numDfRows = df.count()
    df.show

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema)
    val mode = SaveMode.Overwrite

    var rows_exist = 0
    if (mode == SaveMode.Append) {
      stmt = conn.createStatement()
      val query = "SELECT COUNT(*) AS count FROM " + options("dbschema") + "." + options("table")
      try {
        val rs = stmt.executeQuery(query)
        if (rs.next) {
          rows_exist = rs.getInt("count")
        }
      }
      finally {
        stmt.close()
      }
    }

    val start = System.currentTimeMillis()
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    val end = System.currentTimeMillis()
    println("Save time=" + (end-start)/1000.0 + " seconds.")

    var rowsLoaded = 0
    stmt = conn.createStatement()
    val query = "SELECT COUNT(*) AS count FROM " + options("dbschema") + "." + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert ( rowsLoaded == (rows_exist + numDfRows) )
  }

  it should "save a dataframe under specified schema in Append mode" in {
    var stmt = conn.createStatement()

    val tableName = "s2vdevtest06"
    val dbschema = "S2VTestSchema"

    // the schema was created above in Test 06
    //stmt.executeUpdate("DROP SCHEMA IF EXISTS " + dbschema + " CASCADE")
    //stmt.executeUpdate("CREATE SCHEMA " + dbschema)
    TestUtils.createTableBySQL(conn, tableName, "create table " + dbschema + "." + tableName + " (txt VARCHAR(1024), a INTEGER, b BOOLEAN, float FLOAT)")

    val diffTypesText = spark.sparkContext.textFile("src/main/resources/diffTypesORC.txt")
    val rowRDD = diffTypesText.map(_.split(",")).map(p => Row(p(0), p(1).toInt, p(2).toBoolean, p(3).toFloat))
    val schema = StructType(Array(
      StructField("txt",StringType,nullable=true),
      StructField("a",IntegerType,nullable=true),
      StructField("b",BooleanType,nullable=true),
      StructField("float",FloatType,nullable=false)
    ))
    val df = spark.createDataFrame(rowRDD,schema)
    val numDfRows = df.count()
    df.show

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema)
    val mode = SaveMode.Append

    var rows_exist = 0
    if (mode == SaveMode.Append) {
      stmt = conn.createStatement()
      val query = "SELECT COUNT(*) AS count FROM " + options("dbschema") + "." + options("table")
      val rs = stmt.executeQuery(query)
      if (rs.next) { rows_exist = rs.getInt("count") }
      stmt.close()
    }

    val start = System.currentTimeMillis()
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    val end = System.currentTimeMillis()
    println("Save time=" + (end-start)/1000.0 + " seconds.")

    var rows = 0
    stmt = conn.createStatement()
    val query = "SELECT COUNT(*) AS count FROM " + options("dbschema") + "." + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rows = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert ( rows == (rows_exist + numDfRows) )
  }

  // TODO: Fix this test if we decide to support array/map/struct types
  it should "DataFrame with Complex type array" in {
    val tableName = "s2vdevtest08"
    val dbschema = "public"

    val json_string = """{"tags": ["home", "green"], "name":"Yin","address":{"city":"Columbus","state":"Ohio"}}"""
    TestUtils.createTableBySQL(conn, tableName, "create table " + tableName + " (address_array VARBINARY(65000), name_string LONG VARCHAR(65000), tags_array VARBINARY(65000))")

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema)
    val mode = SaveMode.Append

    val peopleRDD = spark.sparkContext.parallelize(json_string :: Nil)
    val df = spark.read.json(peopleRDD)

    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    val expectedMessage = "Error: Vertica currently does not support ArrayType, MapType, StructType;"

    assert (failureMessage.contains(expectedMessage))
  }

  it should "save date types over Vertica partitioned table." in {

    val log = Logger.getLogger(getClass.getName)
    val stmt = conn.createStatement()

    val tableName = "s2vdevtest09"
    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + dbschema + "." + tableName + " (tdate DATE NOT NULL,tsymbol VARCHAR(3) NOT NULL) PARTITION BY EXTRACT (year FROM tdate)")

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")

    // to convert our text file string dates into java.util.Date type then to
    // java.sql.Date type
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))

    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema, "failed_rows_percent_tolerance" -> "0.10")
    val mode = SaveMode.Append
    log.info(s"Test options:" + options.toString)

    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var rowsLoaded = 0
    val query = "SELECT COUNT(*) AS count FROM " + options("dbschema") + "." + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    println("REJECTED-ROWS:  Check the log here to verify these printed.")
    assert (rowsLoaded == numDfRows)
  }

  it should "reject invalid rows" in {

    val stmt = conn.createStatement()

    val tableName = "s2vdevtest10"
    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (a int)")

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")

    // to convert our text file string dates into java.util.Date type then to
    // java.sql.Date type
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))

    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema, "failed_rows_percent_tolerance" -> "0.10")
    val mode = SaveMode.Append

    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    val expectedMessage = "Number of columns in the target table should be greater or equal to number of columns in the DataFrame"
    assert (failureMessage.nonEmpty)
  }

  it should "Vertica column type mismatch in Append mode." in {
    val stmt = conn.createStatement()

    val tableName = "s2vdevtest13"
    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (a int, b float)")

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))

    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema, "failed_rows_percent_tolerance" -> "0.10")

    val mode = SaveMode.Append
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    assert (failureMessage.nonEmpty)
  }

  it should "halt if table name already exists as view." in {
    val stmt = conn.createStatement()

    val path = "/data/test/"
    val tableName = "s2vdevtest17"
    val viewName = tableName + "view"

    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (a int, b float)")
    stmt.execute("DROP VIEW  IF EXISTS "+ viewName)
    TestUtils.createTableBySQL(conn, viewName, "CREATE VIEW " + viewName + " as select * from " + tableName)

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))

    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)

    val options = writeOpts + ("table" -> viewName, "dbschema" -> dbschema,
    "staging_fs_url" -> (writeOpts("staging_fs_url").stripSuffix("/") + path))

    val mode = SaveMode.Overwrite
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    println("failureMessage=" + failureMessage)
    val expectedMessage = "Table name provided cannot refer to an existing view in Vertica"
    assert (failureMessage.contains(expectedMessage))
  }

  it should "ErrorIfExists mode should save to Vertica if table does not already exist in Vertica." in {
    var stmt = conn.createStatement()

    val path = "/data/test/"
    val tableName = "s2vdevtest18"
    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))

    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema,
      "staging_fs_url" -> (writeOpts("staging_fs_url").stripSuffix("/") + path))

    val mode = SaveMode.ErrorIfExists
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var rowsLoaded = 0
    stmt = conn.createStatement()
    val query = "SELECT COUNT(*) AS count FROM " + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert (rowsLoaded == numDfRows)
  }

  it should "ErrorIfExists mode should NOT save to Vertica if table already exists in Vertica." in {

    val stmt = conn.createStatement()

    val path = "/data/test/"
    val tableName = "s2vdevtest19"
    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (a int, b float)")

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))

    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema,
      "staging_fs_url" -> (writeOpts("staging_fs_url").stripSuffix("/") + path))

    val mode = SaveMode.ErrorIfExists
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    println("failureMessage=" + failureMessage)
    val expectedMessage = "exists, which is disallowed"
    assert (failureMessage.contains(expectedMessage))
  }

  it should "Ignore mode should save to Vertica if table does not already exist in Vertica." in {
    var stmt = conn.createStatement()

    val path = "/data/test/"
    val tableName = "s2vdevtest20"
    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))

    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema,
      "staging_fs_url" -> (writeOpts("staging_fs_url").stripSuffix("/") + path))

    val mode = SaveMode.Ignore
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var rowsLoaded = 0
    stmt = conn.createStatement()
    val query = "SELECT COUNT(*) AS count FROM " + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert (rowsLoaded == numDfRows)
  }

  it should "Ignore mode should NOT save to Vertica and ignores the load if table already exists in Vertica." in {
    val stmt = conn.createStatement()

    val path = "/data/test/"
    val tableName = "s2vdevtest21"
    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (a int, b float)")

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))

    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema,
      "staging_fs_url" -> (writeOpts("staging_fs_url").stripSuffix("/") + path))

    val mode = SaveMode.Ignore
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }

    //since load is ignored so there should be 0 rows in the target table
    var rowsLoaded = -1
    val query = "SELECT COUNT(*) AS count FROM " + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert (rowsLoaded == 0)
  }

  it should "Should throw clear error message if Vertica host address is not reachable." in {
    val stmt = conn.createStatement()

    val tableName = "s2vdevtest22"
    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (a int, b float)")

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))

    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema,
      "host" -> (writeOpts("host") + "xx"))

    val mode = SaveMode.Overwrite
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    println("failureMessage=" + failureMessage)
    val expectedMessage = "connection error"

    assert (failureMessage.contains(expectedMessage))
  }

  it should "Should throw clear error message if Vertica user name or password is invalid." in {
    val stmt = conn.createStatement()

    val tableName = "s2vdevtest23"
    val dbschema = "public"

    stmt.execute("DROP TABLE IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (a DATE, b float)")

    // Create a new user with a password and privileges to the test schema and table
    stmt.execute("DROP USER IF EXISTS test_user")
    stmt.execute("CREATE USER test_user IDENTIFIED BY 'password'")
    stmt.execute("GRANT ALL PRIVILEGES ON " + dbschema + "." + tableName + " TO test_user")

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))
    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)

    // Move DF to Vertica
    try {
      // Use the new user as opposed to the user for running the test

      val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema,
       "user" -> "test_user", "password" -> "oops")
      val mode = SaveMode.Overwrite
      var failureMessage = ""
      try {
        df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
      }
      catch {
        case e: java.lang.Exception => failureMessage = e.toString
      }
      assert (failureMessage.nonEmpty)
    } finally {
      stmt.execute("DROP USER IF EXISTS test_user")
      stmt.close()
    }
  }

  it should "Should throw clear error message if data frame contains a complex data type not supported by Vertica." in {
    val stmt = conn.createStatement()

    val path = "/data/test/"
    val tableName = "s2vdevtest24"
    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (a int, b float)")

    val schema = StructType(StructField("c", ArrayType(StringType), nullable=true)::Nil)
    val inputData = Seq(
      Seq("123","456"),
      null
    )

    val data = spark.sparkContext.parallelize(inputData).map(p => Row(p))
    val df = spark.createDataFrame(data, schema)
    df.show

    val numDfRows = df.count
    println("numDfRows=" + numDfRows)


    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema,
      "staging_fs_url" -> (writeOpts("staging_fs_url").stripSuffix("/") + path))

    val mode = SaveMode.Overwrite
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    println("failureMessage=" + failureMessage)
    val expectedMessage = "Error: Vertica currently does not support ArrayType, MapType, StructType;"

    assert (failureMessage.contains(expectedMessage))
  }

  it should "Should not try to save an empty dataframe." in {
    val stmt = conn.createStatement()

    val path = "/data/test/"
    val tableName = "s2vdevtest25"
    val dbschema = "public"

    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (a int, b float)")

    val schema = StructType(StructField("c", BooleanType, nullable=true)::Nil)
    val df = spark.createDataFrame(spark.sparkContext.emptyRDD[Row], schema)
    df.show

    val numDfRows = df.count
    println("numDfRows=" + numDfRows)

    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema,
      "staging_fs_url" -> (writeOpts("staging_fs_url").stripSuffix("/") + path))

    val mode = SaveMode.Overwrite
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    println("failureMessage=" + failureMessage)
    assert (failureMessage.nonEmpty)
  }

  it should "Should drop rejects table if it is empty." in {
    val stmt = conn.createStatement()

    val path = "/data/test/"
    val rand = scala.util.Random.nextInt(10000000)
    val tableName = "s2vdevtest26" + "_" + rand.toString
    val dbschema = "public"

    stmt.execute("DROP TABLE IF EXISTS "+ tableName)

    val data = spark.sparkContext.textFile("src/main/resources/date_test_file.txt")
    val formatter= new java.text.SimpleDateFormat("MM/dd/yy")
    val rowRDD = data.map(_.split(",")).map(p => {
      val sd: java.util.Date = formatter.parse(p(0))
      Row(new java.sql.Date(sd.getTime), p(1))
    })

    // Generate the schema based on the string of schema
    val schema = StructType(Array(
      StructField("tdate",DateType,nullable=true),
      StructField("tsymbol",StringType,nullable=true)
    ))

    val df = spark.createDataFrame(rowRDD, schema)
    val numDfRows = df.count()
    df.show
    println("numDfRows=" + numDfRows)


    val options = writeOpts + ("table" -> tableName, "dbschema" -> dbschema,
      "staging_fs_url" -> (writeOpts("staging_fs_url").stripSuffix("/") + path))

    val mode = SaveMode.Overwrite
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    // obtain the name of the rejects table
    var rejects_table = ""
    var query = "select job_name from S2V_JOB_STATUS_USER_" + writeOpts("user") + " where target_table_name = '" + tableName  + "'"
    var rs = stmt.executeQuery(query)
    if (rs.next) {
      rejects_table =  rs.getString("job_name") + "_rejects"
    }
    rs.close()

    // check if it has been dropped, since in this test no rows were rejected
    // and hence the rejects table should be empty.
    query = "select count(*) as cnt from v_catalog.tables where table_schema ILIKE '" +
      options("dbschema") + "' and table_name ILIKE '" +  rejects_table +  "'"
    var rejects_table_dropped = false
    try {
      rs = stmt.executeQuery(query)
      if (rs.next) {
        val count = rs.getInt("cnt")
        if (count == 0) rejects_table_dropped = true
      }
      rs.close()
      stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    }
    finally {
      stmt.close()
    }

    assert (rejects_table_dropped)
  }

  it should "Save a DataFrame when table name contains spaces in SaveMode.Overwrite" in {

    val tableName = "s2vdevtest27 with some spaces"

    // else local file path within this project.
    val datafile = "src/main/resources/datafile-100cols-100rows.csv"
    val testdata = spark.sparkContext.textFile(datafile)


    val options = writeOpts + ("table" -> tableName)

    val schema = TestUtils.getKmeans100colFloatSchema()
    val rowRDD = TestUtils.getKmeans100colFloatRowRDD(testdata)
    val df = spark.createDataFrame(rowRDD,schema)
    val numDfRows = df.count()

    // ALL save modes should work
    // SaveMode.Overwrite, SaveMode.Append, SaveMode.ErrorIfExists
    val log = Logger.getLogger(getClass.getName)
    log.info(s"Test options:" + options.toString)

    val start = System.currentTimeMillis()
    val mode = SaveMode.Overwrite
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    val end = System.currentTimeMillis()
    println("Save time=" + (end-start)/1000.0 + " seconds.")

    var rowsLoaded = 0
    val stmt = conn.createStatement()
    val query = "SELECT COUNT(*) AS count FROM \"" + options("table") + "\""
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert ( rowsLoaded == numDfRows )
  }

  it should "Save a DataFrame when table name contains '$' in SaveMode.Overwrite" in {
    val tableName = "s2vdevtest28_with_$dollar$_sign"
    val schema = StructType(StructField("abc", BooleanType, nullable=true)::Nil)
    val data = Seq(true, false, null)
    val rowRDD = spark.sparkContext.parallelize(data).map(p => Row(p))
    val df = spark.createDataFrame(rowRDD,schema)
    val numDfRows = df.count()


    val options = writeOpts + ("table" -> tableName)

    val mode = SaveMode.Overwrite
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var rowsLoaded = 0
    val stmt = conn.createStatement()
    val query = "SELECT COUNT(*) AS count FROM \"" + options("table") + "\""
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert ( rowsLoaded == numDfRows )
  }

  it should "Save a DataFrame when table name contains unicode chars in SaveMode.Overwrite" in {

    val tableName = "s2vdevtest29_with_unicode_" + "\u8704"
    val schema = StructType(StructField("abc", BooleanType, nullable=true)::Nil)
    val data = Seq(true, false, null)
    val rowRDD = spark.sparkContext.parallelize(data).map(p => Row(p))
    val df = spark.createDataFrame(rowRDD,schema)
    val numDfRows = df.count()


    val options = writeOpts + ("table" -> tableName)

    val mode = SaveMode.Overwrite
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var rowsLoaded = 0
    val stmt = conn.createStatement()
    val query = "SELECT COUNT(*) AS count FROM \"" + options("table") + "\""
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }

    assert ( rowsLoaded == numDfRows )
  }

  it should "Save a DataFrame with BinaryType SaveMode.Overwrite" in {

    // given:
    // val data = Seq("123","abc", null)
    // val schema = StructType(StructField("c", BinaryType, true)::Nil)
    // val rowRDD = sc.parallelize(data).map(p => Row(p))
    // this is the error observed:
    // Caused by: java.lang.ClassCastException: java.lang.String cannot be cast to [B
    //
    val tableName = "s2vdevtest30"

    val input1 = Array[Byte](192.toByte, 168.toByte, 1, 9, "123".toByte)
    val input2 = Array[Byte](1.toByte, 222.toByte, 1, 9, "-1".toByte)
    val data = Seq(input1, input2)
    val schema = StructType(StructField("a_binary_type_of_col", BinaryType, nullable=true)::Nil)
    val rowRDD = spark.sparkContext.parallelize(data).map(p => Row(p))
    val df = spark.createDataFrame(rowRDD,schema)
    df.show

    val numDfRows = df.count()

    val options = writeOpts + ("table" -> tableName)

    val mode = SaveMode.Overwrite
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var rowsLoaded = 0
    val stmt = conn.createStatement()
    val query = "SELECT COUNT(*) AS count FROM \"" + options("table") + "\""
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }

    assert ( rowsLoaded == numDfRows )
  }

  it should "Fail with helpful error message when trying to append to temp table." in {

    val tableName = "s2vdevtest33"
    val schema = StructType(StructField("a", BooleanType, nullable=true)::Nil)
    val data = Seq(true, false, null)
    val rowRDD = spark.sparkContext.parallelize(data).map(p => Row(p))
    val df = spark.createDataFrame(rowRDD,schema)

    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE GLOBAL temp TABLE " + tableName + " (a boolean)")

    val query = " select is_temp_table as t from v_catalog.tables where table_name='" + tableName + "'"
    val rs = stmt.executeQuery(query)
    var is_temp = false
    if (rs.next) {is_temp = rs.getBoolean("t") }

    val options = writeOpts + ("table" -> tableName)

    val mode = SaveMode.Append
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    println("failureMessage=" + failureMessage)
    val expectedMessage = "Cannot append to a temporary table"
    assert (failureMessage.contains(expectedMessage))
  }

  it should "Create Spark ByteType (represented as 'tinyint' in scala) as Vertica TINYINT type column." in {

    // java.lang.ClassCastException: [I cannot be cast to java.lang.Byte at scala.runtime.BoxesRunTime.unboxToByte(BoxesRunTime.java:98)
    // ERROR: java.sql.SQLDataException: [Vertica][VJDBC](6726) ERROR: Datatype mismatch: column 1 in the orc source [webhdfs://qadr-005:50070/I_dont_exist/S2V_job3899775852140326860/part-r-00071-e12751aa-d17a-4bed-bf86-0ea8763722f0.orc] has type TINYINT, expected varbinary(65000
    // https://github.com/apache/spark/blob/v1.6.2/sql/catalyst/src/main/scala/org/apache/spark/sql/types/ByteType.scala

    val tableName = "s2vdevtest34"
    val stmt = conn.createStatement()
    val schema = StructType(StructField("a", ByteType, nullable=true)::Nil)
    val a = 127.toByte
    val b = -128.toByte
    val data = spark.sparkContext.parallelize(Seq(a,b))
    val rowRDD = data.map(p => Row(p))
    val df = spark.createDataFrame(rowRDD,schema)

    df.show
    df.schema
    val numDfRows = df.count()

    stmt.execute("DROP TABLE IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (abc tinyint)")

    val options = writeOpts + ("table" -> tableName)

    val mode = SaveMode.Overwrite
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    var rowsLoaded = 0
    val query = "SELECT COUNT(*) AS count FROM \"" + options("table") + "\""
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert ( rowsLoaded == numDfRows )
  }

  it should "Verify dateType works" in {

    // java.lang.ClassCastException: [I cannot be cast to java.lang.Byte at scala.runtime.BoxesRunTime.unboxToByte(BoxesRunTime.java:98)
    // ERROR: java.sql.SQLDataException: [Vertica][VJDBC](6726) ERROR: Datatype mismatch: column 1 in the orc source [webhdfs://qadr-005:50070/I_dont_exist/S2V_job3899775852140326860/part-r-00071-e12751aa-d17a-4bed-bf86-0ea8763722f0.orc] has type TINYINT, expected varbinary(65000
    // https://github.com/apache/spark/blob/v1.6.2/sql/catalyst/src/main/scala/org/apache/spark/sql/types/ByteType.scala

    val tableName = "s2vdevtest35"
    val schema = StructType(StructField("dt", DateType, nullable=true)::Nil)

    // jeff
    val c = java.util.Calendar.getInstance()
    c.set(1,1,1, 1,1,1)
    val ms = new java.util.Date(c.getTimeInMillis)

    //hua
    val date1 = new java.text.SimpleDateFormat("yyyy-MM-dd").parse("2016-07-05")
    val date3 = new java.text.SimpleDateFormat("yyyy-MM-dd").parse("0001-01-01")

    val inputData = Seq(
      new java.sql.Date(date1.getTime),
      new java.sql.Date(date3.getTime),
      new java.sql.Date(ms.getTime),
      null
    )
    val rowRDD = spark.sparkContext.parallelize(inputData).map(p => Row(p))
    val df = spark.createDataFrame(rowRDD, schema)
    df.show
    df.schema
    val numDfRows = df.count()

    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (a date)")

    val options = writeOpts + ("table" -> tableName)

    val mode = SaveMode.Overwrite
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var rowsLoaded = 0
    val query = "SELECT COUNT(*) AS cnt FROM \"" + options("table") + "\""
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        rowsLoaded = rs.getInt("cnt")
      }
    }
    finally {
      stmt.close()
    }
    assert ( rowsLoaded == numDfRows )
  }

  it should "Verify decimal type works." in {

    val tableName = "s2vdevtest37"
    val schema = StructType(StructField("dec", DecimalType(38,2), nullable=true)::Nil)
    val inputData = Seq(
      Decimal(1.23456),
      Decimal(-1.23456),
      Decimal(12345678901234567890.123),
      null
    )

    val rowRDD = spark.sparkContext.parallelize(inputData).map(p => Row(p))
    val df = spark.createDataFrame(rowRDD, schema)
    df.show
    println("df.schema=" + df.schema)
    val numDfRows = df.count()

    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (dec  numeric(38,2))")

    val options = writeOpts + ("table" -> tableName)

    val mode = SaveMode.Overwrite
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var count = 0
    val query = "SELECT COUNT(*) AS cnt FROM \"" + options("table") + "\""
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        count = rs.getInt("cnt")
      }
    }
    finally {
      stmt.close()
    }
    assert (count == numDfRows)
  }

  it should "Verify long type works correctly." in {
    val tableName = "s2vdevtest38"
    val schema = StructType(StructField("longs", LongType, nullable=true)::Nil)
    val inputData = Seq(
      9223372036854775807L
    )
    val rowRDD = spark.sparkContext.parallelize(inputData).map(p => Row(p))
    val df = spark.createDataFrame(rowRDD, schema)
    df.show
    println("df.schema=" + df.schema)
    val numDfRows = df.count()

    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE " + tableName + " (longs  BIGINT)")

    val options = writeOpts + ("table" -> tableName)

    val mode = SaveMode.Append
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var count = 0
    val query = "SELECT COUNT(*) AS cnt FROM \"" + options("table") + "\""
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        count = rs.getInt("cnt")
      }
    }
    finally {
      stmt.close()
    }
    assert (count == numDfRows)
  }

  it should "Reject 1/5 of rows, and hence not pass failed_rows_percent_tolerance.  Append mode." in {

    val tableName = "s2vdevtest39"
    val schema = StructType(StructField("i", IntegerType, nullable=true)::Nil)
    val inputData: Seq[Any] = List(500, -500, 2147483647, -2147483648, null)
    val rowRDD = spark.sparkContext.parallelize(inputData).map(p => Row(p))
    val df = spark.createDataFrame(rowRDD, schema).coalesce(5)
    df.show
    println("df.schema=" + df.schema)

    // create and append to a table that disallows nulls, these will be rejected.
    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE \"" + tableName + "\" (i  INTEGER not null)")

    val options = writeOpts + ("table" -> tableName,
    "failed_rows_percent_tolerance" -> "0.199")

    val mode = SaveMode.Append
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    println("failureMessage=" + failureMessage)
    val expectedMessage = "Failed rows percent was greater than user specified tolerance for table:"
    assert (failureMessage.nonEmpty)
  }

  it should "Reject 1/5 of rows, and pass failed_rows_percent_tolerance.  Append mode" in {

    val tableName = "s2vdevtest40"
    val schema = StructType(StructField("i", IntegerType, nullable=true)::Nil)
    val inputData: Seq[Any] = List(500, -500, 2147483647, -2147483648, null)
    val rowRDD = spark.sparkContext.parallelize(inputData).map(p => Row(p))
    val df = spark.createDataFrame(rowRDD, schema).coalesce(5)
    df.show
    println("df.schema=" + df.schema)
    val numBadRows = 1  // the null due to "NOT NULL" in create DDL

    // create and append to a table that disallows nulls, these will be rejected.
    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS "+ tableName)
    TestUtils.createTableBySQL(conn, tableName, "CREATE TABLE \"" + tableName + "\" (i  INTEGER not null)")
    stmt.execute("INSERT INTO \"" + tableName + "\" VALUES(1)")

    val options = writeOpts + ("table" -> tableName,
      "failed_rows_percent_tolerance" -> "0.5")

    // get prev count
    var countold = 0
    val query = "SELECT COUNT(*) AS cnt FROM \"" + options("table") + "\""
    var rs = stmt.executeQuery(query)
    if (rs.next) { countold = rs.getInt("cnt") }

    // S2V append
    val mode = SaveMode.Append
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    assert(failureMessage.isEmpty)
  }

  it should "create a dataframe and load all 100 rows successfully for SaveMode.Append when table does not exist" in {
    val tableName = "s2vdevtest44"
    // else local file path within this project.
    val datafile = "src/main/resources/datafile-100cols-100rows.csv"
    val testdata = spark.sparkContext.textFile(datafile)

    val options = writeOpts + ("table" -> tableName)

    val schema = TestUtils.getKmeans100colFloatSchema()
    val rowRDD = TestUtils.getKmeans100colFloatRowRDD(testdata)
    val df = spark.createDataFrame(rowRDD,schema)
    val numDfRows = df.count()

    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)

    // ALL save modes should work
    // SaveMode.Overwrite, SaveMode.Append, SaveMode.ErrorIfExists
    val log = Logger.getLogger(getClass.getName)
    log.info(s"Test options:" + options.toString)

    val mode = SaveMode.Append
    val start = System.currentTimeMillis()
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    val end = System.currentTimeMillis()
    println("Save time=" + (end-start)/1000.0 + " seconds.")

    var totalRows = 0
    val query = "SELECT COUNT(*) AS count FROM " + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        totalRows = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    log.info(s"APPEND MODE to table:" + options("table") + "  total rows is now=" + totalRows)

    assert (totalRows == numDfRows)
  }

  it should "fail to save DataFrame with duplicate column names if table does not exist." in {
    // If table does not exist and no custom DDL, the connector creates table based on DF schema
    val tableName = "s2vdevtest45"

    val options = writeOpts + ("table" -> tableName)

    val schema = StructType(Array(
      StructField("fullname", StringType, nullable=false),
      StructField("age", IntegerType, nullable=true),
      StructField("age", IntegerType, nullable=true),
      StructField("hiredate", DateType, nullable=false),
      StructField("region", StringType, nullable=false)
    ))
    val rows = spark.sparkContext.parallelize(Array(
      Row("fullname1", 35, null, Date.valueOf("2009-09-09"), "south"),
      Row("fullname2", null, null, Date.valueOf("2019-09-09"), "north")
    ))

    val df = spark.createDataFrame(rows,schema)
    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)

    val log = Logger.getLogger(getClass.getName)
    log.info(s"Test options:" + options.toString)
    val mode = SaveMode.Overwrite

    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }

    val expectedMessage = "Duplicate column \"age\" in create table statement"
    log.info("failureMessage: "+ failureMessage)
    log.info("expectedMessage: "+ expectedMessage)
    assert (failureMessage.contains(expectedMessage))
  }

  it should "fail to save DataFrame with duplicate column names if load by name." in {
    // In order to load by name:
    //  - all columns in dataframe exist in target table. Columns order doesn't matter.

    val tableName = "s2vdevtest46"
    val tableDDL = "CREATE TABLE " + tableName + "(key IDENTITY(1,1), fullname VARCHAR(1024) NOT NULL, " +
      "age INTEGER, age2 INTEGER, region VARCHAR(1024) NOT NULL, loaddate TIMESTAMP DEFAULT NOW())"

    val options = writeOpts + ("table" -> tableName)

    // Load by name on an existing table
    val schema = StructType(Array(
      StructField("fullname", StringType, nullable=false),
      StructField("age", IntegerType, nullable=true),
      StructField("age", IntegerType, nullable=true),
      StructField("region", StringType, nullable=false)
    ))
    val rows = spark.sparkContext.parallelize(Array(
      Row("fullname1", 35, null, "south")
    ))

    val df = spark.createDataFrame(rows,schema)
    val stmt = conn.createStatement()
    stmt.execute("SELECT SET_VERTICA_OPTIONS('BASIC', 'DISABLE_DEPARSE_CHECK')")
    stmt.execute("DROP TABLE  IF EXISTS "+ tableName)
    stmt.execute(tableDDL)

    val mode = SaveMode.Append
    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }

    val expectedMessage = "Duplicate column(s) : \"age\" found, cannot save to file."
    assert (failureMessage.contains(expectedMessage))
  }

  it should "fail to save a DataFrame with duplicate column names using parquet format." in {
    // Spark allow you to have a DF with duplicate column names but doesn't allow you to save it to parquet format.
    val tableName = "s2vdevtest48"
    val tableDDL = "CREATE TABLE " + tableName + "(age1 integer, age2 integer, age3 integer, age4 integer, age5 integer)"

    val options = writeOpts + ("table" -> tableName,
      "target_table_ddl" -> tableDDL
      )

    // Spark won't allow you to save a DF with duplicate column names in parquet format
    val schema = StructType(Array(
      StructField("age", IntegerType, nullable=true),
      StructField("age", IntegerType, nullable=true),
      StructField("age", IntegerType, nullable=true),
      StructField("age", IntegerType, nullable=true),
      StructField("age", IntegerType, nullable=true)
    ))
    val rows = spark.sparkContext.parallelize(Array(
      Row(1, 2, 3, 4, 5)
    ))

    val df = spark.createDataFrame(rows,schema)

    val mode = SaveMode.Overwrite

    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    val expectedMessage = "Duplicate column(s) : \"age\" found, cannot save to file."
    assert (failureMessage.contains(expectedMessage))
  }

  it should "load data successfully using a custom DDL and a custom COPY column list together." in {
    val tableName = "s2vdevtest49"

    val options = writeOpts + ("table" -> tableName,
    "target_table_ddl" -> "CREATE TABLE s2vdevtest49(key IDENTITY(1,1), FULLNAME VARCHAR(1024) NOT NULL, AGE INTEGER, hiredate DATE NOT NULL, region VARCHAR(1024) NOT NULL, loaddate TIMESTAMP DEFAULT NOW()) PARTITION BY EXTRACT (year FROM hiredate);CREATE PROJECTION s2vdevtestORC49_p(key, fullname, hiredate) AS SELECT key, fullname, hiredate FROM s2vdevtest49 SEGMENTED BY HASH(key) ALL NODES;",
    "copy_column_list" -> "firstname FILLER VARCHAR(1024),middlename FILLER VARCHAR(1024),lastname FILLER VARCHAR(1024),fullname AS firstname||' '|| NVL(middlename,'') ||' '||lastname,age as NULL,hiredate,region")

    val schema = StructType(Array(
      StructField("first_name", StringType, nullable=false),
      StructField("middle_name", StringType, nullable=true),
      StructField("last_name", StringType, nullable=false),
      StructField("hire_date", DateType, nullable=false),
      StructField("region", StringType, nullable=false)
    ))
    val rows = spark.sparkContext.parallelize(Array(
      Row("fn","mn","ln", Date.valueOf("2015-03-18"), "west")
    ))

    val df = spark.createDataFrame(rows,schema)
    val numDfRows = df.count()
    val stmt = conn.createStatement()

    val mode = SaveMode.Overwrite

    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var totalRows = 0
    val query = "SELECT COUNT(*) AS count FROM " + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        totalRows = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert (totalRows == numDfRows)
  }

  it should "load data successfully using a custom DDL and a default COPY column list (Load by Name)." in {
    // Load by name requires all dataframe column names to exist in target table
    val tableName = "s2vdevtest50"

    val options = writeOpts + ("table" -> tableName,
      "target_table_ddl" -> "CREATE TABLE s2vdevtest50(key IDENTITY(1,1), FULLNAME VARCHAR(1024) NOT NULL, AGE INTEGER, hiredate DATE NOT NULL, region VARCHAR(1024) NOT NULL, loaddate TIMESTAMP DEFAULT NOW()) PARTITION BY EXTRACT (year FROM hiredate);CREATE PROJECTION s2vdevtest50_p(key, fullname, hiredate) AS SELECT key, fullname, hiredate FROM s2vdevtest50 SEGMENTED BY HASH(key) ALL NODES;")

    // It will load by name because all dataframe column names match target column names.
    // Columns order doesn't matter.
    val schema = StructType(Array(
      StructField("fullname", StringType, nullable=false),
      StructField("age", IntegerType, nullable=true),
      StructField("region", StringType, nullable=false),
      StructField("hiredate", DateType, nullable=false)
    ))
    val rows = spark.sparkContext.parallelize(Array(
      Row("fn", 1, "south", Date.valueOf("2015-03-18"))
    ))

    val df = spark.createDataFrame(rows,schema)
    val numDfRows = df.count()
    val stmt = conn.createStatement()

    val log = Logger.getLogger(getClass.getName)
    log.info(s"Test options: " + options.toString)
    val mode = SaveMode.Overwrite

    val start = System.currentTimeMillis()
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    val end = System.currentTimeMillis()
    log.info("Save time: " + (end-start)/1000.0 + " seconds.")

    var totalRows = 0
    val query = "SELECT COUNT(*) AS count FROM " + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        totalRows = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    log.info(s"Overwrite Mode to table: " + options("table") + "  total rows is now: " + totalRows)
    assert (totalRows == numDfRows)
  }

  it should "load data successfully using a custom DDL and a default COPY column list (Load by Position)." in {
    // Load by position requires number of columns in the DF equals to number of columns in target table
    val tableName = "s2vdevtest51"

    val options = writeOpts + ("table" -> tableName,
      "target_table_ddl" -> "CREATE TABLE s2vdevtest51(FULLNAME VARCHAR(1024) NOT NULL, AGE INTEGER, hiredate DATE NOT NULL, region VARCHAR(1024) NOT NULL) PARTITION BY EXTRACT (year FROM hiredate);CREATE PROJECTION s2vdevtest51_p(fullname, hiredate) AS SELECT fullname, hiredate FROM s2vdevtest51 SEGMENTED BY HASH(fullname) ALL NODES;"
    )

    // It will load by position because not all dataframe column names match target column names.
    // Order of the columns in dataframe needs to match order of the columns in target table.
    val schema = StructType(Array(
      StructField("full_name", StringType, nullable=false),
      StructField("age_emp", IntegerType, nullable=true),
      StructField("hire_date", DateType, nullable=false),
      StructField("location", StringType, nullable=false)
    ))
    val rows = spark.sparkContext.parallelize(Array(
      Row("fn", 1, Date.valueOf("2015-03-18"), "south")
    ))

    val df = spark.createDataFrame(rows,schema)
    val numDfRows = df.count()
    val stmt = conn.createStatement()

    val log = Logger.getLogger(getClass.getName)
    log.info(s"Test options: " + options.toString)
    val mode = SaveMode.Overwrite

    val start = System.currentTimeMillis()
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    val end = System.currentTimeMillis()
    log.info("Save time: " + (end-start)/1000.0 + " seconds.")

    var totalRows = 0
    val query = "SELECT COUNT(*) AS count FROM " + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        totalRows = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    log.info(s"Overwrite mode to table: " + options("table") + "  total rows is now: " + totalRows)
    assert (totalRows == numDfRows)
  }

  it should "load data successfully in Append mode for default DDL and default COPY column list." in {
    // In the case of Append, try load by name on the pre-existing target table
    val tableName = "s2vdevtest52"
    val existingTable = "CREATE TABLE " + tableName + "(key IDENTITY(1,1), fullname VARCHAR(1024) NOT NULL, age INTEGER NOT NULL, hiredate DATE NOT NULL, region VARCHAR(1024) NOT NULL, loaddate TIMESTAMP DEFAULT NOW()) PARTITION BY EXTRACT (year FROM hiredate)"

    // No custom DDL or COPY column list, instead append to a pre-existing target table
    val options = writeOpts + ("table" -> tableName)

    // It will try load by name first, column order doesn't matter.
    val schema = StructType(Array(
      StructField("fullname", StringType, nullable=false),
      StructField("region", StringType, nullable=false),
      StructField("age", IntegerType, nullable=true),
      StructField("hiredate", DateType, nullable=false)
    ))
    val rows = spark.sparkContext.parallelize(Array(
      Row("fn", "north", 30, Date.valueOf("2018-05-22"))
    ))

    val df = spark.createDataFrame(rows,schema)
    val numDfRows = df.count()
    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS "+ tableName)
    stmt.execute(existingTable)

    val log = Logger.getLogger(getClass.getName)
    log.info(s"Test options: " + options.toString)
    val mode = SaveMode.Append

    val start = System.currentTimeMillis()
    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    val end = System.currentTimeMillis()
    log.info("Save time: " + (end-start)/1000.0 + " seconds.")

    var totalRows = 0
    val query = "SELECT COUNT(*) AS count FROM " + options("table")
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        totalRows = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    log.info(s"Append mode to table: " + options("table") + "  total rows is now: " + totalRows)
    assert (totalRows == numDfRows)
  }

  it should "fail to save a DF with column names with spaces in parquet format" in {
    val tableName = "Quoted_Identifiers"

    val options = writeOpts + ("table" -> tableName)
    val rows = spark.sparkContext.parallelize(Array(
      Row(1)
    ))
    val schema = StructType(Array(
      StructField("My sequence", IntegerType, nullable=false)
    ))

    val df = spark.createDataFrame(rows,schema)

    val mode = SaveMode.Overwrite

    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    val expectedMessage = "Attribute name \"My sequence\" contains invalid character(s) among \" ,;{}()\\n\\t=\". Please use alias to rename it.;"
    assert (failureMessage.contains(expectedMessage))
  }

  it should "save a 1600 column table using default copy logic." in {
    val tableName = "1600ColumnTable"

    val options = writeOpts + ("table" -> tableName)
    val df = spark.read.format("org.apache.spark.sql.execution.datasources.csv.CSVFileFormat")
      .option("header", "true").load("src/main/resources/1600ColumnTable.csv")

    val numDfRows = df.count()
    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS " + "\"" + options("table") + "\";")

    val mode = SaveMode.Append

    df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()

    var totalRows = 0
    val query = "SELECT COUNT(*) AS count FROM " + "\"" + options("table") + "\";"
    try {
      val rs = stmt.executeQuery(query)
      if (rs.next) {
        totalRows = rs.getInt("count")
      }
    }
    finally {
      stmt.close()
    }
    assert (totalRows == numDfRows)
  }

  it should "fail to save a DF if target_table_ddl doesn't generate the right table" in {
    // table name is inconsistent with the DDL
    val tableName = "targetTable"
    val target_table_ddl = "CREATE TABLE peopleTable (name varchar(65000) not null, age integer not null);"
    val options = writeOpts + ("table" -> tableName,
      "target_table_ddl" -> target_table_ddl
      )

    val rows = spark.sparkContext.parallelize(Array(
      Row("name1", 30)
    ))
    val schema = StructType(Array(
      StructField("name", StringType, nullable=false),
      StructField("age", IntegerType, nullable=false)
    ))

    val df = spark.createDataFrame(rows,schema)

    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS " + "\"" + options("table") + "\";")
    stmt.execute("DROP TABLE IF EXISTS peopleTable")

    val mode = SaveMode.Overwrite

    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    val expectedMessage = "Parameter target_table_dll did not generate the right table"
    assert (failureMessage.contains(expectedMessage))
  }

  it should "fail to save a DF if there are syntax errors in target_table_ddl" in {
    // table name is inconsistent with the DDL
    val tableName = "targetTable"
    val target_table_ddl = "CREATE TBLE targetTable (name varchar(65000) not null, age integer not null);"
    val options = writeOpts + ("table" -> tableName,
      "target_table_ddl" -> target_table_ddl
    )

    val rows = spark.sparkContext.parallelize(Array(
      Row("name1", 30)
    ))
    val schema = StructType(Array(
      StructField("name", StringType, nullable=false),
      StructField("age", IntegerType, nullable=false)
    ))

    val df = spark.createDataFrame(rows,schema)

    val mode = SaveMode.Overwrite

    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    val expectedMessage = "[Vertica][VJDBC](4856) ERROR: Syntax error at or near \"TBLE\""
    assert (failureMessage.contains(expectedMessage))
  }

  it should "fail to save a DF if there are syntax errors in copy_column_list" in {
    // copy column list doesn't need parentheses, EXPLAIN COPY catches this issue
    val tableName = "targetTable"
    val target_table_ddl = "CREATE TABLE " + tableName + "(a int, b varchar(100))"
    val copy_column_list = "(b, a)"
    val options = writeOpts + ("table" -> tableName,
      "target_table_ddl" -> target_table_ddl,
      "copy_column_list" -> copy_column_list
      )

    val rows = spark.sparkContext.parallelize(Array(
      Row("name1", 30)
    ))
    val schema = StructType(Array(
      StructField("name", StringType, nullable=false),
      StructField("age", IntegerType, nullable=false)
    ))

    val df = spark.createDataFrame(rows,schema)
    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS " + "\"" + options("table") + "\";")

    val mode = SaveMode.Overwrite

    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    val expectedMessage = "Custom COPY column list is invalid: ((b, a))"
    assert (failureMessage.contains(expectedMessage))
  }

  it should "fail to generate default copy by name and by position if cols names and count are different" in {

    // this test fails both the by name and by position default copy generation
    val tableName = "targetTable"
    val target_table_ddl = "CREATE TABLE " + tableName + "(name varchar(65000), age integer, flag boolean, area varchar(50))"
    val options = writeOpts + ("table" -> tableName,
    "target_table_ddl" -> target_table_ddl)

    val rows = spark.sparkContext.parallelize(Array(
      Row("name1", 30, "west")
    ))
    val schema = StructType(Array(
      StructField("name", StringType, nullable=false),
      StructField("age", IntegerType, nullable=false),
      StructField("region", StringType, nullable=false)
    ))

    val df = spark.createDataFrame(rows,schema)
    val stmt = conn.createStatement()
    stmt.execute("DROP TABLE IF EXISTS " + "\"" + options("table") + "\";")

    val mode = SaveMode.Overwrite

    var failureMessage = ""
    try {
      df.write.format("com.vertica.spark.datasource.VerticaSource").options(options).mode(mode).save()
    }
    catch {
      case e: java.lang.Exception => failureMessage = e.toString
    }
    val expectedMessage = "[Vertica][VJDBC](6726) ERROR: Datatype mismatch: column 3 in the orc source"
    assert (failureMessage.contains(expectedMessage))
  }
}
