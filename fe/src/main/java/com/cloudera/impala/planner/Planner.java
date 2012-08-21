
// Copyright (c) 2011 Cloudera, Inc. All rights reserved.

package com.cloudera.impala.planner;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.AggregateInfo;
import com.cloudera.impala.analysis.AnalysisContext;
import com.cloudera.impala.analysis.Analyzer;
import com.cloudera.impala.analysis.BaseTableRef;
import com.cloudera.impala.analysis.BinaryPredicate;
import com.cloudera.impala.analysis.DescriptorTable;
import com.cloudera.impala.analysis.Expr;
import com.cloudera.impala.analysis.InlineViewRef;
import com.cloudera.impala.analysis.InsertStmt;
import com.cloudera.impala.analysis.Predicate;
import com.cloudera.impala.analysis.QueryStmt;
import com.cloudera.impala.analysis.SelectStmt;
import com.cloudera.impala.analysis.SlotDescriptor;
import com.cloudera.impala.analysis.SlotId;
import com.cloudera.impala.analysis.SortInfo;
import com.cloudera.impala.analysis.TableRef;
import com.cloudera.impala.analysis.TupleDescriptor;
import com.cloudera.impala.analysis.TupleId;
import com.cloudera.impala.analysis.UnionStmt;
import com.cloudera.impala.analysis.UnionStmt.Qualifier;
import com.cloudera.impala.analysis.UnionStmt.UnionOperand;
import com.cloudera.impala.catalog.HdfsTable;
import com.cloudera.impala.catalog.PrimitiveType;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.IdGenerator;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.common.NotImplementedException;
import com.cloudera.impala.common.Pair;
import com.cloudera.impala.common.Reference;
import com.cloudera.impala.thrift.Constants;
import com.cloudera.impala.thrift.TExplainLevel;
import com.cloudera.impala.thrift.TFinalizeParams;
import com.cloudera.impala.thrift.THBaseKeyRange;
import com.cloudera.impala.thrift.THdfsFileSplit;
import com.cloudera.impala.thrift.THostPort;
import com.cloudera.impala.thrift.TPlanExecParams;
import com.cloudera.impala.thrift.TPlanExecRequest;
import com.cloudera.impala.thrift.TQueryExecRequest;
import com.cloudera.impala.thrift.TQueryGlobals;
import com.cloudera.impala.thrift.TQueryOptions;
import com.cloudera.impala.thrift.TScanRange;
import com.cloudera.impala.thrift.TUniqueId;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

/**
 * The planner is responsible for turning parse trees into plan fragments that
 * can be shipped off to backends for execution.
 *
 */
public class Planner {
  private final static Logger LOG = LoggerFactory.getLogger(Planner.class);

  // For generating a string of the current time.
  private final SimpleDateFormat formatter =
      new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");

  // counter to assign sequential node ids
  private final IdGenerator<PlanNodeId> nodeIdGenerator = new IdGenerator<PlanNodeId>();

  // Control how much info explain plan outputs
  private TExplainLevel explainLevel = TExplainLevel.NORMAL;

  /**
   * Sets how much details the explain plan the planner will generate.
   * @param level
   */
  public void setExplainLevel(TExplainLevel level) {
    explainLevel = level;
  }

  /**
   * Transform '=', '<[=]' and '>[=]' comparisons for given slot into
   * ValueRange. Also removes those predicates which were used for the construction
   * of ValueRange from 'conjuncts'. Only looks at comparisons w/ constants
   * (ie, the bounds of the result can be evaluated with Expr::GetValue(NULL)).
   * If there are multiple competing comparison predicates that could be used
   * to construct a ValueRange, only the first one from each category is chosen.
   */
  private ValueRange createScanRange(SlotDescriptor d, List<Predicate> conjuncts) {
    ListIterator<Predicate> i = conjuncts.listIterator();
    ValueRange result = null;
    while (i.hasNext()) {
      Predicate p = i.next();
      if (!(p instanceof BinaryPredicate)) {
        continue;
      }
      BinaryPredicate comp = (BinaryPredicate) p;
      if (comp.getOp() == BinaryPredicate.Operator.NE) {
        continue;
      }
      Expr slotBinding = comp.getSlotBinding(d.getId());
      if (slotBinding == null || !slotBinding.isConstant()) {
        continue;
      }

      if (comp.getOp() == BinaryPredicate.Operator.EQ) {
        i.remove();
        return ValueRange.createEqRange(slotBinding);
      }

      if (result == null) {
        result = new ValueRange();
      }

      // TODO: do we need copies here?
      if (comp.getOp() == BinaryPredicate.Operator.GT
          || comp.getOp() == BinaryPredicate.Operator.GE) {
        if (result.lowerBound == null) {
          result.lowerBound = slotBinding;
          result.lowerBoundInclusive = (comp.getOp() == BinaryPredicate.Operator.GE);
          i.remove();
        }
      } else {
        if (result.upperBound == null) {
          result.upperBound = slotBinding;
          result.upperBoundInclusive = (comp.getOp() == BinaryPredicate.Operator.LE);
          i.remove();
        }
      }
    }
    return result;
  }

  /**
   * Create a tree of nodes for the inline view ref
   * @param analyzer
   * @param inlineViewRef the inline view ref
   * @return a tree of nodes for the inline view
   */
  private PlanNode createInlineViewPlan(Analyzer analyzer, InlineViewRef inlineViewRef)
      throws NotImplementedException, InternalException {
    // Get the list of fully bound predicates
    List<Predicate> boundConjuncts =
        analyzer.getBoundConjuncts(inlineViewRef.getMaterializedTupleIds());

    // TODO (alan): this is incorrect for left outer join. Predicate should not be
    // evaluated after the join.

    if (inlineViewRef.getViewStmt() instanceof UnionStmt) {
      // Register predicates with the inline view's analyzer
      // such that the topmost merge node evaluates them.
      inlineViewRef.getAnalyzer().registerConjuncts(boundConjuncts);
      analyzer.markConjunctsAssigned(boundConjuncts);
      return createUnionPlan((UnionStmt) inlineViewRef.getViewStmt(),
          inlineViewRef.getAnalyzer());
    }

    Preconditions.checkState(inlineViewRef.getViewStmt() instanceof SelectStmt);
    SelectStmt selectStmt = (SelectStmt) inlineViewRef.getViewStmt();

    // If the view select statement does not compute aggregates, predicates are
    // registered with the inline view's analyzer for predicate pushdown.
    if (selectStmt.getAggInfo() == null) {
      inlineViewRef.getAnalyzer().registerConjuncts(boundConjuncts);
      analyzer.markConjunctsAssigned(boundConjuncts);
    }

    // Create a tree of plan node for the inline view, using inline view's analyzer.
    PlanNode result = createSelectPlan(selectStmt, inlineViewRef.getAnalyzer());

    // If the view select statement has aggregates, predicates aren't pushed into the
    // inline view. The predicates have to be evaluated at the root of the plan node
    // for the inline view (which should be an aggregate node).
    if (selectStmt.getAggInfo() != null) {
      result.getConjuncts().addAll(boundConjuncts);
      analyzer.markConjunctsAssigned(boundConjuncts);
    }

    return result;
  }

  /**
   * Create node for scanning all data files of a particular table.
   * @param analyzer
   * @param tblRef
   * @return a scan node
   * @throws NotImplementedException
   */
  private PlanNode createScanNode(Analyzer analyzer, TableRef tblRef) {
    ScanNode scanNode = null;

    if (tblRef.getTable() instanceof HdfsTable) {
      scanNode = new HdfsScanNode(
          new PlanNodeId(nodeIdGenerator), tblRef.getDesc(),
          (HdfsTable)tblRef.getTable());
    } else {
      // HBase table
      scanNode = new HBaseScanNode(new PlanNodeId(nodeIdGenerator), tblRef.getDesc());
    }

    // TODO (alan): this is incorrect for left outer joins. Predicate should not be
    // evaluated after the join.

    List<Predicate> conjuncts = analyzer.getBoundConjuncts(tblRef.getId().asList());
    analyzer.markConjunctsAssigned(conjuncts);
    ArrayList<ValueRange> keyRanges = Lists.newArrayList();
    boolean addedRange = false;  // added non-null range
    // determine scan predicates for clustering cols
    for (int i = 0; i < tblRef.getTable().getNumClusteringCols(); ++i) {
      SlotDescriptor slotDesc =
          analyzer.getColumnSlot(tblRef.getDesc(),
                                 tblRef.getTable().getColumns().get(i));
      if (slotDesc == null
          || (scanNode instanceof HBaseScanNode
              && slotDesc.getType() != PrimitiveType.STRING)) {
        // clustering col not referenced in this query;
        // or: the hbase row key is mapped to a non-string type
        // (since it's stored in ascii it will be lexicographically ordered,
        // and non-string comparisons won't work)
        keyRanges.add(null);
      } else {
        ValueRange keyRange = createScanRange(slotDesc, conjuncts);
        keyRanges.add(keyRange);
        addedRange = true;
      }
    }

    if (addedRange) {
      scanNode.setKeyRanges(keyRanges);
    }
    scanNode.setConjuncts(conjuncts);

    return scanNode;
  }

  /**
   * Return join conjuncts that can be used for hash table lookups.
   * - for inner joins, those are equi-join predicates in which one side is fully bound
   *   by lhsIds and the other by rhs' id;
   * - for outer joins: same type of conjuncts as inner joins, but only from the JOIN
   *   clause
   * Returns the conjuncts in 'joinConjuncts' (in which "<lhs> = <rhs>" is returned
   * as Pair(<lhs>, <rhs>)) and also in their original form in 'joinPredicates'.
   */
  private void getHashLookupJoinConjuncts(
      Analyzer analyzer,
      List<TupleId> lhsIds, TableRef rhs,
      List<Pair<Expr, Expr>> joinConjuncts,
      List<Predicate> joinPredicates) {
    joinConjuncts.clear();
    joinPredicates.clear();
    TupleId rhsId = rhs.getId();
    List<TupleId> rhsIds = rhs.getMaterializedTupleIds();
    List<Predicate> candidates;
    if (rhs.getJoinOp().isOuterJoin()) {
      // TODO: create test for this
      Preconditions.checkState(rhs.getOnClause() != null);
      candidates = rhs.getEqJoinConjuncts();
      Preconditions.checkState(candidates != null);
    } else {
      candidates = analyzer.getEqJoinPredicates(rhsId);
    }
    if (candidates == null) {
      return;
    }
    for (Predicate p: candidates) {
      Expr rhsExpr = null;
      if (p.getChild(0).isBound(rhsIds)) {
        rhsExpr = p.getChild(0);
      } else {
        Preconditions.checkState(p.getChild(1).isBound(rhsIds));
        rhsExpr = p.getChild(1);
      }

      Expr lhsExpr = null;
      if (p.getChild(0).isBound(lhsIds)) {
        lhsExpr = p.getChild(0);
      } else if (p.getChild(1).isBound(lhsIds)) {
        lhsExpr = p.getChild(1);
      } else {
        // not an equi-join condition between lhsIds and rhsId
        continue;
      }

      Preconditions.checkState(lhsExpr != rhsExpr);
      joinPredicates.add(p);
      Pair<Expr, Expr> entry = Pair.create(lhsExpr, rhsExpr);
      joinConjuncts.add(entry);
    }
  }

  /**
   * Create HashJoinNode to join outer with inner.
   */
  private PlanNode createHashJoinNode(
      Analyzer analyzer, PlanNode outer, TableRef innerRef)
      throws NotImplementedException, InternalException {
    // the rows coming from the build node only need to have space for the tuple
    // materialized by that node
    PlanNode inner = createTableRefNode(analyzer, innerRef);
    inner.rowTupleIds = Lists.newArrayList(innerRef.getMaterializedTupleIds());

    List<Pair<Expr, Expr>> eqJoinConjuncts = Lists.newArrayList();
    List<Predicate> eqJoinPredicates = Lists.newArrayList();
    getHashLookupJoinConjuncts(
        analyzer, outer.getTupleIds(), innerRef, eqJoinConjuncts, eqJoinPredicates);
    if (eqJoinPredicates.isEmpty()) {
      throw new NotImplementedException(
          "Join requires at least one equality predicate between the two tables.");
    }
    HashJoinNode result =
        new HashJoinNode(new PlanNodeId(nodeIdGenerator), outer, inner,
                         innerRef.getJoinOp(), eqJoinConjuncts,
                         innerRef.getOtherJoinConjuncts());
    analyzer.markConjunctsAssigned(eqJoinPredicates);

    // The remaining conjuncts that are bound by result.getTupleIds()
    // need to be evaluated explicitly by the hash join.
    ArrayList<Predicate> conjuncts =
      new ArrayList<Predicate>(analyzer.getBoundConjuncts(result.getTupleIds()));
    result.setConjuncts(conjuncts);
    analyzer.markConjunctsAssigned(conjuncts);
    return result;
  }

  /**
   * Mark slots that are being referenced by any conjuncts, order-by exprs, or select list
   * exprs as materialized. All aggregate slots are materialized.
   */
  private void markRefdSlots(PlanNode root, QueryStmt queryStmt, Analyzer analyzer) {
    Preconditions.checkArgument(root != null);
    List<SlotId> refdIdList = Lists.newArrayList();
    root.getMaterializedIds(refdIdList);

    Expr.getIds(queryStmt.getResultExprs(), null, refdIdList);

    HashSet<SlotId> refdIds = Sets.newHashSet();
    refdIds.addAll(refdIdList);
    for (TupleDescriptor tupleDesc: analyzer.getDescTbl().getTupleDescs()) {
      for (SlotDescriptor slotDesc: tupleDesc.getSlots()) {
        if (refdIds.contains(slotDesc.getId())) {
          slotDesc.setIsMaterialized(true);
        }
      }
    }
  }

  /**
   * Create a tree of PlanNodes for the given tblRef, which can be a BaseTableRef or a
   * InlineViewRef
   * @param analyzer
   * @param tblRef
   */
  private PlanNode createTableRefNode(Analyzer analyzer, TableRef tblRef)
      throws NotImplementedException, InternalException {
    if (tblRef instanceof BaseTableRef) {
      return createScanNode(analyzer, tblRef);
    }
    if (tblRef instanceof InlineViewRef) {
      return createInlineViewPlan(analyzer, (InlineViewRef)tblRef);
    }
    throw new NotImplementedException("unknown Table Ref Node");
  }

  /**
   * Create tree of PlanNodes that implements the Select/Project/Join part of the
   * given selectStmt.
   * Also calls DescriptorTable.computeMemLayout().
   * @param selectStmt
   * @param analyzer
   * @return root node of plan tree * @throws NotImplementedException if selectStmt
   * contains Order By clause
   */
  private PlanNode createSpjPlan(SelectStmt selectStmt, Analyzer analyzer)
      throws NotImplementedException, InternalException {
    if (selectStmt.getTableRefs().isEmpty()) {
      // no from clause -> nothing to plan
      return null;
    }
    // collect ids of tuples materialized by the subtree that includes all joins
    // and scans
    ArrayList<TupleId> rowTuples = Lists.newArrayList();
    for (TableRef tblRef: selectStmt.getTableRefs()) {
      rowTuples.addAll(tblRef.getMaterializedTupleIds());
    }

    // create left-deep sequence of binary hash joins; assign node ids as we go along
    TableRef tblRef = selectStmt.getTableRefs().get(0);
    PlanNode root = createTableRefNode(analyzer, tblRef);
    root.rowTupleIds = rowTuples;
    for (int i = 1; i < selectStmt.getTableRefs().size(); ++i) {
      TableRef innerRef = selectStmt.getTableRefs().get(i);
      root = createHashJoinNode(analyzer, root, innerRef);
      root.rowTupleIds = rowTuples;
      // Have the build side of a join copy data to a compact representation
      // in the tuple buffer.
      root.getChildren().get(1).setCompactData(true);
    }

    if (selectStmt.getSortInfo() != null && selectStmt.getLimit() == -1) {
      // TODO: only use topN if the memory footprint is expected to be low
      // how to account for strings???
      throw new NotImplementedException(
          "ORDER BY without LIMIT currently not supported");
    }

    return root;
  }

  /**
   * Create tree of PlanNodes that implements the Select/Project/Join/Group by/Having
   * of the selectStmt query block.
   */
  private PlanNode createSelectPlan(SelectStmt selectStmt, Analyzer analyzer)
      throws NotImplementedException, InternalException {
    PlanNode result = createSpjPlan(selectStmt, analyzer);
    if (result == null) {
      // No FROM clause.
      return null;
    }
    // add aggregation, if required
    AggregateInfo aggInfo = selectStmt.getAggInfo();
    if (aggInfo != null) {
      result = new AggregationNode(new PlanNodeId(nodeIdGenerator), result, aggInfo);
      // if we're computing DISTINCT agg fns, the analyzer already created the
      // 2nd phase agginfo
      if (aggInfo.isDistinctAgg()) {
        result = new AggregationNode(
            new PlanNodeId(nodeIdGenerator), result,
            aggInfo.getSecondPhaseDistinctAggInfo());
      }
    }
    // add having clause, if required
    if (selectStmt.getHavingPred() != null) {
      Preconditions.checkState(result instanceof AggregationNode);
      result.setConjuncts(selectStmt.getHavingPred().getConjuncts());
      analyzer.markConjunctsAssigned(selectStmt.getHavingPred().getConjuncts());
    }

    // add order by and limit
    SortInfo sortInfo = selectStmt.getSortInfo();
    if (sortInfo != null) {
      Preconditions.checkState(selectStmt.getLimit() != -1);
      result = new SortNode(new PlanNodeId(nodeIdGenerator), result, sortInfo, true);
    }
    result.setLimit(selectStmt.getLimit());

    // All the conjuncts in the inline view analyzer should be assigned
    Preconditions.checkState(!analyzer.hasUnassignedConjuncts());

    return result;
  }

  private ScanNode getLeftmostScan(PlanNode root) {
    if (root instanceof ScanNode) {
      return (ScanNode) root;
    }
    if (root.getChildren().isEmpty()) {
      return null;
    }
    return getLeftmostScan(root.getChildren().get(0));
  }

  /**
   * Return the execution parameter explain string for the given plan fragment index
   * @param request
   * @param planFragIdx
   * @return
   */
  private String getExecParamExplainString(TQueryExecRequest request, int planFragIdx) {
    StringBuilder execParamExplain = new StringBuilder();
    String prefix = "  ";

    List<TPlanExecParams> execHostsParams =
        request.getNode_request_params().get(planFragIdx);

    for (int nodeIdx = 0; nodeIdx < execHostsParams.size(); nodeIdx++) {
      // If the host has no parameter set, don't print anything
      TPlanExecParams hostExecParams = execHostsParams.get(nodeIdx);
      if (hostExecParams == null || !hostExecParams.isSetScan_ranges()) {
        continue;
      }

      if (planFragIdx == 0) {
        // plan fragment 0 is the coordinator
        execParamExplain.append(prefix + "  HOST: coordinator\n");
      } else {
        THostPort hostport =
            request.getData_locations().get(planFragIdx - 1).get(nodeIdx);
        String hostnode = hostport.getIpaddress() + ":" + hostport.getPort();
        execParamExplain.append(prefix + "  HOST: " + hostnode + "\n");
      }

      for (TScanRange scanRange: hostExecParams.getScan_ranges()) {
        int nodeId = scanRange.getNodeId();
        if (scanRange.isSetHbaseKeyRanges()) {
          // HBase scan ranges are sorted and are printed as "startKey:stopKey"
          execParamExplain.append(
              prefix + "    HBASE KEY RANGES NODE ID: " + nodeId + "\n");
          List<THBaseKeyRange> keyRanges =
              Ordering.natural().sortedCopy(scanRange.getHbaseKeyRanges());
          for (THBaseKeyRange kr: keyRanges) {
            execParamExplain.append(prefix + "      ");
            if (kr.isSetStartKey()) {
              execParamExplain.append(
                  HBaseScanNode.printKey(kr.getStartKey().getBytes()));
            } else {
              execParamExplain.append("<unbounded>");
            }
            execParamExplain.append(":");
            if (kr.isSetStopKey()) {
              execParamExplain.append(HBaseScanNode.printKey(kr.getStopKey().getBytes()));
            } else {
              execParamExplain.append("<unbounded>");
            }
            execParamExplain.append("\n");
          }
        } else if (scanRange.isSetHdfsFileSplits()) {
          // HDFS splits are sorted and are printed as "<path> <offset>:<length>"
          execParamExplain.append(prefix + "    HDFS SPLITS NODE ID: " + nodeId);
          if (explainLevel == TExplainLevel.VERBOSE) {
            execParamExplain.append("\n");
          }
          List<THdfsFileSplit> fileSplists =
              Ordering.natural().sortedCopy(scanRange.getHdfsFileSplits());
          long totalLength = 0;
          // print per-split details for high explain level
          for (THdfsFileSplit fs: fileSplists) {
            if (explainLevel == TExplainLevel.VERBOSE) {
              execParamExplain.append(prefix + "      ");
              execParamExplain.append(fs.getPath() + " ");
              execParamExplain.append(fs.getOffset() + ":");
              execParamExplain.append(fs.getLength() + "\n");
            }
            totalLength += fs.getLength();
          }

          // print summary for normal explain level
          if (explainLevel == TExplainLevel.NORMAL) {
            execParamExplain.append(" TOTAL SIZE: " + totalLength + "\n");
          }
        }
      }
    }
    if (execParamExplain.length() > 0) {
      execParamExplain.insert(0, "\n" + prefix + "EXEC PARAMS\n");
    }

    return execParamExplain.toString();
  }

  /**
   * Build an explain plan string for plan fragments and execution parameters.
   * @param explainString output parameter that contains the explain plan string
   * @param planFragments
   * @param dataSinks
   * @param request
   */
  private void buildExplainString(
      StringBuilder explainStr, List<PlanNode> planFragments,
      List<DataSink> dataSinks, TQueryExecRequest request) {
    Preconditions.checkState(planFragments.size() == dataSinks.size());

    if (!request.has_coordinator_fragment) {
      explainStr.append("NO COORDINATOR FRAGMENT\n");
    }
    for (int planFragIdx = 0; planFragIdx < planFragments.size(); ++planFragIdx) {
      // An extra line after each plan fragment
      if (planFragIdx != 0) {
        explainStr.append("\n");
      }

      explainStr.append("Plan Fragment " + planFragIdx + "\n");
      DataSink dataSink = dataSinks.get(planFragIdx);
      PlanNode fragment = planFragments.get(planFragIdx);
      String expString;
      // Coordinator (can only be the first) fragment might not have an associated sink.
      if (dataSink == null) {
        Preconditions.checkState(planFragIdx == 0);
        expString = fragment.getExplainString("  ", explainLevel);
      } else {
        expString = dataSink.getExplainString("  ") +
            fragment.getExplainString("  ", explainLevel);
      }
      explainStr.append(expString);

      // Execution parameters of the current plan fragment
      String execParamExplain = getExecParamExplainString(request, planFragIdx);
      explainStr.append(execParamExplain);
    }

  }

  /**
   * Add aggregation, HAVING predicate and sort node for single-node execution.
   */
  private PlanNode createSingleNodePlan(
      Analyzer analyzer, PlanNode spjPlan, SelectStmt selectStmt) {
    // add aggregation, if required, but without having predicate
    PlanNode root = spjPlan;
    if (selectStmt.getAggInfo() != null) {
      AggregateInfo aggInfo = selectStmt.getAggInfo();
      root = new AggregationNode(new PlanNodeId(nodeIdGenerator), root, aggInfo);

      // if we're computing DISTINCT agg fns, the analyzer already created the
      // 2nd phase agginfo
      if (aggInfo.isDistinctAgg()) {
        root = new AggregationNode(
            new PlanNodeId(nodeIdGenerator), root,
            aggInfo.getSecondPhaseDistinctAggInfo());
      }
    }

    if (selectStmt.getHavingPred() != null) {
      Preconditions.checkState(root instanceof AggregationNode);
      // only enforce having predicate after the final aggregation step
      // TODO: substitute having pred
      root.setConjuncts(selectStmt.getHavingPred().getConjuncts());
      analyzer.markConjunctsAssigned(selectStmt.getHavingPred().getConjuncts());
    }

    SortInfo sortInfo = selectStmt.getSortInfo();
    if (sortInfo != null) {
      Preconditions.checkState(selectStmt.getLimit() != -1);
      root = new SortNode(new PlanNodeId(nodeIdGenerator), root, sortInfo, true);
    }

    return root;
  }

  /**
   * Add aggregation, HAVING predicate and sort node for multi-node execution.
   */
  private void createMultiNodePlans(
      Analyzer analyzer, PlanNode spjPlan, SelectStmt selectStmt,
      int numNodes, Reference<PlanNode> coordRef, Reference<PlanNode> slaveRef)
      throws InternalException {
    // plan fragment executed by the coordinator, which does merging and
    // post-aggregation, if applicable
    PlanNode coord = null;

    // plan fragment executed by slave that feeds into coordinator;
    // does everything aside from application of Having predicate and final
    // sorting/top-n step
    PlanNode slave = spjPlan;

    // add aggregation to slave, if required, but without having predicate
    AggregateInfo aggInfo = selectStmt.getAggInfo();
    if (aggInfo != null) {
      slave = new AggregationNode(new PlanNodeId(nodeIdGenerator), slave, aggInfo, true);
    }

    // create coordinator plan fragment (single ExchangeNode, possibly
    // followed by a merge aggregation step and a top-n node)
    coord = new ExchangeNode(new PlanNodeId(nodeIdGenerator), slave.getTupleIds());
    coord.rowTupleIds = slave.rowTupleIds;
    coord.nullableTupleIds = slave.nullableTupleIds;

    if (aggInfo != null && aggInfo.getMergeAggInfo() != null) {
      coord = new AggregationNode(
          new PlanNodeId(nodeIdGenerator), coord, aggInfo.getMergeAggInfo());
    }

    if (selectStmt.getHavingPred() != null) {
      Preconditions.checkState(coord instanceof AggregationNode);
      // only enforce having predicate after the final aggregation step
      // TODO: substitute having pred
      coord.setConjuncts(selectStmt.getHavingPred().getConjuncts());
      analyzer.markConjunctsAssigned(selectStmt.getHavingPred().getConjuncts());
    }

    // top-n is always applied at the very end
    // (if we wanted to apply top-n to the slave plan fragments, they would need to be
    // fragmented by the grouping exprs of the GROUP BY, which would only be possible
    // if we grouped by the table's partitioning exprs, and even then do we want
    // to use HDFS file splits for plan fragmentation)
    SortInfo sortInfo = selectStmt.getSortInfo();
    if (sortInfo != null) {
      Preconditions.checkState(selectStmt.getLimit() != -1);
      coord = new SortNode(new PlanNodeId(nodeIdGenerator), coord, sortInfo, true);
    }

    coordRef.setRef(coord);
    slaveRef.setRef(slave);
  }

  /**
   * Given an analysisResult, creates a sequence of plan fragments that implement the
   * query.
   *
   * @param analysisResult
   *          result of query analysis
   * @param queryOptions
   *          user specified query options; only num_nodes and max_scan_range_length are
   *          used.
   * @param explainString output parameter of the explain plan string, if not null
   * @return query exec request containing plan fragments and all execution parameters
   */
  public TQueryExecRequest createPlanFragments(
      AnalysisContext.AnalysisResult analysisResult, TQueryOptions queryOptions,
      StringBuilder explainString)
      throws NotImplementedException, InternalException {
    // Only num_nodes and max_scan_range_length are used
    int numNodes = queryOptions.num_nodes;
    long maxScanRangeLength = queryOptions.max_scan_range_length;

    // Set queryStmt from analyzed SELECT or INSERT query.
    QueryStmt queryStmt = null;
    if (analysisResult.isInsertStmt()) {
      queryStmt = analysisResult.getInsertStmt().getQueryStmt();
    } else {
      queryStmt = analysisResult.getQueryStmt();
    }
    Analyzer analyzer = analysisResult.getAnalyzer();

    // Global query parameters to be set in each TPlanExecRequest.
    TQueryGlobals queryGlobals = createQueryGlobals(analyzer);

    TQueryExecRequest request = new TQueryExecRequest();
    // root: the node producing the final output (ie, coord plan for distrib. execution)
    PlanNode root = null;
    // slave: only set for distrib. execution; plan feeding into coord
    PlanNode slave = null;
    if (queryStmt instanceof SelectStmt) {
      SelectStmt selectStmt = (SelectStmt) queryStmt;

      PlanNode spjPlan = createSpjPlan(selectStmt, analyzer);
      if (spjPlan == null) {
        // SELECT without FROM clause
        // TODO: This incorrectly plans INSERT INTO TABLE ... SELECT 1 as a
        // SELECT CONSTANT plan with no sink. The backend won't correctly execute
        // such a plan at the moment anyhow, but this bug causes the query to return
        // results, which is counterintuitive.
        TPlanExecRequest fragmentRequest = new TPlanExecRequest(
            new TUniqueId(), new TUniqueId(),
            Expr.treesToThrift(selectStmt.getResultExprs()), queryGlobals,
            new TQueryOptions());
        request.addToFragment_requests(fragmentRequest);
        request.has_coordinator_fragment = true;
        explainString.append("Plan Fragment " + 0 + "\n");
        explainString.append("  SELECT CONSTANT\n");
        return request;
      }

      // add aggregation/sort/etc. nodes;
      if (numNodes == 1) {
        root = createSingleNodePlan(analyzer, spjPlan, selectStmt);
      } else {
        Reference<PlanNode> rootRef = new Reference<PlanNode>();
        Reference<PlanNode> slaveRef = new Reference<PlanNode>();
        createMultiNodePlans(analyzer, spjPlan, selectStmt, numNodes, rootRef, slaveRef);
        root = rootRef.getRef();
        slave = slaveRef.getRef();
        slave.finalize(analyzer);
        markRefdSlots(slave, selectStmt, analyzer);
        if (root instanceof ExchangeNode) {
          // if all we're doing is merging results from the slaves, we can
          // also set the limit in the slaves
          slave.setLimit(selectStmt.getLimit());
        }
      }
    } else {
      Preconditions.checkState(queryStmt instanceof UnionStmt);
      // TODO: Implement multinode planning of UNION.
      if (numNodes != 1) {
        throw new NotImplementedException("Multinode planning of UNION not implemented.");
      }
      root = createUnionPlan((UnionStmt) queryStmt, analyzer);
    }

    root.setLimit(queryStmt.getLimit());
    root.finalize(analyzer);
    markRefdSlots(root, queryStmt, analyzer);
    // don't compute mem layout before marking slots that aren't being referenced
    analyzer.getDescTbl().computeMemLayout();

    // TODO: determine if slavePlan produces more slots than are being
    // ref'd by coordPlan; if so, insert MaterializationNode that trims the
    // output
    // TODO: substitute select list exprs against output of currentPlanRoot
    // probably best to add PlanNode.substMap
    // create plan fragments

    // create scan ranges and determine hosts; do this before serializing the
    // plan trees, otherwise we won't pick up on numSenders for exchange nodes
    ArrayList<ScanNode> scans = Lists.newArrayList();  // leftmost scan is first in list
    List<TScanRange> scanRanges = Lists.newArrayList();
    List<THostPort> dataLocations = Lists.newArrayList();
    if (numNodes == 1) {
      createPartitionParams(root, 1, maxScanRangeLength, scanRanges, dataLocations);
      root.collectSubclasses(ScanNode.class, scans);
    } else {
      createPartitionParams(slave, numNodes, maxScanRangeLength, scanRanges,
          dataLocations);
      slave.collectSubclasses(ScanNode.class, scans);
      ExchangeNode exchangeNode = root.findFirstOf(ExchangeNode.class);
      exchangeNode.setNumSenders(dataLocations.size());
    }

    // collect data sinks for explain string; dataSinks.size() == # of plan
    // fragments
    List<DataSink> dataSinks = Lists.newArrayList();
    List<PlanNode> planFragments = Lists.newArrayList();

    boolean coordinatorDoesInsert = (numNodes == 1);

    if (queryStmt instanceof SelectStmt) { // Could be UNION
      SelectStmt selectStmt = (SelectStmt)queryStmt;
      if ((selectStmt.getAggInfo() != null ||
           selectStmt.getHavingPred() != null ||
           selectStmt.getSortInfo() != null) ||
           selectStmt.hasOrderByClause() ||
           selectStmt.hasLimitClause()) {
        coordinatorDoesInsert = true;
      }
    }

    if (analysisResult.isInsertStmt()) {
      InsertStmt insertStmt = analysisResult.getInsertStmt();
      TFinalizeParams finalizeParams = new TFinalizeParams();

      finalizeParams.setIs_overwrite(insertStmt.isOverwrite());
      finalizeParams.setTable_name(insertStmt.getTargetTableName().getTbl());

      String db = insertStmt.getTargetTableName().getDb();
      // TODO: Why is this null?
      finalizeParams.setTable_db(db == null ? "" : db);

      String hdfsBaseDir = ((HdfsTable)insertStmt.getTargetTable()).getHdfsBaseDir();
      finalizeParams.setHdfs_base_dir(hdfsBaseDir);

      request.setFinalize_params(finalizeParams);
    }

    // create TPlanExecRequests and set up data sinks
    if (numNodes == 1) {
      TPlanExecRequest planRequest =
          createPlanExecRequest(root, analyzer.getDescTbl(), queryGlobals);
      planRequest.setOutput_exprs(
          Expr.treesToThrift(queryStmt.getResultExprs()));

      request.addToFragment_requests(planRequest);
      request.has_coordinator_fragment = true;
    } else {
      // coordinator fragment comes first, only if no insert
      if (!analysisResult.isInsertStmt() || coordinatorDoesInsert) {
        TPlanExecRequest coordRequest =
          createPlanExecRequest(root, analyzer.getDescTbl(), queryGlobals);
        coordRequest.setOutput_exprs(
            Expr.treesToThrift(queryStmt.getResultExprs()));

        request.addToFragment_requests(coordRequest);
        request.has_coordinator_fragment = true;
      }
      // create TPlanExecRequest for slave plan
      TPlanExecRequest slaveRequest =
          createPlanExecRequest(slave, analyzer.getDescTbl(), queryGlobals);

      // Choose sink for slave fragment.
      DataSink dataSink;
      if (analysisResult.isInsertStmt() && !coordinatorDoesInsert) {
        dataSink = analysisResult.getInsertStmt().createDataSink();
        slaveRequest.setOutput_exprs(
            Expr.treesToThrift(queryStmt.getResultExprs()));
        request.has_coordinator_fragment = false;
      } else {
        // Slaves write to stream data sink for an exchange node.
        ExchangeNode exchNode = root.findFirstOf(ExchangeNode.class);
        dataSink = new DataStreamSink(exchNode.getId());
      }

      slaveRequest.setData_sink(dataSink.toThrift());
      request.addToFragment_requests(slaveRequest);

      planFragments.add(slave);
      dataSinks.add(dataSink);
    }

    // create table data sink for insert stmt
    if (analysisResult.isInsertStmt() && coordinatorDoesInsert) {
      DataSink dataSink = analysisResult.getInsertStmt().createDataSink();
      request.fragment_requests.get(0).setData_sink(dataSink.toThrift());
      // this is the fragment producing the output; always add in first position
      planFragments.add(0, root);
      dataSinks.add(0, dataSink);
    } else {
      // record the fact that coord doesn't have a sink
      if (request.has_coordinator_fragment) {
        planFragments.add(0, root);
        dataSinks.add(0, null);
      }
    }

    // set request.dataLocations and request.nodeRequestParams
    createExecParams(request, scans, maxScanRangeLength, scanRanges, dataLocations,
        numNodes);

    // Build the explain plan string, if requested
    if (explainString != null) {
      buildExplainString(explainString, planFragments, dataSinks, request);
    }

    // All the conjuncts in the analyzer should be assigned
    Preconditions.checkState(!analyzer.hasUnassignedConjuncts());

    return request;
  }

  /**
   * Create query global parameters to be set in each TPlanExecRequest.
   */
  private TQueryGlobals createQueryGlobals(Analyzer analyzer) {
    TQueryGlobals queryGlobals = new TQueryGlobals();
    Calendar currentDate = Calendar.getInstance();
    String nowStr = formatter.format(currentDate.getTime());
    queryGlobals.setNow_string(nowStr);
    return queryGlobals;
  }

  /**
   * Creates the plan for a union stmt in three phases:
   * 1. If present, absorbs all DISTINCT-qualified operands into a single merge node,
   *    and adds an aggregation node on top to remove duplicates.
   * 2. If present, absorbs all ALL-qualified operands into a single merge node,
   *    also adding the subplan generated in 1 (if applicable).
   * 3. Set conjuncts if necessary, and add order by and limit.
   * The absorption of operands applies unnesting rules.
   */
  private PlanNode createUnionPlan(UnionStmt unionStmt, Analyzer analyzer)
      throws NotImplementedException, InternalException {
    List<UnionOperand> operands = unionStmt.getUnionOperands();
    Preconditions.checkState(operands.size() > 1);
    TupleDescriptor tupleDesc =
        analyzer.getDescTbl().getTupleDesc(unionStmt.getTupleId());

    MergeNode mergeNode = new MergeNode(new PlanNodeId(nodeIdGenerator), tupleDesc);
    PlanNode result = mergeNode;
    absorbUnionOperand(operands.get(0), mergeNode, operands.get(1).getQualifier());

    // Put DISTINCT operands into a single mergeNode.
    // Later, we'll put an agg node on top for duplicate removal.
    boolean hasDistinct = false;
    int opIx = 1;
    while (opIx < operands.size()) {
      UnionOperand operand = operands.get(opIx);
      if (operand.getQualifier() != Qualifier.DISTINCT) {
        break;
      }
      hasDistinct = true;
      absorbUnionOperand(operand, mergeNode, Qualifier.DISTINCT);
      ++opIx;
    }

    // If we generated a merge node for DISTINCT-qualified operands,
    // add an agg node on top to remove duplicates.
    AggregateInfo aggInfo = null;
    if (hasDistinct) {
      ArrayList<Expr> groupingExprs = Expr.cloneList(unionStmt.getResultExprs(), null);
      // Aggregate produces exactly the same tuple as the original union stmt.
      try {
        aggInfo =
            AggregateInfo.create(groupingExprs, null,
              analyzer.getDescTbl().getTupleDesc(unionStmt.getTupleId()), analyzer);
      } catch (AnalysisException e) {
        // this should never happen
        throw new InternalException("error creating agg info in createUnionPlan()");
      }
      result = new AggregationNode(new PlanNodeId(nodeIdGenerator), mergeNode, aggInfo);
      // If there are more operands, then add the distinct subplan as a child
      // of a new merge node which also merges the remaining ALL-qualified operands.
      if (opIx < operands.size()) {
        mergeNode = new MergeNode(new PlanNodeId(nodeIdGenerator), tupleDesc);
        mergeNode.addChild(result, unionStmt.getResultExprs());
        result = mergeNode;
      }
    }

    // Put all ALL-qualified operands into a single mergeNode.
    // During analysis we propagated DISTINCT to the left. Therefore,
    // we should only encounter ALL qualifiers at this point.
    while (opIx < operands.size()) {
      UnionOperand operand = operands.get(opIx);
      Preconditions.checkState(operand.getQualifier() == Qualifier.ALL);
      absorbUnionOperand(operand, mergeNode, Qualifier.ALL);
      ++opIx;
    }

    // A MergeNode may have predicates if a union is used inside an inline view,
    // and the enclosing select stmt has predicates on its columns.
    List<Predicate> conjuncts =
        analyzer.getBoundConjuncts(unionStmt.getTupleId().asList());
    // If the topmost node is an agg node, then set the conjuncts on its first child
    // (which must be a MergeNode), to evaluate the conjuncts as early as possible.
    if (!conjuncts.isEmpty() && result instanceof AggregationNode) {
      Preconditions.checkState(result.getChild(0) instanceof MergeNode);
      result.getChild(0).setConjuncts(conjuncts);
    } else {
      result.setConjuncts(conjuncts);
    }

    // Add order by and limit if present.
    SortInfo sortInfo = unionStmt.getSortInfo();
    if (sortInfo != null) {
      if (unionStmt.getLimit() == -1) {
        throw new NotImplementedException(
            "ORDER BY without LIMIT currently not supported");
      }
      result = new SortNode(new PlanNodeId(nodeIdGenerator), result, sortInfo, true);
    }
    result.setLimit(unionStmt.getLimit());

    // Mark slots as materialized.
    markRefdSlots(result, unionStmt, analyzer);

    return result;
  }

  /**
   * Absorbs the given operand into the topMergeNode, as follows:
   * 1. Operand's query stmt is a select stmt: Generate its plan
   *    and add it into topMergeNode.
   * 2. Operand's query stmt is a union stmt:
   *    Apply unnesting rules, i.e., check if the union stmt's operands
   *    can be directly added into the topMergeNode
   *    If unnesting is possible then absorb the union stmt's operands into topMergeNode,
   *    otherwise generate the union stmt's subplan and add it into the topMergeNode.
   * topQualifier refers to the qualifier of original operand which
   * was passed to absordUnionOperand() (i.e., at the root of the recursion)
   */
  private void absorbUnionOperand(UnionOperand operand, MergeNode topMergeNode,
      Qualifier topQualifier) throws NotImplementedException, InternalException {
    QueryStmt queryStmt = operand.getQueryStmt();
    Analyzer analyzer = operand.getAnalyzer();
    if (queryStmt instanceof SelectStmt) {
      SelectStmt selectStmt = (SelectStmt) queryStmt;
      PlanNode selectPlan = createSelectPlan(selectStmt, analyzer);
      if (selectPlan == null) {
        // Select with no FROM clause.
        topMergeNode.addConstExprList(selectStmt.getResultExprs());
      } else {
        topMergeNode.addChild(selectPlan, selectStmt.getResultExprs());
        markRefdSlots(selectPlan, selectStmt, analyzer);
      }
      return;
    }

    Preconditions.checkState(queryStmt instanceof UnionStmt);
    UnionStmt unionStmt = (UnionStmt) queryStmt;
    List<UnionOperand> unionOperands = unionStmt.getUnionOperands();
    // We cannot recursively absorb this union stmt's operands if either:
    // 1. The union stmt has a limit.
    // 2. Or the top qualifier is ALL and the first operand qualifier is not ALL.
    // Note that the first qualifier is ALL iff all operand qualifiers are ALL,
    // because DISTINCT is propagated to the left during analysis.
    if (unionStmt.hasLimitClause() || (topQualifier == Qualifier.ALL &&
        unionOperands.get(1).getQualifier() != Qualifier.ALL)) {
      PlanNode node = createUnionPlan(unionStmt, analyzer);
      markRefdSlots(node, queryStmt, analyzer);

      // If node is a MergeNode then it means it's operands are mixed ALL/DISTINCT.
      // We cannot directly absorb it's operands, but we can safely add
      // the MergeNode's children to topMergeNode if the UnionStmt has no limit.
      if (node instanceof MergeNode && !unionStmt.hasLimitClause()) {
        MergeNode mergeNode = (MergeNode) node;
        topMergeNode.getChildren().addAll(mergeNode.getChildren());
        topMergeNode.getResultExprLists().addAll(mergeNode.getResultExprLists());
        topMergeNode.getConstExprLists().addAll(mergeNode.getConstExprLists());
      } else {
        topMergeNode.addChild(node, unionStmt.getResultExprs());
      }
    } else {
      for (UnionOperand nestedOperand : unionStmt.getUnionOperands()) {
        absorbUnionOperand(nestedOperand, topMergeNode, topQualifier);
      }
    }
  }

  /**
   * Compute partitioning parameters (scan ranges and host / ports) for leftmost scan of
   * plan.
   */
  private void createPartitionParams(PlanNode plan, int numNodes, long maxScanRangeLength,
      List<TScanRange> scanRanges, List<THostPort> dataLocations) {
    ScanNode leftmostScan = getLeftmostScan(plan);
    if (leftmostScan == null) {
      // No scans in this plan.
      return;
    }
    int numPartitions;
    if (numNodes > 1) {
      // if we asked for a specific number of nodes, partition into numNodes - 1
      // fragments
      numPartitions = numNodes - 1;
    } else if (numNodes == Constants.NUM_NODES_ALL
        || numNodes == Constants.NUM_NODES_ALL_RACKS) {
      numPartitions = numNodes;
    } else {
      numPartitions = 1;
    }
    leftmostScan.getScanParams(maxScanRangeLength, numPartitions, scanRanges,
        dataLocations);
    if (scanRanges.isEmpty() && dataLocations.isEmpty()) {
      // if we're scanning an empty table we still need a single
      // host to execute the scan
      dataLocations.add(new THostPort("localhost", "127.0.0.1", 0));
    }
  }

  /**
   * Create TPlanExecRequest for root.
   */
  private TPlanExecRequest createPlanExecRequest(PlanNode root,
      DescriptorTable descTbl, TQueryGlobals queryGlobals) {
    TPlanExecRequest planRequest = new TPlanExecRequest();
    planRequest.setPlan_fragment(root.treeToThrift());
    planRequest.setDesc_tbl(descTbl.toThrift());
    planRequest.setQuery_globals(queryGlobals);
    planRequest.setQuery_options(new TQueryOptions());
    return planRequest;
  }

  /**
   * Set request.dataLocations and request.nodeRequestParams based on
   * scanRanges and data locations.
   */
  private void createExecParams(
      TQueryExecRequest request, ArrayList<ScanNode> scans, long maxScanRangeLength,
      List<TScanRange> scanRanges, List<THostPort> dataLocations, int numNodes) {
    // one TPlanExecParams per fragment/scan range;
    // we need to add an "empty" range for empty tables (in which case
    // scanRanges will be empty)
    List<TPlanExecParams> fragmentParamsList = Lists.newArrayList();

    if (numNodes != 1 && request.has_coordinator_fragment) {
      // For distributed queries, coord fragment doesn't scan any tables
      // and doesn't send the output anywhere
      request.addToNode_request_params(Lists.newArrayList(new TPlanExecParams()));
    }

    Iterator<TScanRange> scanRange = scanRanges.iterator();
    do {
      TPlanExecParams fragmentParams = new TPlanExecParams();
      if (scanRange.hasNext()) {
        fragmentParams.addToScan_ranges(scanRange.next());
      }
      THostPort address = new THostPort();
      address.hostname = "localhost";
      address.ipaddress = "127.0.0.1";
      address.port = 0;  // set elsewhere
      fragmentParams.setDestinations(Lists.newArrayList(address));
      fragmentParamsList.add(fragmentParams);
    } while (scanRange.hasNext());

    // add scan ranges for the non-partitioning scans to each of
    // fragmentRequests[1]'s parameters
    for (int i = 1; i < scans.size(); ++i) {
      ScanNode scan = scans.get(i);
      scanRanges = Lists.newArrayList();
      scan.getScanParams(maxScanRangeLength, 1, scanRanges, null);
      Preconditions.checkState(scanRanges.size() <= 1);
      if (!scanRanges.isEmpty()) {
        for (TPlanExecParams fragmentParams: fragmentParamsList) {
          fragmentParams.addToScan_ranges(scanRanges.get(0));
        }
      }
    }

    request.addToData_locations(dataLocations);
    request.addToNode_request_params(fragmentParamsList);
  }

}
