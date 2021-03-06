package org.shirdrn.storm.examples;

import java.util.Arrays;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.storm.hdfs.bolt.HdfsBolt;
import org.apache.storm.hdfs.bolt.format.DefaultFileNameFormat;
import org.apache.storm.hdfs.bolt.format.DelimitedRecordFormat;
import org.apache.storm.hdfs.bolt.format.FileNameFormat;
import org.apache.storm.hdfs.bolt.format.RecordFormat;
import org.apache.storm.hdfs.bolt.rotation.FileRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.TimedRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.TimedRotationPolicy.TimeUnit;
import org.apache.storm.hdfs.bolt.sync.CountSyncPolicy;
import org.apache.storm.hdfs.bolt.sync.SyncPolicy;

import storm.kafka.BrokerHosts;
import storm.kafka.KafkaSpout;
import storm.kafka.SpoutConfig;
import storm.kafka.StringScheme;
import storm.kafka.ZkHosts;
import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.spout.SchemeAsMultiScheme;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

/**
 * Topology transformation graph:
 * <pre>
 * +----------+       +------------------------+       +--------+
 * |KafkaSpout| ----> |KafkaWordToUpperCaseBolt| ----> |HdfsBolt|
 * +----------+       +------------------------+       +--------+
 * </pre>
 * 
 * @author yanjun
 */
@SuppressWarnings("rawtypes")
public class KafkaToStormToHDFSTopology {
	
	public static class KafkaWordToUpperCaseBolt extends BaseRichBolt {

		private static final Log LOG = LogFactory.getLog(KafkaWordToUpperCaseBolt.class);
		private static final long serialVersionUID = -7484537738096905817L;
		private OutputCollector collector;
		
		@Override
		public void prepare(Map stormConf, TopologyContext context,
				OutputCollector collector) {
			this.collector = collector;			
		}

		@Override
		public void execute(Tuple input) {
			String line = input.getString(0).trim();
			LOG.info("RECV[kafka -> splitter] " + line);
			if(!line.isEmpty()) {
				String upperLine = line.toUpperCase();
				LOG.info("EMIT[splitter -> counter] " + upperLine);
				collector.emit(input, new Values(upperLine, upperLine.length()));
			}
		}

		@Override
		public void declareOutputFields(OutputFieldsDeclarer declarer) {
			declarer.declare(new Fields("line", "len"));
		}
		
	}
	
	public static void main(String[] args) throws AlreadyAliveException, InvalidTopologyException, InterruptedException {
		// Configure Kafka
		String zks = "zk1:2181,zk2:2181,zk3:2181";
		String topic = "basis_common";
		String zkRoot = "/storm"; // default zookeeper root configuration for storm
		String id = "word";
		BrokerHosts brokerHosts = new ZkHosts(zks);
		SpoutConfig spoutConf = new SpoutConfig(brokerHosts, topic, zkRoot, id);
		spoutConf.scheme = new SchemeAsMultiScheme(new StringScheme());
		spoutConf.forceFromStart = true;
		spoutConf.zkServers = Arrays.asList(new String[] {"zk1", "zk2", "zk3"});
		spoutConf.zkPort = 2181;
		
		// Configure HDFS bolt
		RecordFormat format = new DelimitedRecordFormat()
		        .withFieldDelimiter("\t"); // use "\t" instead of "," for field delimiter
		SyncPolicy syncPolicy = new CountSyncPolicy(1000); // sync the filesystem after every 1k tuples
		FileRotationPolicy rotationPolicy = new TimedRotationPolicy(1.0f, TimeUnit.MINUTES); // rotate files
		FileNameFormat fileNameFormat = new DefaultFileNameFormat()
		        .withPath("/data/storm/").withPrefix("app_").withExtension(".log"); // set file name format
		HdfsBolt hdfsBolt = new HdfsBolt()
		        .withFsUrl("hdfs://hadoop6:8020")
		        .withFileNameFormat(fileNameFormat)
		        .withRecordFormat(format)
		        .withRotationPolicy(rotationPolicy)
		        .withSyncPolicy(syncPolicy);
		
		// configure & build topology
		TopologyBuilder builder = new TopologyBuilder();
		builder
			.setSpout("kafka-reader", new KafkaSpout(spoutConf), 5)
			.setNumTasks(5);
		builder
			.setBolt("to-upper", new KafkaWordToUpperCaseBolt(), 2)
			.setNumTasks(4)
			.shuffleGrouping("kafka-reader");
		builder
			.setBolt("hdfs-bolt", hdfsBolt, 2)
			.setNumTasks(4)
			.fieldsGrouping("to-upper", new Fields("line"));
		
		// submit topology
		Config conf = new Config();
		String name = KafkaToStormToHDFSTopology.class.getSimpleName();
		if (args != null && args.length > 0) {
			String nimbus = args[0];
			conf.put(Config.NIMBUS_HOST, nimbus);
			conf.setNumWorkers(2);
			StormSubmitter.submitTopologyWithProgressBar(name, conf, builder.createTopology());
		} else {
			conf.setMaxTaskParallelism(3);
			LocalCluster cluster = new LocalCluster();
			cluster.submitTopology(name, conf, builder.createTopology());
			Thread.sleep(600000);
			cluster.shutdown();
		}
	}

}
