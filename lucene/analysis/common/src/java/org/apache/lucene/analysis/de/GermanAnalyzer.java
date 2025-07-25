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
package org.apache.lucene.analysis.de;

// This file is encoded in UTF-8

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.StopwordAnalyzerBase;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.WordlistLoader;
import org.apache.lucene.analysis.miscellaneous.SetKeywordMarkerFilter;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.util.IOUtils;

/**
 * {@link Analyzer} for German language.
 *
 * <p>Supports an external list of stopwords (words that will not be indexed at all) and an external
 * list of exclusions (word that will not be stemmed, but indexed). A default set of stopwords is
 * used unless an alternative list is specified, but the exclusion list is empty by default.
 *
 * <p><b>NOTE</b>: This class uses the same {@link org.apache.lucene.util.Version} dependent
 * settings as {@link StandardAnalyzer}.
 *
 * <p><b>NOTE</b>: This class does not decompound nouns, additional data files are needed,
 * incompatible with the Apache 2.0 License. You can find these data files and example code for
 * decompounding <a
 * href="https://github.com/uschindler/german-decompounder#lucene-api-example">here</a>.
 *
 * @since 3.1
 * @see <a
 *     href="https://github.com/uschindler/german-decompounder">https://github.com/uschindler/german-decompounder</a>
 */
public final class GermanAnalyzer extends StopwordAnalyzerBase {

  /** File containing default German stopwords. */
  public static final String DEFAULT_STOPWORD_FILE = "german_stop.txt";

  /**
   * Returns a set of default German-stopwords
   *
   * @return a set of default German-stopwords
   */
  public static final CharArraySet getDefaultStopSet() {
    return DefaultSetHolder.DEFAULT_SET;
  }

  private static class DefaultSetHolder {
    private static final CharArraySet DEFAULT_SET;

    static {
      try {
        DEFAULT_SET =
            WordlistLoader.getSnowballWordSet(
                IOUtils.requireResourceNonNull(
                    SnowballFilter.class.getResourceAsStream(DEFAULT_STOPWORD_FILE),
                    DEFAULT_STOPWORD_FILE));
      } catch (IOException ex) {
        // default set should always be present as it is part of the
        // distribution (JAR)
        throw new UncheckedIOException("Unable to load default stopword set", ex);
      }
    }
  }

  /** Contains words that should be indexed but not stemmed. */
  private final CharArraySet exclusionSet;

  /** Builds an analyzer with the default stop words: {@link #getDefaultStopSet()}. */
  public GermanAnalyzer() {
    this(DefaultSetHolder.DEFAULT_SET);
  }

  /**
   * Builds an analyzer with the given stop words
   *
   * @param stopwords a stopword set
   */
  public GermanAnalyzer(CharArraySet stopwords) {
    this(stopwords, CharArraySet.EMPTY_SET);
  }

  /**
   * Builds an analyzer with the given stop words
   *
   * @param stopwords a stopword set
   * @param stemExclusionSet a stemming exclusion set
   */
  public GermanAnalyzer(CharArraySet stopwords, CharArraySet stemExclusionSet) {
    super(stopwords);
    exclusionSet = CharArraySet.unmodifiableSet(CharArraySet.copy(stemExclusionSet));
  }

  /**
   * Creates {@link org.apache.lucene.analysis.Analyzer.TokenStreamComponents} used to tokenize all
   * the text in the provided {@link Reader}.
   *
   * @return {@link org.apache.lucene.analysis.Analyzer.TokenStreamComponents} built from a {@link
   *     StandardTokenizer} filtered with {@link LowerCaseFilter}, {@link StopFilter}, {@link
   *     SetKeywordMarkerFilter} if a stem exclusion set is provided, {@link
   *     GermanNormalizationFilter} and {@link GermanLightStemFilter}
   */
  @Override
  protected TokenStreamComponents createComponents(String fieldName) {
    final Tokenizer source = new StandardTokenizer();
    TokenStream result = new LowerCaseFilter(source);
    result = new StopFilter(result, stopwords);
    result = new SetKeywordMarkerFilter(result, exclusionSet);
    result = new GermanNormalizationFilter(result);
    result = new GermanLightStemFilter(result);
    return new TokenStreamComponents(source, result);
  }

  @Override
  protected TokenStream normalize(String fieldName, TokenStream in) {
    TokenStream result = new LowerCaseFilter(in);
    result = new GermanNormalizationFilter(result);
    return result;
  }
}
