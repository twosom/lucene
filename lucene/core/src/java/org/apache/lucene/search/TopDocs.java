/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.util.PriorityQueue;

/** Represents hits returned by {@link IndexSearcher#search(Query,int)}. */
public class TopDocs {

  /** The total number of hits for the query. */
  public TotalHits totalHits;

  /** The top hits for the query. */
  public ScoreDoc[] scoreDocs;

  /** Internal comparator with shardIndex */
  private static final Comparator<ScoreDoc> SHARD_INDEX_TIE_BREAKER =
      Comparator.comparingInt(d -> d.shardIndex);

  /** Internal comparator with docID */
  private static final Comparator<ScoreDoc> DOC_ID_TIE_BREAKER =
      Comparator.comparingInt(d -> d.doc);

  /** Default comparator */
  private static final Comparator<ScoreDoc> DEFAULT_TIE_BREAKER =
      SHARD_INDEX_TIE_BREAKER.thenComparing(DOC_ID_TIE_BREAKER);

  /** Constructs a TopDocs. */
  public TopDocs(TotalHits totalHits, ScoreDoc[] scoreDocs) {
    this.totalHits = totalHits;
    this.scoreDocs = scoreDocs;
  }

  // Refers to one hit:
  private static final class ShardRef {
    // Which shard (index into shardHits[]):
    final int shardIndex;

    // Which hit within the shard:
    int hitIndex;

    ShardRef(int shardIndex) {
      this.shardIndex = shardIndex;
    }

    @Override
    public String toString() {
      return "ShardRef(shardIndex=" + shardIndex + " hitIndex=" + hitIndex + ")";
    }
  }

  /**
   * Use the tie breaker if provided. If tie breaker returns 0 signifying equal values, we use hit
   * indices to tie break intra shard ties
   */
  static boolean tieBreakLessThan(
      ShardRef first,
      ScoreDoc firstDoc,
      ShardRef second,
      ScoreDoc secondDoc,
      Comparator<ScoreDoc> tieBreaker) {
    assert tieBreaker != null;
    int value = tieBreaker.compare(firstDoc, secondDoc);

    if (value == 0) {
      // Equal Values
      // Tie break in same shard: resolve however the
      // shard had resolved it:
      assert first.hitIndex != second.hitIndex;
      return first.hitIndex < second.hitIndex;
    }

    return value < 0;
  }

  // LessThan that just sorts by relevance score, descending:
  private static class ScoreLessThan implements PriorityQueue.LessThan<ShardRef> {
    private final ScoreDoc[][] shardHits;
    private final Comparator<ScoreDoc> tieBreakerComparator;

    public ScoreLessThan(TopDocs[] shardHits, Comparator<ScoreDoc> tieBreakerComparator) {
      this.shardHits = new ScoreDoc[shardHits.length][];
      for (int shardIDX = 0; shardIDX < shardHits.length; shardIDX++) {
        this.shardHits[shardIDX] = shardHits[shardIDX].scoreDocs;
      }
      this.tieBreakerComparator = tieBreakerComparator;
    }

    // Returns true if first is < second
    @Override
    public boolean lessThan(ShardRef first, ShardRef second) {
      assert first != second;
      ScoreDoc firstScoreDoc = shardHits[first.shardIndex][first.hitIndex];
      ScoreDoc secondScoreDoc = shardHits[second.shardIndex][second.hitIndex];
      if (firstScoreDoc.score < secondScoreDoc.score) {
        return false;
      } else if (firstScoreDoc.score > secondScoreDoc.score) {
        return true;
      } else {
        return tieBreakLessThan(first, firstScoreDoc, second, secondScoreDoc, tieBreakerComparator);
      }
    }
  }

  private static class ShardRefLessThan implements PriorityQueue.LessThan<ShardRef> {
    // These are really FieldDoc instances:
    final ScoreDoc[][] shardHits;
    final FieldComparator<?>[] comparators;
    final int[] reverseMul;
    final Comparator<ScoreDoc> tieBreaker;

    private ShardRefLessThan(Sort sort, TopDocs[] shardHits, Comparator<ScoreDoc> tieBreaker) {
      this.shardHits = new ScoreDoc[shardHits.length][];
      this.tieBreaker = tieBreaker;
      for (int shardIDX = 0; shardIDX < shardHits.length; shardIDX++) {
        final ScoreDoc[] shard = shardHits[shardIDX].scoreDocs;
        // System.out.println("  init shardIdx=" + shardIDX + " hits=" + shard);
        if (shard != null) {
          this.shardHits[shardIDX] = shard;
          // Fail gracefully if API is misused:
          for (int hitIDX = 0; hitIDX < shard.length; hitIDX++) {
            final ScoreDoc sd = shard[hitIDX];
            if (!(sd instanceof FieldDoc)) {
              throw new IllegalArgumentException(
                  "shard "
                      + shardIDX
                      + " was not sorted by the provided Sort (expected FieldDoc but got ScoreDoc)");
            }
            final FieldDoc fd = (FieldDoc) sd;
            if (fd.fields == null) {
              throw new IllegalArgumentException(
                  "shard " + shardIDX + " did not set sort field values (FieldDoc.fields is null)");
            }
          }
        }
      }

      final SortField[] sortFields = sort.getSort();
      comparators = new FieldComparator<?>[sortFields.length];
      reverseMul = new int[sortFields.length];
      for (int compIDX = 0; compIDX < sortFields.length; compIDX++) {
        final SortField sortField = sortFields[compIDX];
        comparators[compIDX] = sortField.getComparator(1, Pruning.NONE);
        reverseMul[compIDX] = sortField.getReverse() ? -1 : 1;
      }
    }

    // Returns true if first is < second
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean lessThan(ShardRef first, ShardRef second) {
      assert first != second;
      final FieldDoc firstFD = (FieldDoc) shardHits[first.shardIndex][first.hitIndex];
      final FieldDoc secondFD = (FieldDoc) shardHits[second.shardIndex][second.hitIndex];
      // System.out.println("  lessThan:\n     first=" + first + " doc=" + firstFD.doc + " score=" +
      // firstFD.score + "\n    second=" + second + " doc=" + secondFD.doc + " score=" +
      // secondFD.score);

      for (int compIDX = 0; compIDX < comparators.length; compIDX++) {
        final FieldComparator comp = comparators[compIDX];
        // System.out.println("    cmp idx=" + compIDX + " cmp1=" + firstFD.fields[compIDX] + "
        // cmp2=" + secondFD.fields[compIDX] + " reverse=" + reverseMul[compIDX]);

        final int cmp =
            reverseMul[compIDX]
                * comp.compareValues(firstFD.fields[compIDX], secondFD.fields[compIDX]);

        if (cmp != 0) {
          // System.out.println("    return " + (cmp < 0));
          return cmp < 0;
        }
      }
      return tieBreakLessThan(first, firstFD, second, secondFD, tieBreaker);
    }
  }

  /**
   * Returns a new TopDocs, containing topN results across the provided TopDocs, sorting by score.
   * Each {@link TopDocs} instance must be sorted.
   *
   * @see #merge(int, int, TopDocs[])
   * @lucene.experimental
   */
  public static TopDocs merge(int topN, TopDocs[] shardHits) {
    return merge(0, topN, shardHits);
  }

  /**
   * Same as {@link #merge(int, TopDocs[])} but also ignores the top {@code start} top docs. This is
   * typically useful for pagination.
   *
   * <p>docIDs are expected to be in consistent pattern i.e. either all ScoreDocs have their
   * shardIndex set, or all have them as -1 (signifying that all hits belong to same searcher)
   *
   * @lucene.experimental
   */
  public static TopDocs merge(int start, int topN, TopDocs[] shardHits) {
    return mergeAux(null, start, topN, shardHits, DEFAULT_TIE_BREAKER);
  }

  /**
   * Same as above, but accepts the passed in tie breaker
   *
   * <p>docIDs are expected to be in consistent pattern i.e. either all ScoreDocs have their
   * shardIndex set, or all have them as -1 (signifying that all hits belong to same searcher)
   *
   * @lucene.experimental
   */
  public static TopDocs merge(
      int start, int topN, TopDocs[] shardHits, Comparator<ScoreDoc> tieBreaker) {
    return mergeAux(null, start, topN, shardHits, tieBreaker);
  }

  /**
   * Returns a new TopFieldDocs, containing topN results across the provided TopFieldDocs, sorting
   * by the specified {@link Sort}. Each of the TopDocs must have been sorted by the same Sort, and
   * sort field values must have been filled.
   *
   * @see #merge(Sort, int, int, TopFieldDocs[])
   * @lucene.experimental
   */
  public static TopFieldDocs merge(Sort sort, int topN, TopFieldDocs[] shardHits) {
    return merge(sort, 0, topN, shardHits);
  }

  /**
   * Same as {@link #merge(Sort, int, TopFieldDocs[])} but also ignores the top {@code start} top
   * docs. This is typically useful for pagination.
   *
   * <p>docIDs are expected to be in consistent pattern i.e. either all ScoreDocs have their
   * shardIndex set, or all have them as -1 (signifying that all hits belong to same searcher)
   *
   * @lucene.experimental
   */
  public static TopFieldDocs merge(Sort sort, int start, int topN, TopFieldDocs[] shardHits) {
    if (sort == null) {
      throw new IllegalArgumentException("sort must be non-null when merging field-docs");
    }
    return (TopFieldDocs) mergeAux(sort, start, topN, shardHits, DEFAULT_TIE_BREAKER);
  }

  /**
   * Pass in a custom tie breaker for ordering results
   *
   * @lucene.experimental
   */
  public static TopFieldDocs merge(
      Sort sort, int start, int topN, TopFieldDocs[] shardHits, Comparator<ScoreDoc> tieBreaker) {
    if (sort == null) {
      throw new IllegalArgumentException("sort must be non-null when merging field-docs");
    }
    return (TopFieldDocs) mergeAux(sort, start, topN, shardHits, tieBreaker);
  }

  /**
   * Auxiliary method used by the {@link #merge} impls. A sort value of null is used to indicate
   * that docs should be sorted by score.
   */
  private static TopDocs mergeAux(
      Sort sort, int start, int size, TopDocs[] shardHits, Comparator<ScoreDoc> tieBreaker) {

    final PriorityQueue<ShardRef> queue;
    if (sort == null) {
      queue = new PriorityQueue<>(shardHits.length, new ScoreLessThan(shardHits, tieBreaker));
    } else {
      queue =
          new PriorityQueue<>(shardHits.length, new ShardRefLessThan(sort, shardHits, tieBreaker));
    }

    long totalHitCount = 0;
    TotalHits.Relation totalHitsRelation = TotalHits.Relation.EQUAL_TO;
    int availHitCount = 0;
    for (int shardIDX = 0; shardIDX < shardHits.length; shardIDX++) {
      final TopDocs shard = shardHits[shardIDX];
      // totalHits can be non-zero even if no hits were
      // collected, when searchAfter was used:
      totalHitCount += shard.totalHits.value();
      // If any hit count is a lower bound then the merged
      // total hit count is a lower bound as well
      if (shard.totalHits.relation() == TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO) {
        totalHitsRelation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
      }
      if (shard.scoreDocs != null && shard.scoreDocs.length > 0) {
        availHitCount += shard.scoreDocs.length;
        queue.add(new ShardRef(shardIDX));
      }
    }

    final ScoreDoc[] hits;
    boolean unsetShardIndex = false;
    if (availHitCount <= start) {
      hits = new ScoreDoc[0];
    } else {
      hits = new ScoreDoc[Math.min(size, availHitCount - start)];
      int requestedResultWindow = start + size;
      int numIterOnHits = Math.min(availHitCount, requestedResultWindow);
      int hitUpto = 0;
      while (hitUpto < numIterOnHits) {
        assert queue.size() > 0;
        ShardRef ref = queue.top();
        final ScoreDoc hit = shardHits[ref.shardIndex].scoreDocs[ref.hitIndex++];

        // Irrespective of whether we use shard indices for tie breaking or not, we check for
        // consistent
        // order in shard indices to defend against potential bugs
        if (hitUpto > 0) {
          if (unsetShardIndex != (hit.shardIndex == -1)) {
            throw new IllegalArgumentException("Inconsistent order of shard indices");
          }
        }

        unsetShardIndex |= hit.shardIndex == -1;

        if (hitUpto >= start) {
          hits[hitUpto - start] = hit;
        }

        hitUpto++;

        if (ref.hitIndex < shardHits[ref.shardIndex].scoreDocs.length) {
          // Not done with this these TopDocs yet:
          queue.updateTop();
        } else {
          queue.pop();
        }
      }
    }

    TotalHits totalHits = new TotalHits(totalHitCount, totalHitsRelation);
    if (sort == null) {
      return new TopDocs(totalHits, hits);
    } else {
      return new TopFieldDocs(totalHits, hits, sort.getSort());
    }
  }

  private record ShardIndexAndDoc(int shardIndex, int doc) {}

  /**
   * Reciprocal Rank Fusion method.
   *
   * <p>This method combines different search results into a single ranked list by combining their
   * ranks. This is especially well suited when combining hits computed via different methods, whose
   * score distributions are hardly comparable.
   *
   * @param topN the top N results to be returned
   * @param k a constant determines how much influence documents in individual rankings have on the
   *     final result. A higher value gives lower rank documents more influence. k should be greater
   *     than or equal to 1.
   * @param hits a list of TopDocs to apply RRF on
   * @return a TopDocs contains the top N ranked results.
   */
  public static TopDocs rrf(int topN, int k, TopDocs[] hits) {
    if (topN < 1) {
      throw new IllegalArgumentException("topN must be >= 1, got " + topN);
    }
    if (k < 1) {
      throw new IllegalArgumentException("k must be >= 1, got " + k);
    }

    Boolean shardIndexSet = null;
    for (TopDocs topDocs : hits) {
      for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
        boolean thisShardIndexSet = scoreDoc.shardIndex != -1;
        if (shardIndexSet == null) {
          shardIndexSet = thisShardIndexSet;
        } else if (shardIndexSet.booleanValue() != thisShardIndexSet) {
          throw new IllegalArgumentException(
              "All hits must either have their ScoreDoc#shardIndex set, or unset (-1), not a mix of both.");
        }
      }
    }

    // Compute the rrf score as a double to reduce accuracy loss due to floating-point arithmetic.
    Map<ShardIndexAndDoc, Double> rrfScore = new HashMap<>();
    long totalHitCount = 0;
    for (TopDocs topDoc : hits) {
      // A document is a hit globally if it is a hit for any of the top docs, so we compute the
      // total hit count as the max total hit count.
      totalHitCount = Math.max(totalHitCount, topDoc.totalHits.value());
      for (int i = 0; i < topDoc.scoreDocs.length; ++i) {
        ScoreDoc scoreDoc = topDoc.scoreDocs[i];
        int rank = i + 1;
        double rrfScoreContribution = 1d / Math.addExact(k, rank);
        rrfScore.compute(
            new ShardIndexAndDoc(scoreDoc.shardIndex, scoreDoc.doc),
            (_, score) -> (score == null ? 0 : score) + rrfScoreContribution);
      }
    }

    List<Map.Entry<ShardIndexAndDoc, Double>> rrfScoreRank = new ArrayList<>(rrfScore.entrySet());
    rrfScoreRank.sort(
        // Sort by descending score
        Map.Entry.<ShardIndexAndDoc, Double>comparingByValue()
            .reversed()
            // Tie-break by doc ID, then shard index (like TopDocs#merge)
            .thenComparing(
                Map.Entry.<ShardIndexAndDoc, Double>comparingByKey(
                    Comparator.comparingInt(ShardIndexAndDoc::doc)))
            .thenComparing(
                Map.Entry.<ShardIndexAndDoc, Double>comparingByKey(
                    Comparator.comparingInt(ShardIndexAndDoc::shardIndex))));

    ScoreDoc[] rrfScoreDocs = new ScoreDoc[Math.min(topN, rrfScoreRank.size())];
    for (int i = 0; i < rrfScoreDocs.length; i++) {
      Map.Entry<ShardIndexAndDoc, Double> entry = rrfScoreRank.get(i);
      int doc = entry.getKey().doc;
      int shardIndex = entry.getKey().shardIndex();
      float score = entry.getValue().floatValue();
      rrfScoreDocs[i] = new ScoreDoc(doc, score, shardIndex);
    }

    TotalHits totalHits = new TotalHits(totalHitCount, TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO);
    return new TopDocs(totalHits, rrfScoreDocs);
  }
}
