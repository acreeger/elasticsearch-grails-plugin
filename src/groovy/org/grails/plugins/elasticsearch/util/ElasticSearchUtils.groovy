package org.grails.plugins.elasticsearch.util

import org.codehaus.groovy.grails.commons.ApplicationHolder
import java.util.regex.Pattern

class ElasticSearchUtils {
  public static indexDomain(entity){
    def elasticSearchService = ApplicationHolder.application.mainContext.getBean('elasticSearchService')
    elasticSearchService.indexDomain(entity)
  }

  public static deleteDomain(entity){
    def elasticSearchService = ApplicationHolder.application.mainContext.getBean('elasticSearchService')
    elasticSearchService.deleteDomain(entity)
  }

  private static final Pattern AND_OR_Pattern = Pattern.compile(/(^(\s*(OR|AND))+\b)|(\b((AND|OR)\s*)+$)/)
  private static final Pattern Backslash_Pattern = Pattern.compile(/(\\)(?!")/) // A backslash not followed by a quote
  private static final Pattern UnescapedQuote_Pattern = Pattern.compile(/(?<!\\)"/) //a quote, not prefixed by backslash

  private static final Pattern LuceneSpecialCharacter_Pattern = Pattern.compile(/(\+|\-|\&\&|\|\||\!|\(|\)|\{|\}|\[|\]|\^|\~|\*|\?|\:)/) //note the pipe | and ampersand & are escaped

  /**
   * Strip out any user entered Lucene special characters
   * @param query  - the user entered query
   * @return - the query adjusted
   */
  public static Object escapeLuceneSpecialChars(Object query) {
    if (!(query instanceof String)) return query;
    String strQuery = (String)query;

    strQuery = escapeBackslashes(strQuery)

    strQuery = removeOddNumberOfUnescapedQuotes(strQuery)

    strQuery = removeDangerousWrittenOperators(strQuery)

    strQuery = strQuery.replaceAll(LuceneSpecialCharacter_Pattern,/\\$1/);

    return strQuery.trim();
  }

  private static def escapeBackslashes(String strQuery) {
    return strQuery.replaceAll(Backslash_Pattern,/\\$1/ /*replace with two slashes*/)
  }

  private static def removeOddNumberOfUnescapedQuotes(String strQuery) {
    if ((strQuery =~ UnescapedQuote_Pattern).size() % 2 == 1) {
        strQuery = strQuery.replaceAll(UnescapedQuote_Pattern, "")
    }
    return strQuery
  }

  private static def removeDangerousWrittenOperators(String strQuery) {
    if (!strQuery) return strQuery

    strQuery = strQuery.replaceAll(AND_OR_Pattern,'')

    return strQuery
  }
}
