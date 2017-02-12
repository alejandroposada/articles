package mrhints;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;

import mrhints.DenormalizeJobV3.JoinKey.ValueType;

public class DenormalizeJobV3 {

	private static final int SHARDS = 20;

	public static class JoinKey implements Writable {
		public long uid;
		public ValueType vtype;
		public int shard;

		public JoinKey() {
		}
		public JoinKey(long uid, ValueType vtype, int shard) {
			this.uid = uid;
			this.vtype = vtype;
			this.shard = shard;
		}

		public enum ValueType {
			USER, // 0
			SESSION // 1
		}

		@Override
		public void readFields(DataInput in) throws IOException {
			uid = in.readLong();
			vtype = ValueType.values()[in.readInt()];
			shard = in.readInt();
		}

		@Override
		public void write(DataOutput out) throws IOException {
			out.writeLong(uid);
			out.writeInt(vtype.ordinal());
			out.writeInt(shard);
		}
	}

	public static class MapperImpl extends Mapper<NullWritable, Any, JoinKey, Any> {
		@Override
		protected void map(NullWritable key, Any value, Mapper<NullWritable, Any, JoinKey, Any>.Context context)
		    throws IOException, InterruptedException {

			if (value.session != null) {
				int shard = (Arrays.hashCode(value.session.data) & 0x7fff_ffff) % SHARDS; // [0..SHARDS-1]
				context.write(new JoinKey(value.session.uid, ValueType.SESSION, shard), new Any(value.session));
        context.getCounter(InputRecordByType.Session).increment(1);
			}
			if (value.user != null) {
				for (int shard = 0; shard < SHARDS; shard++) {
					context.write(new JoinKey(value.user.uid, ValueType.USER, shard), new Any(value.user));
				}
				context.getCounter(InputRecordByType.User).increment(1);
			}
		}
	}

	public static class PartitionerImpl extends Partitioner<JoinKey, Any> {

		@Override
		public int getPartition(JoinKey key, Any any, int numPartitions) {
      int hash = 1;
      hash = hash * 13 + (int)(key.uid & 0xffff_ffff);
      hash = hash * 13 + key.shard;
			return (hash & 0x7fff_ffff) % numPartitions;
		}
	}

	public static class SortComparatorImpl implements RawComparator<JoinKey> {

		@Override
		public int compare(JoinKey key1, JoinKey key2) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int compare(byte[] buf1, int off1, int len1,
						           byte[] buf2, int off2, int len2) {

			JoinKey key1 = Utils.readObject(new JoinKey(), buf1, off1, len1);
			JoinKey key2 = Utils.readObject(new JoinKey(), buf2, off2, len2);

			return Utils.compareMultilevel(
					      Utils.compareLongs(key1.uid, key2.uid),
					      Utils.compareInts(key1.shard, key2.shard),
					      Utils.compareInts(key1.vtype.ordinal(), key2.vtype.ordinal())
			       );
		}
	}

	public static class GroupingComparatorImpl implements RawComparator<JoinKey> {

		@Override
		public int compare(JoinKey key1, JoinKey key2) {
			throw new UnsupportedOperationException();
		}


		@Override
		public int compare(byte[] buf1, int off1, int len1,
						           byte[] buf2, int off2, int len2) {

			JoinKey key1 = Utils.readObject(new JoinKey(), buf1, off1, len1);
			JoinKey key2 = Utils.readObject(new JoinKey(), buf2, off2, len2);

			return Utils.compareMultilevel(
					      Utils.compareLongs(key1.uid, key2.uid),
					      Utils.compareInts(key1.shard, key2.shard)
			       );
		}
	}

	public static class ReducerImpl extends Reducer<JoinKey, Any, NullWritable, Any> {

		@Override
		protected void reduce(JoinKey key, Iterable<Any> values, Reducer<JoinKey, Any, NullWritable, Any>.Context context)
				throws IOException, InterruptedException {

			User user = null;
			boolean foundSession = false;
			for (Any value : values) {
				// the very first record should be _user_
				if (value.user != null) {
					if (user == null) {
						user = new User(value.user);
					} else {
						if (foundSession) {
							throw new RuntimeException("User is out of order with session list");
						}
						// ignore duplicate user
						context.getCounter(JoinStatus.DuplicateUser).increment(1);
					}
				}

				// all further records should be _sessions_
				if (value.session != null) {
					foundSession = true;
					if (user != null) {
						context.write(NullWritable.get(), new Any(value.session, user));
					} else {
						// ignore session without corresponding user
            context.getCounter(JoinStatus.SessionsWithoutUser).increment(1);
					}
				}
			}
		}
	}

	public void run() throws Exception {
		Job job = Job.getInstance();
		job.setJobName(this.getClass().getSimpleName());
		job.setJarByClass(this.getClass());

		job.setInputFormatClass(SequenceFileInputFormat.class);
		SequenceFileInputFormat.addInputPath(job, new Path("/sessions"));
		SequenceFileInputFormat.addInputPath(job, new Path("/users"));
		job.setMapperClass(MapperImpl.class);
		job.setMapOutputKeyClass(JoinKey.class);
		job.setMapOutputValueClass(Any.class);
		job.setPartitionerClass(PartitionerImpl.class);
		job.setSortComparatorClass(SortComparatorImpl.class);
		job.setGroupingComparatorClass(GroupingComparatorImpl.class);

		job.setReducerClass(ReducerImpl.class);
		job.setOutputKeyClass(NullWritable.class);
		job.setOutputValueClass(Any.class);
		job.setOutputFormatClass(SequenceFileOutputFormat.class);
		SequenceFileOutputFormat.setOutputPath(job, new Path("/enriched_sessions"));
		job.setNumReduceTasks(10);

		if (!job.waitForCompletion(true)) {
			throw new Exception();
		}
	}

	public static void main(String args[]) throws Exception {
		new DenormalizeJobV3().run();
	}
}
