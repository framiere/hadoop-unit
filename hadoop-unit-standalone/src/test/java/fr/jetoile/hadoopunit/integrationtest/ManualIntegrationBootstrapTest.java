/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.jetoile.hadoopunit.integrationtest;


import fr.jetoile.hadoopunit.test.hdfs.HdfsUtils;
import fr.jetoile.hadoopunit.Config;
import fr.jetoile.hadoopunit.component.OozieBootstrap;
import fr.jetoile.hadoopunit.component.SolrCloudBootstrap;
import fr.jetoile.hadoopunit.exception.BootstrapException;
import fr.jetoile.hadoopunit.kafka.consumer.KafkaTestConsumer;
import fr.jetoile.hadoopunit.kafka.producer.KafkaTestProducer;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.oozie.client.OozieClient;
import org.apache.oozie.client.WorkflowJob;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.zookeeper.KeeperException;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.sql.*;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static junit.framework.TestCase.assertNotNull;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

@Ignore
public class ManualIntegrationBootstrapTest {

    static private Configuration configuration;

    static private Logger LOGGER = LoggerFactory.getLogger(ManualIntegrationBootstrapTest.class);


    @BeforeClass
    public static void setup() throws BootstrapException {
        try {
            configuration = new PropertiesConfiguration(Config.DEFAULT_PROPS_FILE);
        } catch (ConfigurationException e) {
            throw new BootstrapException("bad config", e);
        }
    }


    @AfterClass
    public static void tearDown() throws BootstrapException {
    }

    @Test
    public void solrCloudShouldStart() throws IOException, SolrServerException, KeeperException, InterruptedException {

        String collectionName = configuration.getString(SolrCloudBootstrap.SOLR_COLLECTION_NAME);

        String zkHostString = configuration.getString(Config.ZOOKEEPER_HOST_KEY) + ":" + configuration.getInt(Config.ZOOKEEPER_PORT_KEY);
        CloudSolrClient client = new CloudSolrClient(zkHostString);

        for (int i = 0; i < 1000; ++i) {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField("cat", "book");
            doc.addField("id", "book-" + i);
            doc.addField("name", "The Legend of the Hobbit part " + i);
            client.add(collectionName, doc);
            if (i % 100 == 0) client.commit(collectionName);  // periodically flush
        }
        client.commit("collection1");

        SolrDocument collection1 = client.getById(collectionName, "book-1");

        assertNotNull(collection1);

        assertThat(collection1.getFieldValue("name")).isEqualTo("The Legend of the Hobbit part 1");


        client.close();
    }

    @Test
    public void kafkaShouldStart() throws Exception {

        // Producer
        KafkaTestProducer kafkaTestProducer = new KafkaTestProducer.Builder()
                .setKafkaHostname(configuration.getString(Config.KAFKA_HOSTNAME_KEY))
                .setKafkaPort(configuration.getInt(Config.KAFKA_PORT_KEY))
                .setTopic(configuration.getString(Config.KAFKA_TEST_TOPIC_KEY))
                .setMessageCount(configuration.getInt(Config.KAFKA_TEST_MESSAGE_COUNT_KEY))
                .build();
        kafkaTestProducer.produceMessages();


        // Consumer
        List<String> seeds = new ArrayList<String>();
        seeds.add(configuration.getString(Config.KAFKA_HOSTNAME_KEY));
        KafkaTestConsumer kafkaTestConsumer = new KafkaTestConsumer();
        kafkaTestConsumer.consumeMessages2(
                configuration.getInt(Config.KAFKA_TEST_MESSAGE_COUNT_KEY),
                configuration.getString(Config.KAFKA_TEST_TOPIC_KEY),
                0,
                seeds,
                configuration.getInt(Config.KAFKA_PORT_KEY));

        // Assert num of messages produced = num of message consumed
        Assert.assertEquals(configuration.getLong(Config.KAFKA_TEST_MESSAGE_COUNT_KEY),
                kafkaTestConsumer.getNumRead());
    }

    @Test
    public void hiveServer2ShouldStart() throws InterruptedException, ClassNotFoundException, SQLException {

//        assertThat(Utils.available("127.0.0.1", 20103)).isFalse();

        // Load the Hive JDBC driver
        LOGGER.info("HIVE: Loading the Hive JDBC Driver");
        Class.forName("org.apache.hive.jdbc.HiveDriver");

        //
        // Create an ORC table and describe it
        //
        // Get the connection
        Connection con = DriverManager.getConnection("jdbc:hive2://" +
                        configuration.getString(Config.HIVE_SERVER2_HOSTNAME_KEY) + ":" +
                        configuration.getInt(Config.HIVE_SERVER2_PORT_KEY) + "/" +
                        configuration.getString(Config.HIVE_TEST_DATABASE_NAME_KEY),
                "user",
                "pass");

        // Create the DB
        Statement stmt;
        try {
            String createDbDdl = "CREATE DATABASE IF NOT EXISTS " +
                    configuration.getString(Config.HIVE_TEST_DATABASE_NAME_KEY);
            stmt = con.createStatement();
            LOGGER.info("HIVE: Running Create Database Statement: {}", createDbDdl);
            stmt.execute(createDbDdl);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Drop the table incase it still exists
        String dropDdl = "DROP TABLE " + configuration.getString(Config.HIVE_TEST_DATABASE_NAME_KEY) + "." +
                configuration.getString(Config.HIVE_TEST_TABLE_NAME_KEY);
        stmt = con.createStatement();
        LOGGER.info("HIVE: Running Drop Table Statement: {}", dropDdl);
        stmt.execute(dropDdl);

        // Create the ORC table
        String createDdl = "CREATE TABLE IF NOT EXISTS " +
                configuration.getString(Config.HIVE_TEST_DATABASE_NAME_KEY) + "." +
                configuration.getString(Config.HIVE_TEST_TABLE_NAME_KEY) + " (id INT, msg STRING) " +
                "PARTITIONED BY (dt STRING) " +
                "CLUSTERED BY (id) INTO 16 BUCKETS " +
                "STORED AS ORC tblproperties(\"orc.compress\"=\"NONE\")";
        stmt = con.createStatement();
        LOGGER.info("HIVE: Running Create Table Statement: {}", createDdl);
        stmt.execute(createDdl);

        // Issue a describe on the new table and display the output
        LOGGER.info("HIVE: Validating Table was Created: ");
        ResultSet resultSet = stmt.executeQuery("DESCRIBE FORMATTED " +
                configuration.getString(Config.HIVE_TEST_TABLE_NAME_KEY));
        int count = 0;
        while (resultSet.next()) {
            ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
            for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
                System.out.print(resultSet.getString(i));
            }
            System.out.println();
            count++;
        }
        assertEquals(33, count);

        // Drop the table
        dropDdl = "DROP TABLE " + configuration.getString(Config.HIVE_TEST_DATABASE_NAME_KEY) + "." +
                configuration.getString(Config.HIVE_TEST_TABLE_NAME_KEY);
        stmt = con.createStatement();
        LOGGER.info("HIVE: Running Drop Table Statement: {}", dropDdl);
        stmt.execute(dropDdl);
    }



    @Test
    public void hdfsShouldStart() throws Exception {

//        assertThat(Utils.available("127.0.0.1", configuration.getInt(Config.HDFS_NAMENODE_HTTP_PORT_KEY))).isFalse();
//
//        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
//        conf.set("fs.default.name", "hdfs://127.0.0.1:" + configuration.getInt(Config.HDFS_NAMENODE_PORT_KEY));
//
//        URI uri = URI.create ("hdfs://127.0.0.1:" + configuration.getInt(Config.HDFS_NAMENODE_PORT_KEY));
//
//        FileSystem hdfsFsHandle = FileSystem.get (uri, conf);
        FileSystem hdfsFsHandle = HdfsUtils.INSTANCE.getFileSystem();


        FSDataOutputStream writer = hdfsFsHandle.create(new Path(configuration.getString(Config.HDFS_TEST_FILE_KEY)));
        writer.writeUTF(configuration.getString(Config.HDFS_TEST_STRING_KEY));
        writer.close();

        // Read the file and compare to test string
        FSDataInputStream reader = hdfsFsHandle.open(new Path(configuration.getString(Config.HDFS_TEST_FILE_KEY)));
        assertEquals(reader.readUTF(), configuration.getString(Config.HDFS_TEST_STRING_KEY));
        reader.close();
        hdfsFsHandle.close();

        URL url = new URL(
                String.format( "http://localhost:%s/webhdfs/v1?op=GETHOMEDIRECTORY&user.name=guest",
                        configuration.getInt( Config.HDFS_NAMENODE_HTTP_PORT_KEY ) ) );
        URLConnection connection = url.openConnection();
        connection.setRequestProperty( "Accept-Charset", "UTF-8" );
        BufferedReader response = new BufferedReader( new InputStreamReader( connection.getInputStream() ) );
        String line = response.readLine();
        response.close();
        assertThat("{\"Path\":\"/user/guest\"}").isEqualTo(line);

    }

    @Test
    public void hBaseShouldStart() throws Exception {

        String tableName = configuration.getString(Config.HBASE_TEST_TABLE_NAME_KEY);
        String colFamName = configuration.getString(Config.HBASE_TEST_COL_FAMILY_NAME_KEY);
        String colQualiferName = configuration.getString(Config.HBASE_TEST_COL_QUALIFIER_NAME_KEY);
        Integer numRowsToPut = configuration.getInt(Config.HBASE_TEST_NUM_ROWS_TO_PUT_KEY);

        org.apache.hadoop.conf.Configuration hbaseConfiguration = HBaseConfiguration.create();
        hbaseConfiguration.set("hbase.zookeeper.quorum", configuration.getString(Config.ZOOKEEPER_HOST_KEY));
        hbaseConfiguration.setInt("hbase.zookeeper.property.clientPort", configuration.getInt(Config.ZOOKEEPER_PORT_KEY));
        hbaseConfiguration.set("hbase.master", "127.0.0.1:" + configuration.getInt(Config.HBASE_MASTER_PORT_KEY));
        hbaseConfiguration.set("zookeeper.znode.parent", configuration.getString(Config.HBASE_ZNODE_PARENT_KEY));


        LOGGER.info("HBASE: Creating table {} with column family {}", tableName, colFamName);
        createHbaseTable(tableName, colFamName, hbaseConfiguration);

        LOGGER.info("HBASE: Populate the table with {} rows.", numRowsToPut);
        for (int i = 0; i < numRowsToPut; i++) {
            putRow(tableName, colFamName, String.valueOf(i), colQualiferName, "row_" + i, hbaseConfiguration);
        }

        LOGGER.info("HBASE: Fetching and comparing the results");
        for (int i = 0; i < numRowsToPut; i++) {
            Result result = getRow(tableName, colFamName, String.valueOf(i), colQualiferName, hbaseConfiguration);
            assertEquals("row_" + i, new String(result.value()));
        }

    }


    @Test
    public void oozieShouldStart() throws Exception {

        LOGGER.info("OOZIE: Test Submit Workflow Start");

        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        conf.set("fs.default.name", "hdfs://127.0.0.1:" + configuration.getInt(Config.HDFS_NAMENODE_PORT_KEY));

        URI uri = URI.create ("hdfs://127.0.0.1:" + configuration.getInt(Config.HDFS_NAMENODE_PORT_KEY));

        FileSystem hdfsFs = FileSystem.get (uri, conf);

        OozieClient oozieClient = new OozieClient("http://" + configuration.getString(OozieBootstrap.OOZIE_HOST) + ":" + configuration.getInt(OozieBootstrap.OOZIE_PORT) + "/oozie");

        Path appPath = new Path(hdfsFs.getHomeDirectory(), "testApp");
        hdfsFs.mkdirs(new Path(appPath, "lib"));
        Path workflow = new Path(appPath, "workflow.xml");

        //write workflow.xml
        String wfApp = "<workflow-app xmlns='uri:oozie:workflow:0.1' name='test-wf'>" +
                "    <start to='end'/>" +
                "    <end name='end'/>" +
                "</workflow-app>";

        Writer writer = new OutputStreamWriter(hdfsFs.create(workflow));
        writer.write(wfApp);
        writer.close();

        //write job.properties
        Properties oozieConf = oozieClient.createConfiguration();
        oozieConf.setProperty(OozieClient.APP_PATH, workflow.toString());
        oozieConf.setProperty(OozieClient.USER_NAME, UserGroupInformation.getCurrentUser().getUserName());

        //submit and check
        final String jobId = oozieClient.submit(oozieConf);
        WorkflowJob wf = oozieClient.getJobInfo(jobId);
        Assert.assertNotNull(wf);
        assertEquals(WorkflowJob.Status.PREP, wf.getStatus());

        LOGGER.info("OOZIE: Workflow: {}", wf.toString());
        hdfsFs.close();

    }

    private static void createHbaseTable(String tableName, String colFamily,
                                         org.apache.hadoop.conf.Configuration configuration) throws Exception {

        final HBaseAdmin admin = new HBaseAdmin(configuration);
        HTableDescriptor hTableDescriptor = new HTableDescriptor(TableName.valueOf(tableName));
        HColumnDescriptor hColumnDescriptor = new HColumnDescriptor(colFamily);

        hTableDescriptor.addFamily(hColumnDescriptor);
        admin.createTable(hTableDescriptor);
    }

    private static void putRow(String tableName, String colFamName, String rowKey, String colQualifier, String value,
                               org.apache.hadoop.conf.Configuration configuration) throws Exception {
        HTable table = new HTable(configuration, tableName);
        Put put = new Put(Bytes.toBytes(rowKey));
        put.add(Bytes.toBytes(colFamName), Bytes.toBytes(colQualifier), Bytes.toBytes(value));
        table.put(put);
        table.flushCommits();
        table.close();
    }

    private static Result getRow(String tableName, String colFamName, String rowKey, String colQualifier,
                                 org.apache.hadoop.conf.Configuration configuration) throws Exception {
        Result result;
        HTable table = new HTable(configuration, tableName);
        Get get = new Get(Bytes.toBytes(rowKey));
        get.addColumn(Bytes.toBytes(colFamName), Bytes.toBytes(colQualifier));
        get.setMaxVersions(1);
        result = table.get(get);
        return result;
    }


}
