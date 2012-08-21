// Copyright (c) 2012 Cloudera, Inc. All rights reserved.

package com.cloudera.impala.planner;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.common.NotImplementedException;
import com.cloudera.impala.service.Frontend;
import com.cloudera.impala.testutil.TestFileParser;
import com.cloudera.impala.testutil.TestFileParser.Section;
import com.cloudera.impala.testutil.TestFileParser.TestCase;
import com.cloudera.impala.testutil.TestUtils;
import com.cloudera.impala.thrift.Constants;
import com.cloudera.impala.thrift.TClientRequest;
import com.cloudera.impala.thrift.THBaseKeyRange;
import com.cloudera.impala.thrift.THdfsFileSplit2;
import com.cloudera.impala.thrift.TQueryExecRequest2;
import com.cloudera.impala.thrift.TQueryOptions;
import com.cloudera.impala.thrift.TScanRangeLocation;
import com.cloudera.impala.thrift.TScanRangeLocations;
import com.google.common.collect.Lists;

public class NewPlannerTest {
  private final static Logger LOG = LoggerFactory.getLogger(NewPlannerTest.class);
  private final static boolean GENERATE_OUTPUT_FILE = true;

  private static Frontend frontend;
  private final String testDir = "functional-newplanner/queries/PlannerTest";
  private final String outDir = "/tmp/NewPlannerTest/";

  @BeforeClass
  public static void setUp() throws Exception {
    frontend = new Frontend(true);
  }

  @AfterClass
  public static void cleanUp() {
  }

  private StringBuilder PrintScanRangeLocations(TQueryExecRequest2 execRequest) {
    StringBuilder result = new StringBuilder();
    for (Map.Entry<Integer, List<TScanRangeLocations>> entry:
        execRequest.per_node_scan_ranges.entrySet()) {
      result.append("NODE " + entry.getKey().toString() + ":\n");
      if (entry.getValue() == null) {
        continue;
      }

      for (TScanRangeLocations locations: entry.getValue()) {
        // print scan range
        result.append("  ");
        if (locations.scan_range.isSetHdfsFileSplit()) {
          THdfsFileSplit2 split = locations.scan_range.getHdfsFileSplit();
          result.append("HDFS SPLIT " + split.path + " "
              + Long.toString(split.offset) + ":" + Long.toString(split.length));
        }
        if (locations.scan_range.isSetHbaseKeyRange()) {
          THBaseKeyRange keyRange = locations.scan_range.getHbaseKeyRange();
          result.append("HBASE KEYRANGE ");
          if (keyRange.isSetStartKey()) {
            result.append(HBaseScanNode.printKey(keyRange.getStartKey().getBytes()));
          } else {
            result.append("<unbounded>");
          }
          result.append(":");
          if (keyRange.isSetStopKey()) {
            result.append(HBaseScanNode.printKey(keyRange.getStopKey().getBytes()));
          } else {
            result.append("<unbounded>");
          }
        }
        result.append("\n");

        // print locations
        result.append("  LOCATIONS: ");
        for (TScanRangeLocation location: locations.locations) {
          result.append(location.server.hostname + ":"
              + Integer.toString(location.server.port));
          if (location.isSetVolume_id()) {
            result.append("/" + Integer.toString(location.volume_id));
          }
          result.append(" ");
        }
        result.append("\n");
      }
    }
    return result;
  }

  /**
   * Produces single-node and distributed plans for testCase and compares
   * plan and scan range results.
   * Appends the actual single-node and distributed plan as well as the printed
   * scan ranges to actualOutput, along with the requisite section header.
   * locations to actualScanRangeLocations; compares both to the appropriate sections
   * of 'testCase'.
   */
  private void RunTestCase(
      TestCase testCase, StringBuilder errorLog, StringBuilder actualOutput) {
    String query = testCase.getQuery();
    LOG.info("running query " + query);
    TQueryOptions options = new TQueryOptions();

    // single-node plan
    ArrayList<String> expectedPlan = testCase.getSectionContents(Section.PLAN);
    boolean isImplemented = expectedPlan.size() > 0
        && !expectedPlan.get(0).toLowerCase().startsWith("not implemented");
    options.setNum_nodes(1);
    TClientRequest request = new TClientRequest(query, options);
    StringBuilder explainBuilder = new StringBuilder();

    TQueryExecRequest2 execRequest = null;
    String locationsStr = null;
    actualOutput.append(Section.PLAN.getHeader() + "\n");
    try {
      execRequest = frontend.createQueryExecRequest2(request, explainBuilder);
      String explainStr = explainBuilder.toString();
      actualOutput.append(explainStr);
      if (!isImplemented) {
        errorLog.append(
            "query produced PLAN\nquery=" + query + "\nplan=\n" + explainStr);
      } else {
        LOG.info("single-node plan: " + explainStr);
        String result = TestUtils.compareOutput(
            Lists.newArrayList(explainStr.split("\n")), expectedPlan, true);
        if (!result.isEmpty()) {
          errorLog.append("section " + Section.PLAN.toString() + " of query:\n" + query
              + "\n" + result);
        }
        locationsStr = PrintScanRangeLocations(execRequest).toString();
      }
    } catch (AnalysisException e) {
      errorLog.append("query:\n" + query + "\nanalysis error: " + e.getMessage() + "\n");
      return;
    } catch (InternalException e) {
      errorLog.append("query:\n" + query + "\ninternal error: " + e.getMessage() + "\n");
      return;
    } catch (NotImplementedException e) {
      actualOutput.append("not implemented\n");
      if (isImplemented) {
        errorLog.append("query:\n" + query + "\nPLAN not implemented: "
            + e.getMessage() + "\n");
      }
    }
    if (!isImplemented) {
      // nothing else to compare
      return;
    }

    expectedPlan = testCase.getSectionContents(Section.DISTRIBUTEDPLAN);
    isImplemented = expectedPlan.size() > 0
        && !expectedPlan.get(0).toLowerCase().startsWith("not implemented");
    options.setNum_nodes(Constants.NUM_NODES_ALL);
    explainBuilder = new StringBuilder();
    actualOutput.append(Section.DISTRIBUTEDPLAN.getHeader() + "\n");
    try {
      // distributed plan
      execRequest = frontend.createQueryExecRequest2(request, explainBuilder);
      String explainStr = explainBuilder.toString();
      actualOutput.append(explainStr);
      if (!isImplemented) {
        errorLog.append(
            "query produced DISTRIBUTEDPLAN\nquery=" + query + "\nplan=\n" + explainStr);
      } else {
        LOG.info("distributed plan: " + explainStr);
        String result = TestUtils.compareOutput(
            Lists.newArrayList(explainStr.split("\n")), expectedPlan, true);
        if (!result.isEmpty()) {
          errorLog.append("section " + Section.DISTRIBUTEDPLAN.toString()
              + " of query:\n" + query + "\n" + result);
        }
      }
    } catch (AnalysisException e) {
      errorLog.append("query:\n" + query + "\nanalysis error: " + e.getMessage() + "\n");
      return;
    } catch (InternalException e) {
      errorLog.append("query:\n" + query + "\ninternal error: " + e.getMessage() + "\n");
      return;
    } catch (NotImplementedException e) {
      actualOutput.append("not implemented\n");
      if (isImplemented) {
        errorLog.append("query:\n" + query + "\nDISTRIBUTEDPLAN not implemented: "
            + e.getMessage() + "\n");
      }
    }


    // compare scan range locations
    LOG.info("scan range locations: " + locationsStr);
    ArrayList<String> expectedLocations =
        testCase.getSectionContents(Section.SCANRANGELOCATIONS);
    String result = TestUtils.compareOutput(
        Lists.newArrayList(locationsStr.split("\n")), expectedLocations, true);
    if (!result.isEmpty()) {
      errorLog.append("section " + Section.SCANRANGELOCATIONS + " of query:\n"
          + query + "\n" + result);
    }
    actualOutput.append(Section.SCANRANGELOCATIONS.getHeader() + "\n");
    actualOutput.append(locationsStr);

      // TODO: check that scan range locations are identical in both cases
  }

  private void runPlannerTestFile(String testFile) {
    String fileName = testDir + "/" + testFile + ".test";
    TestFileParser queryFileParser = new TestFileParser(fileName);
    StringBuilder actualOutput = new StringBuilder();

    queryFileParser.parseFile();
    StringBuilder errorLog = new StringBuilder();
    for (TestCase testCase : queryFileParser.getTestCases()) {
      actualOutput.append(testCase.getSectionAsString(Section.QUERY, true, "\n"));
      actualOutput.append("\n");
      RunTestCase(testCase, errorLog, actualOutput);
      actualOutput.append("====\n");
    }

    // Create the actual output file
    if (GENERATE_OUTPUT_FILE) {
      try {
        File outDirFile = new File(outDir);
        outDirFile.mkdirs();
        FileWriter fw = new FileWriter(outDir + testFile + ".test");
        fw.write(actualOutput.toString());
        fw.close();
      } catch (IOException e) {
        errorLog.append("Unable to create output file: " + e.getMessage());
      }
    }

    if (errorLog.length() != 0) {
      fail(errorLog.toString());
    }
  }

  @Test
  public void testDistinct() {
    runPlannerTestFile("distinct");
  }

  @Test
  public void testAggregation() {
    runPlannerTestFile("aggregation");
  }

  /*
  @Test
  public void testHbase() {
    runPlannerTestFile("hbase");
  }

  @Test
  public void testInsert() {
    runPlannerTestFile("insert");
  }
  */

  @Test
  public void testHdfs() {
    runPlannerTestFile("hdfs");
  }

  @Test
  public void testJoins() {
    runPlannerTestFile("joins");
  }

  @Test
  public void testOrder() {
    runPlannerTestFile("order");
  }

  @Test
  public void testTopN() {
    runPlannerTestFile("topn");
  }

  @Test
  public void testSubquery() {
    runPlannerTestFile("subquery");
  }

  @Test
  public void testUnion() {
    runPlannerTestFile("union");
  }

  @Test
  public void testTpch() {
    // TODO: Q20-Q22 are disabled due to IMP-137. Once that bug is resolved they should
    // be re-enabled.
    runPlannerTestFile("tpch-all");
  }
}
