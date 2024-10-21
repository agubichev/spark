/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression}
import org.apache.spark.sql.catalyst.trees.TreePattern.{TreePattern, UNRESOLVED_HINT}

/**
 * A general hint for the child that is not yet resolved. This node is generated by the parser and
 * should be removed This node will be eliminated post analysis.
 * @param name the name of the hint
 * @param parameters the parameters of the hint
 * @param child the [[LogicalPlan]] on which this hint applies
 */
case class UnresolvedHint(name: String, parameters: Seq[Expression], child: LogicalPlan)
  extends UnaryNode {

  // we need it to be resolved so that the analyzer can continue to analyze the rest of the query
  // plan.
  override lazy val resolved: Boolean = child.resolved

  override def output: Seq[Attribute] = child.output
  final override val nodePatterns: Seq[TreePattern] = Seq(UNRESOLVED_HINT)

  override protected def withNewChildInternal(newChild: LogicalPlan): UnresolvedHint =
    copy(child = newChild)
}

/**
 * A resolved hint node. The analyzer should convert all [[UnresolvedHint]] into [[ResolvedHint]].
 * This node will be eliminated before optimization starts.
 */
case class ResolvedHint(child: LogicalPlan, hints: HintInfo = HintInfo())
  extends UnaryNode {

  override def output: Seq[Attribute] = child.output

  override def doCanonicalize(): LogicalPlan = child.canonicalized

  override protected def withNewChildInternal(newChild: LogicalPlan): ResolvedHint =
    copy(child = newChild)
}

/**
 * Hint that is associated with a [[Join]] node, with [[HintInfo]] on its left child and on its
 * right child respectively.
 */
case class JoinHint(leftHint: Option[HintInfo], rightHint: Option[HintInfo]) {

  def isEmpty: Boolean = leftHint.isEmpty && rightHint.isEmpty

  override def toString: String = {
    Seq(
      leftHint.map("leftHint=" + _),
      rightHint.map("rightHint=" + _))
      .filter(_.isDefined).map(_.get).mkString(", ")
  }
}

object JoinHint {
  val NONE = JoinHint(None, None)
}

/**
 * The hint attributes to be applied on a specific node.
 *
 * @param strategy The preferred join strategy.
 */
case class HintInfo(strategy: Option[JoinStrategyHint] = None) {

  /**
   * Combine this [[HintInfo]] with another [[HintInfo]] and return the new [[HintInfo]].
   * @param other the other [[HintInfo]]
   * @param hintErrorHandler the error handler to notify if any [[HintInfo]] has been overridden
   *                         in this merge.
   *
   * Currently, for join strategy hints, the new [[HintInfo]] will contain the strategy in this
   * [[HintInfo]] if defined, otherwise the strategy in the other [[HintInfo]]. The
   * `hintOverriddenCallback` will be called if this [[HintInfo]] and the other [[HintInfo]]
   * both have a strategy defined but the join strategies are different.
   */
  def merge(other: HintInfo, hintErrorHandler: HintErrorHandler): HintInfo = {
    if (this.strategy.isDefined &&
        other.strategy.isDefined &&
        this.strategy.get != other.strategy.get) {
      hintErrorHandler.hintOverridden(other)
    }
    HintInfo(strategy = this.strategy.orElse(other.strategy))
  }

  override def toString: String = strategy.map(s => s"(strategy=$s)").getOrElse("none")
}

sealed abstract class JoinStrategyHint {

  def displayName: String
  def hintAliases: Set[String]

  override def toString: String = displayName
}

/**
 * The enumeration of join strategy hints.
 *
 * The hinted strategy will be used for the join with which it is associated if doable. In case
 * of contradicting strategy hints specified for each side of the join, hints are prioritized as
 * BROADCAST over SHUFFLE_MERGE over SHUFFLE_HASH over SHUFFLE_REPLICATE_NL.
 */
object JoinStrategyHint {

  val strategies: Set[JoinStrategyHint] = Set(
    BROADCAST,
    SHUFFLE_MERGE,
    SHUFFLE_HASH,
    SHUFFLE_REPLICATE_NL)
}

/**
 * The hint for broadcast hash join or broadcast nested loop join, depending on the availability of
 * equi-join keys.
 */
case object BROADCAST extends JoinStrategyHint {
  override def displayName: String = "broadcast"
  override def hintAliases: Set[String] = Set(
    "BROADCAST",
    "BROADCASTJOIN",
    "MAPJOIN")
}

/**
 * The hint for shuffle sort merge join.
 */
case object SHUFFLE_MERGE extends JoinStrategyHint {
  override def displayName: String = "merge"
  override def hintAliases: Set[String] = Set(
    "SHUFFLE_MERGE",
    "MERGE",
    "MERGEJOIN")
}

/**
 * The hint for shuffle hash join.
 */
case object SHUFFLE_HASH extends JoinStrategyHint {
  override def displayName: String = "shuffle_hash"
  override def hintAliases: Set[String] = Set(
    "SHUFFLE_HASH")
}

/**
 * The hint for shuffle-and-replicate nested loop join, a.k.a. cartesian product join.
 */
case object SHUFFLE_REPLICATE_NL extends JoinStrategyHint {
  override def displayName: String = "shuffle_replicate_nl"
  override def hintAliases: Set[String] = Set(
    "SHUFFLE_REPLICATE_NL")
}

/**
 * An internal hint to discourage broadcast hash join, used by adaptive query execution.
 */
case object NO_BROADCAST_HASH extends JoinStrategyHint {
  override def displayName: String = "no_broadcast_hash"
  override def hintAliases: Set[String] = Set.empty
}

/**
 * An internal hint to encourage shuffle hash join, used by adaptive query execution.
 */
case object PREFER_SHUFFLE_HASH extends JoinStrategyHint {
  override def displayName: String = "prefer_shuffle_hash"
  override def hintAliases: Set[String] = Set.empty
}

/**
 * An internal hint to prohibit broadcasting and replicating one side of a join. This hint is used
 * by some rules where broadcasting or replicating a particular side of the join is not permitted,
 * such as the cardinality check in MERGE operations.
 */
case object NO_BROADCAST_AND_REPLICATION extends JoinStrategyHint {
  override def displayName: String = "no_broadcast_and_replication"
  override def hintAliases: Set[String] = Set.empty
}

abstract class AggregateHint;

/**
 * The callback for implementing customized strategies of handling hint errors.
 */
trait HintErrorHandler {

  /**
   * Callback for an unknown hint.
   * @param name the unrecognized hint name
   * @param parameters the hint parameters
   */
  def hintNotRecognized(name: String, parameters: Seq[Any]): Unit

  /**
   * Callback for relation names specified in a hint that cannot be associated with any relation
   * in the current scope.
   * @param name the hint name
   * @param parameters the hint parameters
   * @param invalidRelations the set of relation names that cannot be associated
   */
  def hintRelationsNotFound(
    name: String, parameters: Seq[Any], invalidRelations: Set[Seq[String]]): Unit

  /**
   * Callback for a join hint specified on a relation that is not part of a join.
   * @param hint the [[HintInfo]]
   */
  def joinNotFoundForJoinHint(hint: HintInfo): Unit

  /**
   * Callback for a join hint specified on a join that doesn't support this build side or
   * doesn't have equi-join keys for equi-join.
   */
  def joinHintNotSupported(hint: HintInfo, reason: String): Unit

  /**
   * Callback for a hint being overridden by another conflicting hint of the same kind.
   * @param hint the [[HintInfo]] being overridden
   */
  def hintOverridden(hint: HintInfo): Unit
}
