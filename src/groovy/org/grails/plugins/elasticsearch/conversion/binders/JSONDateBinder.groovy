package org.grails.plugins.elasticsearch.conversion.binders

import java.beans.PropertyEditorSupport
import java.text.SimpleDateFormat
import java.text.ParseException
import org.apache.log4j.Logger
import org.apache.log4j.Priority

public class JSONDateBinder extends PropertyEditorSupport {

  private static final Logger LOG = Logger.getLogger(JSONDateBinder.class)

  final List<String> formats

  public JSONDateBinder(List formats) {
    if (formats.size() == 0) {
        throw new Exception("list of configured date formats cannot be empty.")
    }
    this.formats = Collections.unmodifiableList(formats)
  }

  public void setAsText(String s) throws IllegalArgumentException {
    if (s != null) {
      for(format in formats){
        // Need to create the SimpleDateFormat every time, since it's not thread-safe
        SimpleDateFormat df = new SimpleDateFormat(format)
        try {
          setValue(df.parse(s))
          return
        } catch (ParseException e) {
          if (LOG.isEnabledFor(Priority.WARN) || LOG.traceEnabled) {
            int currentPos = formats.indexOf(format) + 1
            if (currentPos == formats.size()) {
              LOG.warn("Cannot parse date ${s} using format ${format}. This was the final attempt.")
            } else if (LOG.traceEnabled) {
              LOG.trace("Cannot parse date ${s} using format ${format}. This was attempt ${currentPos} of ${formats.size()}.")
            }
          }
        }
      }
    }
  }
}