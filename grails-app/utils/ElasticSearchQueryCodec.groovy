import org.grails.plugins.elasticsearch.util.ElasticSearchUtils

class ElasticSearchQueryCodec {
  static encode = {str ->
    return ElasticSearchUtils.escapeLuceneSpecialChars(str)
  }
}
