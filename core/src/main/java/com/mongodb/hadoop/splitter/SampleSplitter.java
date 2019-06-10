package com.mongodb.hadoop.splitter;

import com.mongodb.AggregationOutput;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.CommandResult;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.mongodb.hadoop.util.MongoConfigUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * This Splitter calculates InputSplits using the $sample aggregation operator.
 *
 * Using $sample, we collect documents randomly in the input collection in
 * sorted order over an index. We choose every Nth sample to be the start/end
 * bounds of a split based on the estimated size of the resulting split in
 * bytes.
 */
public class SampleSplitter extends MongoCollectionSplitter {

    private static final Log LOG = LogFactory.getLog(SampleSplitter.class);

    public static final String SAMPLES_PER_SPLIT =
      "mongo.input.splits.samples_per_split";
    public static final int DEFAULT_SAMPLES_PER_SPLIT = 10;

    public SampleSplitter() {}

    public SampleSplitter(final Configuration conf) {
        super(conf);
    }

    @Override
    public List<InputSplit> calculateSplits() throws SplitFailedException {
        LOG.info("start calculate splits ...");
        Configuration conf = getConfiguration();
        //long splitSizeMB = MongoConfigUtil.getSplitSize(conf);
        //256MB一个分块
        long splitSizeMB = conf.getInt(MongoConfigUtil.INPUT_SPLIT_SIZE, 256);

        long samplesPerSplit = MongoConfigUtil.getSamplesPerSplit(conf);
        DBObject splitKey = MongoConfigUtil.getInputSplitKey(conf);
        DBCollection inputCollection = MongoConfigUtil.getInputCollection(conf);

        CommandResult result = inputCollection.getDB().command(
          new BasicDBObject("collstats", inputCollection.getName()));
        if (!result.ok()) {
            throw new SplitFailedException(
              "Could not execute command 'collstats': "
                + result.getErrorMessage());
        }
        int count = result.getInt("count");
        int avgObjSize = result.getInt("avgObjSize");
        int numDocsPerSplit = (int) Math.floor(splitSizeMB * 1024 * 1024 / avgObjSize);
        int numSplits = (int) Math.ceil((double) count / numDocsPerSplit);
        int totalSamples = (int) Math.floor(numSplits);

        if (count < numDocsPerSplit) {
            LOG.warn("Not enough documents for more than one split! Consider "
                + "setting " + MongoConfigUtil.INPUT_SPLIT_SIZE + " to a "
                + "lower value.");
            InputSplit split = createSplitFromBounds(null, null);
            return Collections.singletonList(split);
        }

        //最大分片数量
        int maxSplit = conf.getInt("mongo.input.max_split_sizes",400);

        totalSamples = Math.min(totalSamples,maxSplit);
        totalSamples = Math.max(totalSamples,1);

        DBObject[] pipeline = {
          new BasicDBObjectBuilder()
            .push("$sample").add("size", totalSamples).get(),
          //new BasicDBObject("$project", splitKey),
          new BasicDBObject("$sort", splitKey)
        };

        AggregationOutput aggregationOutput;
        try {
            aggregationOutput =
              inputCollection.aggregate(Arrays.asList(pipeline));
        } catch (MongoException e) {
            throw new SplitFailedException(
              "Failed to aggregate sample documents. Note that this Splitter "
                + "implementation is incompatible with MongoDB versions "
                + "prior to 3.2.", e);
        }

        BasicDBObject previousKey = null;
        List<InputSplit> splits = new ArrayList<InputSplit>(numSplits);
        int i = 0;
        BasicDBObject bdbo;
        for (DBObject sample : aggregationOutput.results()) {
            //if (i++ % samplesPerSplit == 0) {
                //BasicDBObject bdbo = (BasicDBObject) sample;
                bdbo = new BasicDBObject();
                bdbo.put("_id", sample.get("_id"));
                splits.add(createSplitFromBounds(previousKey, bdbo));
                previousKey = bdbo;
            //}
        }
        splits.add(createSplitFromBounds(previousKey, null));

        if (MongoConfigUtil.isFilterEmptySplitsEnabled(conf)) {
            return filterEmptySplits(splits);
        }

        LOG.info("end calculate splits ...");
        return splits;
    }
}
